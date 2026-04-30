package com.contextai.presentation.onboarding

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.contextai.core.DesignTokens
import com.contextai.core.dpToPx
import com.contextai.data.preferences.SecurePreferencesManager
import com.contextai.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin

@AndroidEntryPoint
class OnboardingActivity : AppCompatActivity() {

    @Inject lateinit var prefsManager: SecurePreferencesManager

    private lateinit var root: FrameLayout
    private lateinit var screenContainer: FrameLayout
    private var currentScreen = 0
    private val handler = Handler(Looper.getMainLooper())

    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Refresh screen 2 to update the RECORD_AUDIO card status
        if (currentScreen == 1) showScreen(1, animate = false)
    }

    // Screen 3 demo cycling
    private var demoIndex = 0
    private val demoCycleRunnable = object : Runnable {
        override fun run() {
            demoIndex = (demoIndex + 1) % 3
            updateDemoCard(demoIndex)
            handler.postDelayed(this, 2500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(DesignTokens.surfaceBaseDark))
        window.statusBarColor = DesignTokens.surfaceBaseDark
        window.navigationBarColor = DesignTokens.surfaceBaseDark

        root = FrameLayout(this)
        root.setBackgroundColor(DesignTokens.surfaceBaseDark)
        setContentView(root)

        screenContainer = FrameLayout(this)
        root.addView(screenContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        showScreen(0, animate = false)
    }

    private fun showScreen(index: Int, animate: Boolean = true) {
        handler.removeCallbacks(demoCycleRunnable)
        currentScreen = index

        val newScreen = when (index) {
            0 -> buildScreen1()
            1 -> buildScreen2()
            2 -> buildScreen3()
            else -> return
        }

        if (!animate || screenContainer.childCount == 0) {
            screenContainer.removeAllViews()
            screenContainer.addView(newScreen, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else {
            val oldScreen = screenContainer.getChildAt(0)
            newScreen.alpha = 0f
            newScreen.translationX = 80f.dpToPx(this)
            screenContainer.addView(newScreen, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            oldScreen.animate()
                .alpha(0f).translationX(-60f.dpToPx(this))
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { screenContainer.removeView(oldScreen) }
                .start()
            newScreen.animate()
                .alpha(1f).translationX(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }

    // ─── Screen 1 ────────────────────────────────────────────────────────────

    private fun buildScreen1(): View {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(DesignTokens.surfaceBaseDark)

        val animView = BubbleAnimView(this)
        frame.addView(animView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32.dpToPx(this@OnboardingActivity), 0, 32.dpToPx(this@OnboardingActivity), 48.dpToPx(this@OnboardingActivity))
        }

        val tvHeadline = TextView(this).apply {
            text = "Your AI, everywhere"
            textSize = DesignTokens.textXL
            setTextColor(DesignTokens.textPrimaryDark)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvSub = TextView(this).apply {
            text = "A floating assistant that understands what's on your screen and acts on it. Powered by Claude."
            textSize = DesignTokens.textMD
            setTextColor(DesignTokens.textSecondaryDark)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.5f)
            setPadding(0, 12.dpToPx(this@OnboardingActivity), 0, 32.dpToPx(this@OnboardingActivity))
        }

        val dotsRow = buildDotsRow(0)

        val btnNext = buildPrimaryButton("Get started") { showScreen(1) }

        bottomPanel.addView(tvHeadline)
        bottomPanel.addView(tvSub)
        bottomPanel.addView(dotsRow)
        bottomPanel.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 16.dpToPx(this@OnboardingActivity)) })
        bottomPanel.addView(btnNext, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 52.dpToPx(this)
        ))

        frame.addView(bottomPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        return frame
    }

    // ─── Screen 2 ────────────────────────────────────────────────────────────

    private fun buildScreen2(): View {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(DesignTokens.surfaceBaseDark)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20.dpToPx(this@OnboardingActivity), 64.dpToPx(this@OnboardingActivity),
                20.dpToPx(this@OnboardingActivity), 120.dpToPx(this@OnboardingActivity))
        }

        val tvTitle = TextView(this).apply {
            text = "Three quick permissions"
            textSize = DesignTokens.textXL
            setTextColor(DesignTokens.textPrimaryDark)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvSub = TextView(this).apply {
            text = "These let ContextAI show its bubble, read the screen, and accept voice input."
            textSize = DesignTokens.textMD
            setTextColor(DesignTokens.textSecondaryDark)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.5f)
            setPadding(0, 10.dpToPx(this@OnboardingActivity), 0, 28.dpToPx(this@OnboardingActivity))
        }

        val overlayGranted = Settings.canDrawOverlays(this)
        val accessGranted = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK != 0
            && isAccessibilityEnabled()
        val recordAudioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val cardOverlay = buildPermissionCard(
            title = "Overlay — Draw over apps",
            desc = "Shows the floating bubble on top of other apps.",
            granted = overlayGranted,
            buttonText = "Grant in Settings"
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        val cardAccessibility = buildPermissionCard(
            title = "Accessibility — Read screen",
            desc = "Reads screen text only when you explicitly tap the bubble. Zero passive collection.",
            granted = accessGranted,
            buttonText = "Enable in Settings"
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        val cardRecordAudio = buildPermissionCard(
            title = "Microphone — Voice input",
            desc = "Hold the mic button to speak your query instead of typing. Used only on-demand.",
            granted = recordAudioGranted,
            buttonText = "Allow Microphone"
        ) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        val tvSafe = buildSafetyNote()
        val dotsRow = buildDotsRow(1)

        content.addView(tvTitle)
        content.addView(tvSub)
        content.addView(cardOverlay)
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12.dpToPx(this@OnboardingActivity)) })
        content.addView(cardAccessibility)
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12.dpToPx(this@OnboardingActivity)) })
        content.addView(cardRecordAudio)
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 16.dpToPx(this@OnboardingActivity)) })
        content.addView(tvSafe)
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24.dpToPx(this@OnboardingActivity)) })
        content.addView(dotsRow)
        scroll.addView(content)
        frame.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(this@OnboardingActivity), 12.dpToPx(this@OnboardingActivity),
                20.dpToPx(this@OnboardingActivity), 32.dpToPx(this@OnboardingActivity))
            setBackgroundColor(DesignTokens.surfaceBaseDark)
        }

        val btnContinue = buildPrimaryButton("Continue") { showScreen(2) }
        bottomBar.addView(btnContinue, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 52.dpToPx(this)
        ))

        frame.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        return frame
    }

    private fun buildPermissionCard(
        title: String,
        desc: String,
        granted: Boolean,
        buttonText: String,
        onGrant: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(this@OnboardingActivity), 16.dpToPx(this@OnboardingActivity),
                16.dpToPx(this@OnboardingActivity), 16.dpToPx(this@OnboardingActivity))
            background = buildCardBackground()
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            text = title
            textSize = DesignTokens.textMD
            setTextColor(DesignTokens.textPrimaryDark)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val statusBadge = TextView(this).apply {
            text = if (granted) "Granted" else "Required"
            textSize = DesignTokens.textXS
            setTextColor(if (granted) DesignTokens.success else DesignTokens.warning)
            setPadding(8.dpToPx(this@OnboardingActivity), 4.dpToPx(this@OnboardingActivity),
                8.dpToPx(this@OnboardingActivity), 4.dpToPx(this@OnboardingActivity))
            background = buildBadgeBackground(if (granted) DesignTokens.success else DesignTokens.warning)
        }

        headerRow.addView(tvTitle)
        headerRow.addView(statusBadge)

        val tvDesc = TextView(this).apply {
            text = desc
            textSize = DesignTokens.textSM
            setTextColor(DesignTokens.textSecondaryDark)
            setLineSpacing(0f, 1.4f)
            setPadding(0, 8.dpToPx(this@OnboardingActivity), 0, if (granted) 0 else 12.dpToPx(this@OnboardingActivity))
        }

        card.addView(headerRow)
        card.addView(tvDesc)

        if (!granted) {
            val btnGrant = TextView(this).apply {
                text = buttonText
                textSize = DesignTokens.textSM
                setTextColor(DesignTokens.brandPrimary)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 10.dpToPx(this@OnboardingActivity), 0, 4.dpToPx(this@OnboardingActivity))
                isClickable = true
                isFocusable = true
                setOnClickListener { onGrant() }
            }
            card.addView(btnGrant)
        }

        return card
    }

    private fun buildSafetyNote(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dpToPx(this@OnboardingActivity), 10.dpToPx(this@OnboardingActivity),
                12.dpToPx(this@OnboardingActivity), 10.dpToPx(this@OnboardingActivity))
            background = buildBadgeBackground(DesignTokens.brandPrimary)
        }

        val tvLock = TextView(this).apply {
            text = "🔒"
            textSize = DesignTokens.textMD
            setPadding(0, 0, 8.dpToPx(this@OnboardingActivity), 0)
        }

        val tvNote = TextView(this).apply {
            text = "Screen content is only read when you tap the bubble. Nothing is collected passively or stored on our servers."
            textSize = DesignTokens.textSM
            setTextColor(DesignTokens.textSecondaryDark)
            setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(tvLock)
        row.addView(tvNote)
        return row
    }

    // ─── Screen 3 ────────────────────────────────────────────────────────────

    private val demoData = listOf(
        Triple("LinkedIn", "linkedin.com · Job Post", "Summarize this JD and check my fit"),
        Triple("Swiggy", "swiggy.com · Food Order", "Calculate calories in this order"),
        Triple("MakeMyTrip", "makemytrip.com · Flight", "What's the best option for a day trip?")
    )

    private val demoColors = listOf(
        Color.parseColor("#0A66C2"),
        Color.parseColor("#FC8019"),
        Color.parseColor("#1A5276")
    )

    private lateinit var demoCard: LinearLayout
    private lateinit var tvDemoApp: TextView
    private lateinit var tvDemoContext: TextView
    private lateinit var tvDemoQuery: TextView

    private fun buildScreen3(): View {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(DesignTokens.surfaceBaseDark)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20.dpToPx(this@OnboardingActivity), 72.dpToPx(this@OnboardingActivity),
                20.dpToPx(this@OnboardingActivity), 0)
        }

        val tvTitle = TextView(this).apply {
            text = "Here's what you can do"
            textSize = DesignTokens.textXL
            setTextColor(DesignTokens.textPrimaryDark)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val tvSub = TextView(this).apply {
            text = "Open any app, tap the bubble, get instant help."
            textSize = DesignTokens.textMD
            setTextColor(DesignTokens.textSecondaryDark)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.5f)
            setPadding(0, 10.dpToPx(this@OnboardingActivity), 0, 32.dpToPx(this@OnboardingActivity))
        }

        demoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(this@OnboardingActivity), 20.dpToPx(this@OnboardingActivity),
                20.dpToPx(this@OnboardingActivity), 20.dpToPx(this@OnboardingActivity))
            background = buildCardBackground()
        }

        val appRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val appDot = View(this).apply {
            background = buildCircle(demoColors[0])
            layoutParams = LinearLayout.LayoutParams(10.dpToPx(this@OnboardingActivity), 10.dpToPx(this@OnboardingActivity)).also {
                it.marginEnd = 8.dpToPx(this@OnboardingActivity)
            }
        }

        tvDemoApp = TextView(this).apply {
            text = demoData[0].first
            textSize = DesignTokens.textSM
            setTextColor(demoColors[0])
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        tvDemoContext = TextView(this).apply {
            text = demoData[0].second
            textSize = DesignTokens.textSM
            setTextColor(DesignTokens.textSecondaryDark)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = 6.dpToPx(this@OnboardingActivity)
            }
        }

        appRow.addView(appDot)
        appRow.addView(tvDemoApp)
        appRow.addView(tvDemoContext)

        tvDemoQuery = TextView(this).apply {
            text = "\"${demoData[0].third}\""
            textSize = DesignTokens.textMD
            setTextColor(DesignTokens.textPrimaryDark)
            setLineSpacing(0f, 1.4f)
            setPadding(0, 12.dpToPx(this@OnboardingActivity), 0, 0)
        }

        demoCard.addView(appRow)
        demoCard.addView(tvDemoQuery)

        val dotsRow = buildDotsRow(2)

        content.addView(tvTitle)
        content.addView(tvSub)
        content.addView(demoCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24.dpToPx(this@OnboardingActivity)) })
        content.addView(dotsRow)

        frame.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(this@OnboardingActivity), 12.dpToPx(this@OnboardingActivity),
                20.dpToPx(this@OnboardingActivity), 40.dpToPx(this@OnboardingActivity))
        }

        val btnStart = buildPrimaryButton("Start using ContextAI") {
            handler.removeCallbacks(demoCycleRunnable)
            completeOnboarding()
        }
        bottomBar.addView(btnStart, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 52.dpToPx(this)
        ))

        frame.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        handler.postDelayed(demoCycleRunnable, 2500)
        return frame
    }

    private fun updateDemoCard(index: Int) {
        val (app, context, query) = demoData[index]
        val color = demoColors[index]

        demoCard.animate().alpha(0f).setDuration(150).withEndAction {
            tvDemoApp.text = app
            tvDemoApp.setTextColor(color)
            tvDemoContext.text = context
            tvDemoQuery.text = "\"$query\""
            demoCard.animate().alpha(1f).setDuration(200).start()
        }.start()
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────

    private fun buildDotsRow(active: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (i in 0..2) {
            val dot = View(this).apply {
                val isActive = i == active
                val size = if (isActive) 8.dpToPx(this@OnboardingActivity) else 6.dpToPx(this@OnboardingActivity)
                val params = LinearLayout.LayoutParams(size, size).also { it.marginEnd = 6.dpToPx(this@OnboardingActivity) }
                layoutParams = params
                background = buildCircle(
                    if (isActive) DesignTokens.brandPrimary
                    else ColorUtils.setAlphaComponent(DesignTokens.textSecondaryDark, 80)
                )
            }
            row.addView(dot)
        }
        return row
    }

    private fun buildPrimaryButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = DesignTokens.textMD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            isClickable = true
            isFocusable = true
            background = buildPillBackground(DesignTokens.brandPrimary)
            setOnClickListener {
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
                onClick()
            }
        }
    }

    private fun buildCardBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = DesignTokens.radiusMD.dpToPx(this@OnboardingActivity)
            setColor(DesignTokens.surfaceRaisedDark)
        }
    }

    private fun buildPillBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = DesignTokens.radiusPill.dpToPx(this@OnboardingActivity)
            setColor(color)
        }
    }

    private fun buildBadgeBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = DesignTokens.radiusSM.dpToPx(this@OnboardingActivity)
            setColor(ColorUtils.setAlphaComponent(color, 30))
        }
    }

    private fun buildCircle(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
            if (enabled == 1) {
                val services = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                services.contains(packageName)
            } else false
        } catch (e: Settings.SettingNotFoundException) {
            false
        }
    }

    private fun completeOnboarding() {
        prefsManager.setOnboardingComplete(true)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onResume() {
        super.onResume()
        if (currentScreen == 1) {
            showScreen(1, animate = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

// ─── Bubble canvas animation for Screen 1 ────────────────────────────────────

private class BubbleAnimView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DesignTokens.surfaceBaseDark
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(DesignTokens.brandPrimary, 40)
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DesignTokens.brandPrimary
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = ColorUtils.setAlphaComponent(DesignTokens.brandPrimary, 60)
    }

    private val appPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DesignTokens.surfaceRaisedDark
    }

    private val appLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DesignTokens.textSecondaryDark
        textAlign = Paint.Align.CENTER
    }

    private val appColors = listOf(
        Color.parseColor("#0A66C2"),
        Color.parseColor("#FC8019"),
        Color.parseColor("#25D366"),
        Color.parseColor("#EA4335")
    )

    private val appLabels = listOf("in", "🍔", "💬", "✉")

    private var bubbleX = 0f
    private var bubbleY = 0f
    private var progress = 0f
    private val trailPoints = ArrayDeque<PointF>()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = null
        addUpdateListener {
            progress = it.animatedFraction
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // App icon grid in the upper 60% of the view
        val gridTop = h * 0.08f
        val gridBottom = h * 0.62f
        val gridCx = w / 2f
        val appRadius = minOf(w, h) * 0.09f
        val appSpacingX = w * 0.28f
        val appSpacingY = (gridBottom - gridTop) / 2f

        val appPositions = listOf(
            PointF(gridCx - appSpacingX, gridTop + appSpacingY * 0.4f),
            PointF(gridCx + appSpacingX, gridTop + appSpacingY * 0.4f),
            PointF(gridCx - appSpacingX, gridTop + appSpacingY * 1.6f),
            PointF(gridCx + appSpacingX, gridTop + appSpacingY * 1.6f)
        )

        appLabelPaint.textSize = appRadius * 0.7f

        appPositions.forEachIndexed { i, pt ->
            appPaint.color = appColors[i]
            canvas.drawCircle(pt.x, pt.y, appRadius, appPaint)
            canvas.drawText(appLabels[i], pt.x, pt.y + appRadius * 0.3f, appLabelPaint)
        }

        // Bezier path over the app icons
        val pathProgress = (progress * 1.0f).coerceIn(0f, 1f)
        val t = pathProgress
        // Cubic bezier: start→control1→control2→end, looping over all 4 apps
        val pts = appPositions
        val segCount = pts.size
        val segIndex = (t * segCount).toInt().coerceIn(0, segCount - 1)
        val segT = (t * segCount) - segIndex

        val p0 = pts[segIndex]
        val p1 = pts[(segIndex + 1) % segCount]
        val cx = w / 2f
        val cy = (p0.y + p1.y) / 2f - 40f
        val bx = p0.x + (p1.x - p0.x) * segT + (cx - (p0.x + p1.x) / 2f) * sin(segT * Math.PI.toFloat()) * 0.5f
        val by = p0.y + (p1.y - p0.y) * segT + (cy - (p0.y + p1.y) / 2f) * sin(segT * Math.PI.toFloat()) * 0.5f

        bubbleX = bx
        bubbleY = by

        if (trailPoints.isEmpty() || trailPoints.last().let { kotlin.math.hypot(it.x - bx, it.y - by) } > 8f) {
            trailPoints.addLast(PointF(bx, by))
            if (trailPoints.size > 12) trailPoints.removeFirst()
        }

        // Draw trail
        if (trailPoints.size > 1) {
            val path = Path()
            path.moveTo(trailPoints.first().x, trailPoints.first().y)
            for (pt in trailPoints.drop(1)) path.lineTo(pt.x, pt.y)
            canvas.drawPath(path, trailPaint)
        }

        // Glow
        val bubbleR = minOf(w, h) * 0.065f
        canvas.drawCircle(bx, by, bubbleR * 2f, glowPaint)

        // Bubble circle
        canvas.drawCircle(bx, by, bubbleR, circlePaint)

        // 4-pointed star icon inside bubble
        drawStar(canvas, bx, by, bubbleR * 0.48f, bubbleR * 0.22f)
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, outer: Float, inner: Float) {
        val path = Path()
        for (i in 0 until 8) {
            val angle = (Math.PI / 4 * i - Math.PI / 2).toFloat()
            val r = if (i % 2 == 0) outer else inner
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, iconPaint)
    }
}

