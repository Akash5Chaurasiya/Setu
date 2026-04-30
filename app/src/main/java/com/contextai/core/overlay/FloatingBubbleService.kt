package com.contextai.core.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.contextai.R
import com.contextai.core.ConversationWindowManager
import com.contextai.core.HapticEngine
import com.contextai.core.SharedContextBus
import com.contextai.core.accessibility.ScreenContextExtractor
import com.contextai.core.security.AccessibilityGate
import com.contextai.core.security.MicrophoneGate
import com.contextai.core.UrlSummarizer
import com.contextai.core.network.ClaudeRepository
import com.contextai.data.local.AppMemoryDao
import com.contextai.data.local.ConversationDao
import android.widget.Toast
import com.contextai.domain.model.ApiResult
import com.contextai.domain.model.ConversationEntity
import com.contextai.domain.model.EmptyScreenContext
import com.contextai.domain.model.ScreenContext
import com.contextai.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.sqrt

@AndroidEntryPoint
class FloatingBubbleService : Service() {

    @Inject lateinit var claudeRepository: ClaudeRepository
    @Inject lateinit var conversationDao: ConversationDao
    @Inject lateinit var conversationManager: ConversationWindowManager
    @Inject lateinit var appMemoryDao: AppMemoryDao
    @Inject lateinit var urlSummarizer: UrlSummarizer
    @Inject lateinit var accessibilityGate: AccessibilityGate
    @Inject lateinit var microphoneGate: MicrophoneGate

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private var bubbleViewController: BubbleViewController? = null
    private var actionPanelViewController: ActionPanelViewController? = null

    private var clipboardWatcher: ClipboardWatcherService? = null
    private var lastScreenContext: ScreenContext = EmptyScreenContext

    private val captureMainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val captureDisableRunnable = Runnable { ScreenContextExtractor.isCaptureEnabled = false }

    private val sensitivePackagePrefixes = setOf(
        "com.google.android.apps.nbu",
        "net.one97.paytm",
        "com.phonepe.app",
        "in.org.npci.upiapp",
        "com.mobikwik_new",
        "com.axis.mobile",
        "com.sbi.lotusintouch",
        "com.csam.icici.bank",
        "com.hdfcbank.mobilebanking",
        "com.snapwork.hdfc",
        "com.kotak.mahindra.kotak",
        "com.rbl.rblmobilebanking",
        "com.idfc.firstmobile",
        "com.amazon.mShop.android.shopping",
        "com.whatsapp",
        "org.thoughtcrime.securesms"
    )

    private fun isSensitiveApp(packageName: String): Boolean =
        sensitivePackagePrefixes.any { packageName.startsWith(it) }

