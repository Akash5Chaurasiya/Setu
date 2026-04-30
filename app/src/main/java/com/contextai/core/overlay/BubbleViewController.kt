package com.contextai.core.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.PixelFormat
import com.contextai.core.DesignTokens
import com.contextai.core.HapticEngine
import com.contextai.core.dpToPx
import com.contextai.core.security.AccessibilityGate
import com.contextai.core.isDarkMode
import com.contextai.core.surfaceBorderColor
import com.contextai.core.surfaceColor
import com.contextai.core.surfaceOverlayColor
import com.contextai.core.textSecondaryColor
import timber.log.Timber
import java.lang.ref.WeakReference
import kotlin.math.*

class BubbleViewController(
    context: Context,
    private val windowManager: WindowManager,
    private val onSingleTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onRadialAction: ((String) -> Unit)? = null,
    private val onClipBadgeTap: ((String, ClipboardWatcherService.ClipType, String) -> Unit)? = null,
    private val accessibilityGate: AccessibilityGate? = null
) {
    private val contextRef = WeakReference(context)
    private var bubbleView: BubbleView? = null
    private var radialMenuView: View? = null
    private var clipBadgeView: View? = null
    private var trashZoneView: View? = null
    private var restoreBannerView: View? = null
    private var clipBadgeParams: WindowManager.LayoutParams? = null
    private var trashZoneParams: WindowManager.LayoutParams? = null
    private var restoreBannerParams: WindowManager.LayoutParams? = null
    private var params: WindowManager.LayoutParams? = null
    private var isAdded = false
    var isBubbleHidden = false
        private set
    private val mainHandler = Handler(Looper.getMainLooper())

    // Trash zone state
    private var trashZoneVisible = false
    private var nearTrashZone = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var velocityTracker: VelocityTracker? = null

    private val screenWidth: Int get() = contextRef.get()?.resources?.displayMetrics?.widthPixels ?: 0
    private val screenHeight: Int get() = contextRef.get()?.resources?.displayMetrics?.heightPixels ?: 0

    private val gestureDetector: GestureDetector? by lazy {
        val ctx = contextRef.get() ?: return@lazy null
        GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isDragging) {
                    bubbleView?.animateTapRelease()
                    mainHandler.postDelayed({ onSingleTap() }, 120)
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (!isDragging) {
                    vibrate()
                    showRadialMenu()
                    onLongPress()
                }
            }
        })
    }

    fun show() {
        val ctx = contextRef.get() ?: return
        if (isAdded) return

        val bv = BubbleView(ctx)
        bubbleView = bv

        val sizePx = BUBBLE_SIZE_DP.dpToPx(ctx)
        params = WindowManager.LayoutParams(
            sizePx + 16.dpToPx(ctx),
            sizePx + 16.dpToPx(ctx),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - sizePx - BUBBLE_MARGIN_DP.dpToPx(ctx)
            y = screenHeight / 3
        }

        bv.setOnTouchListener { _, event ->
            handleTouch(event)
            gestureDetector?.onTouchEvent(event)
            true
        }

        runCatching {
            windowManager.addView(bv, params)
            isAdded = true
            bv.animateIn()
        }.onFailure { Timber.e(it, "Failed to add bubble view") }
    }

    fun hide() {
        mainHandler.removeCallbacksAndMessages(null)
        radialMenuView?.let { runCatching { windowManager.removeView(it) } }
        radialMenuView = null
        if (!isAdded) return
        runCatching { windowManager.removeView(bubbleView) }
            .onFailure { Timber.w(it, "Failed to remove bubble") }
        isAdded = false
    }

    fun showProcessing(isProcessing: Boolean) {
        if (isProcessing) bubbleView?.setState(BubbleView.BubbleState.PROCESSING)
        else bubbleView?.setState(BubbleView.BubbleState.IDLE)
    }

    fun signalDone() {
        bubbleView?.setState(BubbleView.BubbleState.DONE)
        mainHandler.postDelayed({ bubbleView?.setState(BubbleView.BubbleState.IDLE) }, 1500)
    }

    fun startGateCountdown() {
        val gate = accessibilityGate ?: return
        val runnable = object : Runnable {
            override fun run() {
                if (gate.isOpen()) {
                    bubbleView?.setGateState(true, gate.remainingFraction())
                    mainHandler.postDelayed(this, 250)
                } else {
                    bubbleView?.setGateState(false, 0f)
                }
            }
        }
        mainHandler.post(runnable)
    }

    private fun handleTouch(event: MotionEvent) {
        val ctx = contextRef.get() ?: return
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                initialX = params!!.x
                initialY = params!!.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                velocityTracker = VelocityTracker.obtain()
                velocityTracker!!.addMovement(event)
                bubbleView?.animateTouchDown()
                bubbleView?.clearTrail()
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) {
                    isDragging = true
                    bubbleView?.setDragging(true)
                }
                if (isDragging) {
                    val newX = (initialX + dx).toInt()
                    val newY = (initialY + dy).toInt().coerceIn(0, screenHeight - BUBBLE_SIZE_DP.dpToPx(ctx))
                    params!!.x = newX
                    params!!.y = newY
                    runCatching { windowManager.updateViewLayout(bubbleView, params) }
                        .onFailure { Timber.w(it, "updateViewLayout") }
                    bubbleView?.addTrailPoint(PointF(event.rawX, event.rawY))
                    if (!trashZoneVisible) showTrashZone()
                    val trashCx = screenWidth / 2f
                    val trashCy = screenHeight - TRASH_ZONE_BOTTOM_MARGIN.dpToPx(ctx).toFloat()
                    val bubbleCx = event.rawX
                    val bubbleCy = event.rawY
                    val dist = sqrt((bubbleCx - trashCx).pow(2) + (bubbleCy - trashCy).pow(2))
                    val newNear = dist < TRASH_SNAP_DISTANCE.dpToPx(ctx)
                    if (newNear != nearTrashZone) {
                        nearTrashZone = newNear
                        updateTrashZoneState(newNear)
                        if (newNear) bubbleView?.setRedTint(true)
                        else bubbleView?.setRedTint(false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    hideTrashZone()
                    bubbleView?.setRedTint(false)
                    if (nearTrashZone) {
                        nearTrashZone = false
                        temporarilyHide()
                    } else {
                        nearTrashZone = false
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vx = velocityTracker?.xVelocity ?: 0f
                        snapToEdge(vx)
                        bubbleView?.setDragging(false)
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                isDragging = false
            }
        }
    }

    private fun snapToEdge(velocityX: Float) {
        val ctx = contextRef.get() ?: return
        val sizePx = BUBBLE_SIZE_DP.dpToPx(ctx)
        val margin = BUBBLE_MARGIN_DP.dpToPx(ctx)
        val snapRight = if (abs(velocityX) > FLING_THRESHOLD) velocityX > 0 else params!!.x > screenWidth / 2
        val targetX = if (snapRight) screenWidth - sizePx - margin else margin
        val fromX = params!!.x

        ValueAnimator.ofInt(fromX, targetX + if (snapRight) 8.dpToPx(ctx) else -8.dpToPx(ctx)).apply {
            duration = 280
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                params!!.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(bubbleView, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ValueAnimator.ofInt(params!!.x, targetX).apply {
                        duration = 180
                        interpolator = OvershootInterpolator(2f)
                        addUpdateListener {
                            params!!.x = it.animatedValue as Int
                            runCatching { windowManager.updateViewLayout(bubbleView, params) }
                        }
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun showRadialMenu() {
        val ctx = contextRef.get() ?: return
        val p = params ?: return
        if (radialMenuView != null) return

        val menuItems = listOf(
            Triple("📋", "History", RadialMenuView.RadialItem("History", "H")),
            Triple("⚙️", "Settings", RadialMenuView.RadialItem("Settings", "S")),
            Triple("🔍", "What is this?", RadialMenuView.RadialItem("What is this?", "?")),
            Triple("✕", "Close", RadialMenuView.RadialItem("Close", "X"))
        )

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surfaceColor(ctx))
                cornerRadius = 16.dpToPx(ctx).toFloat()
                setStroke(1.dpToPx(ctx), surfaceBorderColor(ctx))
            }
            elevation = 24f * ctx.resources.displayMetrics.density
            setPadding(0, 6.dpToPx(ctx), 0, 6.dpToPx(ctx))
        }

        menuItems.forEachIndexed { i, (emoji, label, item) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 48.dpToPx(ctx)
                setPadding(16.dpToPx(ctx), 0, 24.dpToPx(ctx), 0)
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT) }
                setOnClickListener { onRadialItem(item) }
            }
            val emojiTv = TextView(ctx).apply {
                text = emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                layoutParams = LinearLayout.LayoutParams(28.dpToPx(ctx), LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
            }
            val labelTv = TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textSecondaryColor(ctx))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 10.dpToPx(ctx) }
            }
            row.addView(emojiTv)
            row.addView(labelTv)
            card.addView(row)

            if (i < menuItems.size - 1) {
                card.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { setMargins(16.dpToPx(ctx), 0, 16.dpToPx(ctx), 0) }
                    setBackgroundColor(surfaceBorderColor(ctx))
                })
            }
        }
        radialMenuView = card

        val onRight = p.x > screenWidth / 2
        val menuW = 170.dpToPx(ctx)
        val menuX = if (onRight) {
            (p.x - menuW - 8.dpToPx(ctx)).coerceAtLeast(4.dpToPx(ctx))
        } else {
            p.x + BUBBLE_SIZE_DP.dpToPx(ctx) + 8.dpToPx(ctx)
        }
        val menuY = (p.y - 60.dpToPx(ctx)).coerceIn(8.dpToPx(ctx), screenHeight - 240.dpToPx(ctx))

        val rmParams = WindowManager.LayoutParams(
            menuW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = menuX
            y = menuY
        }
        card.alpha = 0f
        card.scaleX = 0.85f; card.scaleY = 0.85f
        runCatching {
            windowManager.addView(card, rmParams)
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(OvershootInterpolator(1.5f)).start()
        }
        mainHandler.postDelayed({ dismissRadialMenu() }, 4000)
    }

    private fun dismissRadialMenu() {
        val v = radialMenuView ?: return
        radialMenuView = null
        v.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { runCatching { windowManager.removeView(v) } }
            .start()
    }

    private fun onRadialItem(item: RadialMenuView.RadialItem) {
        dismissRadialMenu()
        onRadialAction?.invoke(item.label)
    }

    private fun vibrate() {
        val ctx = contextRef.get() ?: return
        HapticEngine.bubbleTap(ctx)
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        bubbleView?.cancelAll()
        hideTrashZone()
        dismissClipBadge()
        dismissRestoreBanner()
        hide()
        bubbleView = null
    }

    // ── Enhancement 1: Clip badge ─────────────────────────────────────────────

    private var pendingClipType: ClipboardWatcherService.ClipType = ClipboardWatcherService.ClipType.SHORT_TEXT
    private var pendingClipContent: String = ""

    fun showClipBadge(label: String, type: ClipboardWatcherService.ClipType, content: String) {
        val ctx = contextRef.get() ?: return
        val p = params ?: return
        dismissClipBadge()
        pendingClipType = type
        pendingClipContent = content
        HapticEngine.clipDetected(ctx)

        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(surfaceColor(ctx))
                cornerRadius = DesignTokens.radiusPill.dpToPx(ctx)
                setStroke(1, surfaceBorderColor(ctx))
            }
            elevation = 8f * ctx.resources.displayMetrics.density
            setPadding(12.dpToPx(ctx), 0, 4.dpToPx(ctx), 0)
        }

        val labelTv = TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textSecondaryColor(ctx))
            maxWidth = 200.dpToPx(ctx)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val dismissTv = TextView(ctx).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(DesignTokens.textTertiary)
            setPadding(8.dpToPx(ctx), 0, 8.dpToPx(ctx), 0)
            setOnClickListener { dismissClipBadge() }
        }

        pill.addView(labelTv)
        pill.addView(dismissTv)
        pill.setOnClickListener {
            dismissClipBadge()
            onClipBadgeTap?.invoke(label, pendingClipType, pendingClipContent)
        }
        clipBadgeView = pill

        val badgeH = 32.dpToPx(ctx)
        val badgeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            badgeH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = p.x + BUBBLE_SIZE_DP.dpToPx(ctx)
            y = p.y + (BUBBLE_SIZE_DP.dpToPx(ctx) / 4)
        }
        clipBadgeParams = badgeParams

        runCatching {
            windowManager.addView(pill, badgeParams)
            pill.post {
                val targetX = p.x - pill.width - 8.dpToPx(ctx)
                ValueAnimator.ofInt(badgeParams.x, targetX).apply {
                    duration = 280
                    interpolator = DecelerateInterpolator(2f)
                    addUpdateListener {
                        badgeParams.x = it.animatedValue as Int
                        runCatching { windowManager.updateViewLayout(pill, badgeParams) }
                    }
                    start()
                }
                pill.alpha = 0f
                pill.animate().alpha(1f).setDuration(200).start()
            }
        }

        mainHandler.postDelayed({ dismissClipBadge() }, 4000)
    }

    fun dismissClipBadge() {
        val badge = clipBadgeView ?: return
        badge.animate().alpha(0f).setDuration(180).withEndAction {
            runCatching { windowManager.removeView(badge) }
        }.start()
        clipBadgeView = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ── Enhancement 6: Trash zone & temporary hide ────────────────────────────

    private fun showTrashZone() {
        val ctx = contextRef.get() ?: return
        if (trashZoneVisible) return
        trashZoneVisible = true

        val size = TRASH_ZONE_SIZE_DP.dpToPx(ctx)
        val trashView = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(38, 239, 68, 68))
            }
        }
        val icon = TextView(ctx).apply {
            text = "🗑"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        trashView.addView(icon)
        trashZoneView = trashView

        val tzParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 2 - size / 2
            y = screenHeight - TRASH_ZONE_BOTTOM_MARGIN.dpToPx(ctx) - size / 2
        }
        trashZoneParams = tzParams

        runCatching {
            windowManager.addView(trashView, tzParams)
            trashView.alpha = 0f
            trashView.scaleX = 0.6f; trashView.scaleY = 0.6f
            trashView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }
    }

    private fun hideTrashZone() {
        val tz = trashZoneView ?: return
        trashZoneVisible = false
        tz.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(180).withEndAction {
            runCatching { windowManager.removeView(tz) }
        }.start()
        trashZoneView = null
    }

    private fun updateTrashZoneState(near: Boolean) {
        val ctx = contextRef.get() ?: return
        val tz = trashZoneView ?: return
        val tzP = trashZoneParams ?: return
        val expandedSize = if (near) TRASH_ZONE_EXPANDED_DP.dpToPx(ctx) else TRASH_ZONE_SIZE_DP.dpToPx(ctx)
        tzP.width = expandedSize; tzP.height = expandedSize
        tzP.x = screenWidth / 2 - expandedSize / 2
        tzP.y = screenHeight - TRASH_ZONE_BOTTOM_MARGIN.dpToPx(ctx) - expandedSize / 2
        runCatching { windowManager.updateViewLayout(tz, tzP) }
        val bgAlpha = if (near) 77 else 38
        (tz.background as? GradientDrawable)?.setColor(
            android.graphics.Color.argb(bgAlpha, 239, 68, 68)
        )
    }

    fun temporarilyHide() {
        isBubbleHidden = true
        val ctx = contextRef.get() ?: return
        HapticEngine.dismiss(ctx)
        bubbleView?.animate()
            ?.scaleX(0f)?.scaleY(0f)?.alpha(0f)
            ?.setDuration(250)
            ?.withEndAction { bubbleView?.visibility = View.GONE }
            ?.start()
        showRestoreBanner()
    }

    fun restore() {
        val ctx = contextRef.get() ?: return
        isBubbleHidden = false
        dismissRestoreBanner()
        bubbleView?.visibility = View.VISIBLE
        bubbleView?.animate()
            ?.scaleX(1f)?.scaleY(1f)?.alpha(0.92f)
            ?.setDuration(320)
            ?.setInterpolator(OvershootInterpolator(1.5f))
            ?.start()
    }

    private fun showRestoreBanner() {
        val ctx = contextRef.get() ?: return
        dismissRestoreBanner()

        val banner = TextView(ctx).apply {
            text = "Tap to restore bubble"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(DesignTokens.brandPrimary)
                cornerRadius = DesignTokens.radiusPill.dpToPx(ctx)
            }
            setPadding(16.dpToPx(ctx), 10.dpToPx(ctx), 16.dpToPx(ctx), 10.dpToPx(ctx))
            setOnClickListener { restore() }
        }
        restoreBannerView = banner

        val bannerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 72.dpToPx(ctx)
        }
        restoreBannerParams = bannerParams

        runCatching {
            windowManager.addView(banner, bannerParams)
            banner.alpha = 0f
            banner.translationY = -16.dpToPx(ctx).toFloat()
            banner.animate().alpha(1f).translationY(0f).setDuration(300)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
        mainHandler.postDelayed({ dismissRestoreBanner() }, 8000)
    }

    private fun dismissRestoreBanner() {
        val b = restoreBannerView ?: return
        b.animate().alpha(0f).setDuration(200).withEndAction {
            runCatching { windowManager.removeView(b) }
        }.start()
        restoreBannerView = null
    }

    companion object {
        private const val BUBBLE_SIZE_DP = 48
        private const val BUBBLE_MARGIN_DP = 12
        private const val DRAG_THRESHOLD = 12f
        private const val FLING_THRESHOLD = 800f
        private const val TRASH_ZONE_SIZE_DP = 72
        private const val TRASH_ZONE_EXPANDED_DP = 88
        private const val TRASH_ZONE_BOTTOM_MARGIN = 80
        private const val TRASH_SNAP_DISTANCE = 60
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom drawn bubble view
// ─────────────────────────────────────────────────────────────────────────────
class BubbleView(context: Context) : View(context) {

    enum class BubbleState { IDLE, TOUCH, PROCESSING, DONE }

    private var state = BubbleState.IDLE

    // Pre-allocated Paints (never allocate in onDraw)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DesignTokens.brandSecondary
        alpha = (0.18f * 255).toInt()
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DesignTokens.brandPrimary
    }
    private val successPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DesignTokens.success
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 0f // set in onSizeChanged
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DesignTokens.brandSecondary
    }
    private val gateArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(180, 108, 99, 255) // brandPrimary 70% alpha
        strokeCap = Paint.Cap.ROUND
    }
    private val gateArcRect = RectF()
    private val landingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f
        color = DesignTokens.brandPrimary
        alpha = (0.30f * 255).toInt()
    }

    // Paths — allocated once
    private val starPath = Path()
    private val checkPath = Path()
    private val glowRectF = RectF()
    private val circleRectF = RectF()

    // State
    private var starRotation = 0f
    private var glowAlpha = 0.18f
    private var currentScale = 1f
    private var currentAlpha = ALPHA_ACTIVE
    private var isDone = false
    private var isDragging = false
    private var showLandingLine = false
    private var circleColor = DesignTokens.brandPrimary

    // Ghost trail
    private val trail = ArrayDeque<PointF>()
    private val MAX_TRAIL = 5

    // Gate countdown arc
    private var gateActive = false
    private var gateFraction = 0f

    // Animators (kept as fields to cancel properly)
    private var breathingAnimator: ValueAnimator? = null
    private var glowPulseAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null
    private var idleFadeAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { startIdleFade() }
    private val bubbleHandler = Handler(Looper.getMainLooper())

    // Sizes — set in onSizeChanged
    private var cx = 0f
    private var cy = 0f
    private var bubbleRadius = 0f
    private var glowRadius = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        startBreathingAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        bubbleRadius = w * 0.38f
        glowRadius = w * 0.46f
        checkPaint.strokeWidth = bubbleRadius * 0.18f
        landingLinePaint.strokeWidth = context.resources.displayMetrics.density
        gateArcPaint.strokeWidth = 2.5f * context.resources.displayMetrics.density
        buildStarPath()
        buildCheckPath()
        circleRectF.set(cx - bubbleRadius, cy - bubbleRadius, cx + bubbleRadius, cy + bubbleRadius)
        glowRectF.set(cx - glowRadius, cy - glowRadius, cx + glowRadius, cy + glowRadius)
        val arcInset = gateArcPaint.strokeWidth
        gateArcRect.set(arcInset, arcInset, w - arcInset, h - arcInset)
    }

    private fun buildStarPath() {
        starPath.reset()
        val outerR = bubbleRadius * 0.48f
        val innerR = bubbleRadius * 0.22f
        val numPoints = 4
        for (i in 0 until numPoints * 2) {
            val radius = if (i % 2 == 0) outerR else innerR
            val angle = (i * Math.PI / numPoints) - Math.PI / 4
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
    }

    private fun buildCheckPath() {
        val r = bubbleRadius * 0.40f
        checkPath.reset()
        checkPath.moveTo(cx - r * 0.6f, cy)
        checkPath.lineTo(cx - r * 0.1f, cy + r * 0.5f)
        checkPath.lineTo(cx + r * 0.6f, cy - r * 0.4f)
    }

    override fun onDraw(canvas: Canvas) {
        // Ghost trail
        trail.forEachIndexed { i, pt ->
            val trailAlpha = ((i + 1).toFloat() / MAX_TRAIL * 0.25f * 255).toInt()
            trailPaint.alpha = trailAlpha
            val trailR = bubbleRadius * (0.3f + i * 0.06f)
            canvas.drawCircle(pt.x, pt.y, trailR, trailPaint)
        }

        // Glow ring
        val gAlpha = (glowAlpha * 255).toInt().coerceIn(0, 255)
        glowPaint.alpha = gAlpha
        val glowColor = if (context.isDarkMode()) DesignTokens.brandSecondary else DesignTokens.brandSecondary
        glowPaint.color = glowColor
        glowPaint.alpha = gAlpha
        canvas.drawCircle(cx, cy, glowRadius * currentScale, glowPaint)

        // Main circle
        circlePaint.color = circleColor
        canvas.drawCircle(cx, cy, bubbleRadius * currentScale, circlePaint)

        // Icon
        if (isDone) {
            canvas.drawPath(checkPath, checkPaint)
        } else {
            canvas.save()
            canvas.rotate(starRotation, cx, cy)
            canvas.drawPath(starPath, iconPaint)
            canvas.restore()
        }

        // Gate countdown arc — depletes clockwise over 30 seconds when gate is open
        if (gateActive && gateFraction > 0f) {
            canvas.drawArc(gateArcRect, -90f, 360f * gateFraction, false, gateArcPaint)
        }
    }

    fun setGateState(active: Boolean, fraction: Float) {
        gateActive = active
        gateFraction = fraction
        invalidate()
    }

    fun setState(newState: BubbleState) {
        if (state == newState) return
        state = newState
        cancelStateAnimators()
        idleHandler.removeCallbacks(idleRunnable)
        when (newState) {
            BubbleState.IDLE -> {
                isDone = false
                currentAlpha = ALPHA_ACTIVE
                alpha = currentAlpha
                startBreathingAnimation()
                idleHandler.postDelayed(idleRunnable, IDLE_FADE_DELAY_MS)
            }
            BubbleState.TOUCH -> {
                stopBreathingAnimation()
                alpha = 1f
            }
            BubbleState.PROCESSING -> {
                stopBreathingAnimation()
                alpha = 1f
                glowAlpha = 0.18f
                startGlowPulse()
                startRotation()
            }
            BubbleState.DONE -> {
                stopBreathingAnimation()
                isDone = true
                flashGreen()
                bubbleHandler.postDelayed({ isDone = false; invalidate() }, 1200)
            }
        }
    }

    fun animateIn() {
        scaleX = 0f; scaleY = 0f; alpha = 0f
        animate()
            .scaleX(1f).scaleY(1f).alpha(ALPHA_ACTIVE)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(1.6f))
            .withEndAction { idleHandler.postDelayed(idleRunnable, IDLE_FADE_DELAY_MS) }
            .start()
    }

    fun animateTouchDown() {
        idleHandler.removeCallbacks(idleRunnable)
        animate().cancel()
        breathingAnimator?.cancel()
        idleFadeAnimator?.cancel()
        currentScale = 1f
        ValueAnimator.ofFloat(1f, 0.88f).apply {
            duration = 80
            interpolator = AccelerateInterpolator()
            addUpdateListener { v ->
                currentScale = v.animatedValue as Float
                alpha = 1f
                invalidate()
            }
            start()
        }
    }

    fun animateTapRelease() {
        // Spring-like bounce: 0.88 → 1.14 → 0.96 → 1.02 → 1.0
        ValueAnimator.ofFloat(0.88f, 1.14f, 0.96f, 1.02f, 1.0f).apply {
            duration = 440
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { v ->
                currentScale = v.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentScale = 1f
                    state = BubbleState.IDLE
                    startBreathingAnimation()
                    idleHandler.postDelayed(idleRunnable, IDLE_FADE_DELAY_MS)
                }
            })
            start()
        }
    }

    fun setRedTint(red: Boolean) {
        circleColor = if (red) DesignTokens.danger else DesignTokens.brandPrimary
        invalidate()
    }

    fun setDragging(dragging: Boolean) {
        isDragging = dragging
        showLandingLine = dragging
        if (dragging) {
            ValueAnimator.ofFloat(currentScale, 1.08f).apply {
                duration = 100
                addUpdateListener { currentScale = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            clearTrail()
            showLandingLine = false
            invalidate()
        }
    }

    fun addTrailPoint(pt: PointF) {
        trail.addLast(pt)
        if (trail.size > MAX_TRAIL) trail.removeFirst()
        invalidate()
    }

    fun clearTrail() {
        trail.clear()
        invalidate()
    }

    private fun startBreathingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = ValueAnimator.ofFloat(1f, 1.04f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            addUpdateListener { v ->
                currentScale = v.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopBreathingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        currentScale = 1f
        invalidate()
    }

    private fun startGlowPulse() {
        glowPulseAnimator?.cancel()
        glowPulseAnimator = ValueAnimator.ofFloat(0.18f, 0.45f, 0.18f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener { v ->
                glowAlpha = v.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startRotation() {
        rotationAnimator?.cancel()
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { v ->
                starRotation = v.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startIdleFade() {
        idleFadeAnimator?.cancel()
        idleFadeAnimator = ValueAnimator.ofFloat(ALPHA_ACTIVE, ALPHA_DORMANT).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { v ->
                alpha = v.animatedValue as Float
            }
            start()
        }
    }

    private fun flashGreen() {
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(),
            DesignTokens.brandPrimary, DesignTokens.success, DesignTokens.brandPrimary
        ).apply {
            duration = 600
            addUpdateListener { v ->
                circleColor = v.animatedValue as Int
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    circleColor = DesignTokens.brandPrimary
                }
            })
            start()
        }
    }

    private fun cancelStateAnimators() {
        breathingAnimator?.cancel()
        glowPulseAnimator?.cancel()
        rotationAnimator?.cancel()
        idleFadeAnimator?.cancel()
        colorAnimator?.cancel()
        glowAlpha = 0.18f
        starRotation = 0f
    }

    fun cancelAll() {
        cancelStateAnimators()
        idleHandler.removeCallbacksAndMessages(null)
        animate().cancel()
    }

    companion object {
        private const val ALPHA_ACTIVE = 0.92f
        private const val ALPHA_DORMANT = 0.45f
        private const val IDLE_FADE_DELAY_MS = 8000L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Radial menu view (3 items in 120-degree arc above bubble)
// ─────────────────────────────────────────────────────────────────────────────
class RadialMenuView(
    context: Context,
    private val items: List<RadialItem>,
    private val onItemClick: (RadialItem) -> Unit
) : View(context) {

    data class RadialItem(val label: String, val letter: String)

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DesignTokens.brandPrimary
        textAlign = Paint.Align.CENTER
        textSize = 14f * context.resources.displayMetrics.density
    }

    private val itemPositions = mutableListOf<PointF>()
    private val itemRadius = 22f.dpToPx(context)
    private var animProgress = 0f

    fun animateIn() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun animateOut(onEnd: () -> Unit) {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180
            interpolator = AccelerateInterpolator()
            addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd() }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        itemPositions.clear()
        val cx = w / 2f
        val cy = h.toFloat()
        val arcRadius = h * 0.60f
        val count = items.size
        val totalArc = 135.0
        val startAngle = -90.0 - totalArc / 2.0
        val step = if (count > 1) totalArc / (count - 1) else 0.0
        for (i in items.indices) {
            val angle = Math.toRadians(startAngle + i * step)
            itemPositions.add(PointF(
                cx + arcRadius * cos(angle).toFloat(),
                cy + arcRadius * sin(angle).toFloat()
            ))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bg = if (context.isDarkMode()) DesignTokens.surfaceRaisedDark else DesignTokens.surfaceBase
        itemPositions.forEachIndexed { i, pt ->
            val delay = i * 0.1f
            val p = ((animProgress - delay) / (1f - delay)).coerceIn(0f, 1f)
            val r = itemRadius * p
            circlePaint.color = bg
            circlePaint.alpha = (p * 255).toInt()
            canvas.drawCircle(pt.x, pt.y, r, circlePaint)
            textPaint.alpha = (p * 255).toInt()
            canvas.drawText(items[i].letter, pt.x, pt.y + textPaint.textSize * 0.35f, textPaint)
        }
    }
}
