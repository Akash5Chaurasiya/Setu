package com.contextai.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.contextai.core.SharedContextBus
import com.contextai.core.security.AccessibilityGate
import com.contextai.core.security.SensitiveAppFilter
import com.contextai.domain.model.ScreenContext
import com.contextai.domain.model.blockedScreenContext
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class ContextAIAccessibilityService : AccessibilityService() {

    @Inject lateinit var screenContextExtractor: ScreenContextExtractor
    @Inject lateinit var accessibilityGate: AccessibilityGate

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentPackage: String = ""
    private var currentActivity: String = ""
    private var nullWindowCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        SharedContextBus.setAccessibilityEnabled(true)
        Timber.i("ContextAI Accessibility Service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        serviceScope.launch {
            SharedContextBus.contextRequest.collect {
                captureAndPublishContext()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ScreenContextExtractor.isCaptureEnabled || !accessibilityGate.isAllowedToRead()) return
        event ?: return
        if (event.source == null && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentPackage = event.packageName?.toString() ?: currentPackage
                currentActivity = event.className?.toString() ?: currentActivity
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
        Timber.w("ContextAI Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        SharedContextBus.setAccessibilityEnabled(false)
        serviceScope.cancel()
        Timber.i("ContextAI Accessibility Service destroyed")
    }

    private fun captureAndPublishContext() {
        if (!accessibilityGate.isAllowedToRead()) {
            Timber.d("AccessibilityGate: captureAndPublishContext blocked — gate closed")
            SharedContextBus.publishContext(buildFallbackContext())
            return
        }
        try {
            val rootNode: AccessibilityNodeInfo? = try {
                rootInActiveWindow
            } catch (e: SecurityException) {
                Timber.w(e, "SecurityException getting rootInActiveWindow")
                null
            } catch (e: IllegalStateException) {
                Timber.w(e, "IllegalStateException getting rootInActiveWindow")
                null
            }

            if (rootNode == null) {
                nullWindowCount++
                if (nullWindowCount >= NULL_WINDOW_THRESHOLD) {
                    nullWindowCount = 0
                    mainHandler.post {
                        Toast.makeText(
                            this,
                            "ContextAI: Accessibility may need re-enabling",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                SharedContextBus.publishContext(buildFallbackContext())
                return
            }

            nullWindowCount = 0
            val pkg = currentPackage.ifBlank { rootNode.packageName?.toString() ?: "" }
            if (SensitiveAppFilter.isBlocked(pkg)) {
                SharedContextBus.publishContext(blockedScreenContext(pkg, resolveAppName(pkg)))
                return
            }
            val appName = resolveAppName(pkg)
            val raw = screenContextExtractor.extract(rootNode, pkg, appName, currentActivity)
            val context = raw.copy(rawText = SensitiveAppFilter.sanitizeText(raw.rawText))
            SharedContextBus.publishContext(context)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to capture screen context")
            SharedContextBus.publishContext(buildFallbackContext())
        }
    }

    private fun resolveAppName(packageName: String): String {
        if (packageName.isBlank()) return "Unknown"
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            ).toString()
        }.getOrDefault(packageName.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() })
    }

    private fun buildFallbackContext() = ScreenContext(
        appPackage = currentPackage,
        appName = resolveAppName(currentPackage),
        activityTitle = currentActivity,
        rawText = "",
        detectedEmails = emptyList(),
        detectedUrls = emptyList(),
        detectedType = com.contextai.domain.model.ContextType.GENERIC,
        keyEntities = emptyMap()
    )

    companion object {
        private const val NULL_WINDOW_THRESHOLD = 3
    }
}