    // Shake detection
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private var lastShakeTime = 0L
    private var shakeCount = 0

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val acceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat() - 9.81f
            if (acceleration > 12f) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime < 500) {
                    shakeCount++
                    if (shakeCount >= 2) {
                        onDoubleShake()
                        shakeCount = 0
                    }
                } else {
                    shakeCount = 1
                }
                lastShakeTime = now
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initBubble()
        startClipboardWatcher()
        startShakeDetection()
        Timber.i("FloatingBubbleService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SHOW -> bubbleViewController?.show()
            ACTION_HIDE -> bubbleViewController?.hide()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ScreenContextExtractor.isCaptureEnabled = false
        accessibilityGate.close()
        microphoneGate.close()
        captureMainHandler.removeCallbacks(captureDisableRunnable)
        super.onDestroy()
        stopShakeDetection()
        clipboardWatcher?.stopWatching()
        serviceScope.cancel()
        try {
            actionPanelViewController?.destroy()
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Action panel already removed on destroy")
        }
        try {
            bubbleViewController?.destroy()
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Bubble already removed on destroy")
        }
        Timber.i("FloatingBubbleService destroyed")
    }

    private fun initBubble() {
        bubbleViewController = BubbleViewController(
            context = this,
            windowManager = windowManager,
            onSingleTap = { onBubbleTapped() },
            onLongPress = { /* radial menu handles actions */ },
            onRadialAction = { label -> onRadialActionSelected(label) },
            onClipBadgeTap = { label, type, content ->
                onClipBadgeTapped(label, type, content)
            },
            accessibilityGate = accessibilityGate
        )
        bubbleViewController!!.show()
    }

    private fun onBubbleTapped() {
        if (actionPanelViewController != null) return

        captureMainHandler.removeCallbacks(captureDisableRunnable)
        ScreenContextExtractor.isCaptureEnabled = true
        accessibilityGate.open()
        captureMainHandler.postDelayed(captureDisableRunnable, 30_000L)
        bubbleViewController?.startGateCountdown()

        bubbleViewController?.showProcessing(true)
        HapticEngine.bubbleTap(this)

        serviceScope.launch {
            val ctx = fetchScreenContext()
            lastScreenContext = ctx
            bubbleViewController?.showProcessing(false)
            if (isSensitiveApp(ctx.appPackage)) {
                Toast.makeText(this@FloatingBubbleService, "ContextAI is paused for this app to protect your data.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showActionPanel(ctx)
        }
    }

    private suspend fun fetchScreenContext(): ScreenContext =
        if (SharedContextBus.accessibilityEnabled.value) {
            SharedContextBus.requestContext()
            withTimeoutOrNull(CONTEXT_TIMEOUT_MS) {
                SharedContextBus.contextResult.first()
            } ?: EmptyScreenContext
        } else {
            EmptyScreenContext
        }

    private fun onRadialActionSelected(label: String) {
        when (label) {
            "History", "Settings" -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_OPEN_SETTINGS, label == "Settings")
                }
                startActivity(intent)
            }
            "What is this?" -> {
                captureMainHandler.removeCallbacks(captureDisableRunnable)
                ScreenContextExtractor.isCaptureEnabled = true
                captureMainHandler.postDelayed(captureDisableRunnable, 30_000L)
                serviceScope.launch {
                    val ctx = fetchScreenContext()
                    lastScreenContext = ctx
                    if (actionPanelViewController == null) {
                        showActionPanelForWhatIsThis(ctx)
                    } else {
                        actionPanelViewController?.triggerWhatIsThis(ctx)
                    }
                }
            }
            "Close" -> stopSelf()
        }
    }

    private fun onClipBadgeTapped(
        label: String,
        type: ClipboardWatcherService.ClipType,
        content: String
    ) {
        if (actionPanelViewController != null) {
            actionPanelViewController?.showWithClipboardContent(label, type, content, lastScreenContext)
        } else {
            showActionPanel(lastScreenContext)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                actionPanelViewController?.showWithClipboardContent(label, type, content, lastScreenContext)
            }, 300)
        }
    }

    private fun showActionPanel(screenContext: ScreenContext) {
        val panel = buildPanel()
        actionPanelViewController = panel
        runCatching {
            panel.show(screenContext)
        }.onFailure {
            Timber.e(it, "Failed to show action panel")
            actionPanelViewController = null
        }
    }

    private fun showActionPanelForWhatIsThis(screenContext: ScreenContext) {
        val panel = buildPanel()
        actionPanelViewController = panel
        runCatching {
            panel.triggerWhatIsThis(screenContext)
        }.onFailure {
            Timber.e(it, "Failed to show what-is-this panel")
            actionPanelViewController = null
        }
    }

    private fun buildPanel(): ActionPanelViewController = ActionPanelViewController(
        context = this,
        windowManager = windowManager,
        scope = serviceScope,
        onQuerySubmit = { query, ctx -> streamQuery(query, ctx) },
        onSaveHistory = { query, response, ctx -> saveConversation(query, response, ctx) },
        onNewConversation = { conversationManager.reset() },
        onDismissed = {
            actionPanelViewController = null
            conversationManager.reset()
            bubbleViewController?.signalDone()
            accessibilityGate.close()
            microphoneGate.close()
            captureMainHandler.removeCallbacks(captureDisableRunnable)
        },
        appMemoryDao = appMemoryDao,
        urlSummarizer = urlSummarizer,
        microphoneGate = microphoneGate
    )

    private fun startClipboardWatcher() {
        clipboardWatcher = ClipboardWatcherService(this) { label, type, content ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                bubbleViewController?.showClipBadge(label, type, content)
            }
        }
        clipboardWatcher?.startWatching()
    }

    private fun startShakeDetection() {
        sensorManager.registerListener(
            shakeListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    private fun stopShakeDetection() {
        sensorManager.unregisterListener(shakeListener)
    }

    private fun onDoubleShake() {
        HapticEngine.shakeDetected(this)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (actionPanelViewController?.isPanelVisible() == true) {
                actionPanelViewController?.dismissPanel()
            } else {
                bubbleViewController?.temporarilyHide()
            }
        }
    }

    private fun streamQuery(query: String, ctx: ScreenContext): Flow<ApiResult<String>> {
        return claudeRepository.streamResponse(query, ctx)
    }

    private fun saveConversation(query: String, response: String, ctx: ScreenContext) {
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                conversationDao.insert(
                    ConversationEntity(
                        appName = ctx.appName,
                        appPackage = ctx.appPackage,
                        contextType = ctx.detectedType,
                        userQuery = query,
                        aiResponse = response
                    )
                )
            }.onFailure { Timber.e(it, "Failed to save conversation") }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setSmallIcon(R.drawable.ic_bubble_notification)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            R.drawable.ic_close,
            getString(R.string.action_stop_service),
            PendingIntent.getService(
                this, 1,
                Intent(this, FloatingBubbleService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        const val CHANNEL_ID = "contextai_bubble"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.contextai.STOP"
        const val ACTION_SHOW = "com.contextai.SHOW"
        const val ACTION_HIDE = "com.contextai.HIDE"
        private const val CONTEXT_TIMEOUT_MS = 3000L
    }
}
