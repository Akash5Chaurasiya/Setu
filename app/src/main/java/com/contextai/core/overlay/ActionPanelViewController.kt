package com.contextai.core.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import androidx.core.content.ContextCompat
import android.os.Looper
import android.provider.CalendarContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.*
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.ScrollView
import com.contextai.R
import com.contextai.core.*
import com.contextai.core.AppLaunchHelper
import com.contextai.core.LanguageDetector
import com.contextai.core.security.MicrophoneGate
import com.contextai.data.local.AppMemoryDao
import com.contextai.data.local.AppMemoryEntity
import com.contextai.domain.model.ApiResult
import com.contextai.domain.model.ContextType
import com.contextai.domain.model.ScreenContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.abs

class ActionPanelViewController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private val onQuerySubmit: (String, ScreenContext) -> Flow<ApiResult<String>>,
    private val onSaveHistory: (String, String, ScreenContext) -> Unit,
    private val onNewConversation: () -> Unit = {},
    private val onDismissed: () -> Unit = {},
    private val appMemoryDao: AppMemoryDao? = null,
    private val urlSummarizer: UrlSummarizer? = null,
    private val microphoneGate: MicrophoneGate? = null
) {
    private var rootView: FrameLayout? = null
    private var backdropView: View? = null
    private var panelView: LinearLayout? = null
    private var actionsContainer: LinearLayout? = null
    private var responseCard: FrameLayout? = null
    private var responseScrollView: ScrollView? = null
    private var shimmerContainer: LinearLayout? = null
    private var tvResponse: TextView? = null
    private var progressBar: View? = null
    private var btnRetry: TextView? = null
    private var smartActionsRow: HorizontalScrollView? = null
    private var smartActionsContainer: LinearLayout? = null
    private var contentScrollView: androidx.core.widget.NestedScrollView? = null
    private var etUserQuery: EditText? = null
    private var btnSend: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var isShown = false
    private var streamJob: Job? = null
    private var currentContext: ScreenContext? = null
    private var selectedActionIndex = -1
    private var keyboardVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Voice input
    private var speechRecognizer: SpeechRecognizer? = null
    private var micButton: View? = null

    // Bilingual response
    data class BilingualResponse(
        val nativeText: String,
        val englishText: String?,
        val languageCode: String,
        val languageName: String,
        val isHinglish: Boolean = false
    )
    private var languageBadgeView: TextView? = null
    private var englishTranslationCard: LinearLayout? = null
    private var englishTranslationText: TextView? = null
    private var englishToggleChip: TextView? = null
    private var englishExpanded = false
    private var currentDetection: LanguageDetector.DetectionResult? = null

    // What is this? mode
    private var whatIsThisMode = false
    private var pulsingDotContainer: LinearLayout? = null
    private var cursorHandler = Handler(Looper.getMainLooper())
    private var cursorVisible = true
    private val cursorRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            cursorHandler.postDelayed(this, 500)
        }
    }

    private val screenHeight: Int get() = context.resources.displayMetrics.heightPixels
    private val screenWidth: Int get() = context.resources.displayMetrics.widthPixels

    // Drag-to-dismiss
    private var dragStartY = 0f
    private var panelStartTransY = 0f
    private var dragVelocityTracker: VelocityTracker? = null

    fun show(screenContext: ScreenContext) {
        if (isShown) return
        currentContext = screenContext

        val root = FrameLayout(context)
        rootView = root

        val backdrop = createBackdrop()
        backdropView = backdrop
        root.addView(backdrop, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val panel = createPanel(screenContext)
        panelView = panel
        panel.translationY = 1200f
        panel.alpha = 0f
        val panelLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (screenHeight * 0.88f).toInt()
        ).apply { gravity = Gravity.BOTTOM }
        root.addView(panel, panelLp)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0f
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        runCatching {
            windowManager.addView(root, params)
            isShown = true
            root.post {
                animateIn()
                setupKeyboardListener(root)
            }
        }.onFailure {
            Timber.e(it, "Failed to show action panel")
            isShown = false
        }
    }

    fun dismiss() {
        if (!isShown) return
        HapticEngine.dismiss(context)
        streamJob?.cancel()
        cursorHandler.removeCallbacks(cursorRunnable)
        microphoneGate?.close()
        speechRecognizer?.destroy()
        speechRecognizer = null
        animateOut {
            runCatching {
                hideKeyboard()
                windowManager.removeView(rootView)
                isShown = false
                rootView = null
                whatIsThisMode = false
            }.onFailure { Timber.w(it, "removeView dismiss") }
            onDismissed()
        }
    }

    fun isPanelVisible(): Boolean = isShown

    fun dismissPanel() = dismiss()

    private fun createBackdrop(): View {
        return View(context).apply {
            setBackgroundColor(backdropColor(context))
            alpha = 0f
            setOnClickListener { dismiss() }
        }
    }

    private fun createPanel(screenContext: ScreenContext): LinearLayout {
        val isDark = context.isDarkMode()
        val panelBg = surfaceColor(context)
        val borderColor = surfaceBorderColor(context)
        val density = context.resources.displayMetrics.density

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelBg)
            // Rounded top corners
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + DesignTokens.radiusLG.toInt().dpToPx(context), DesignTokens.radiusLG.dpToPx(context))
                }
            }
            clipToOutline = true
            elevation = 24f * density
        }

        // Handle bar
        val handleContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 28.dpToPx(context))
        }
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(borderColor)
                cornerRadius = DesignTokens.radiusPill.dpToPx(context)
            }
            layoutParams = FrameLayout.LayoutParams(40.dpToPx(context), 4.dpToPx(context)).apply {
                gravity = Gravity.CENTER
                topMargin = 12.dpToPx(context)
            }
        }
        handleContainer.addView(handle)
        panel.addView(handleContainer)
        setupPanelDrag(panel, handle)

        // Context pill
        val pill = createContextPill(screenContext)
        val pillLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 32.dpToPx(context)).apply {
            leftMargin = 16.dpToPx(context)
            rightMargin = 16.dpToPx(context)
            bottomMargin = 12.dpToPx(context)
        }
        pill.layoutParams = pillLp
        pill.alpha = 0f
        pill.translationY = 8.dpToPx(context).toFloat()
        panel.addView(pill)
        mainHandler.postDelayed({
            pill.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        }, 80)

        // Memory row — asynchronously loaded from Room
        val memoryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(16.dpToPx(context), 0, 16.dpToPx(context), 8.dpToPx(context))
        }
        panel.addView(memoryRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        if (appMemoryDao != null) {
            scope.launch {
                val memories = withContext(Dispatchers.IO) {
                    appMemoryDao.getMemoriesForApp(screenContext.appPackage)
                }
                if (memories.isNotEmpty()) {
                    val lastAction = memories.first().lastAction
                    mainHandler.post {
                        val clockTv = TextView(context).apply {
                            text = "🕐"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        }
                        val memTv = TextView(context).apply {
                            text = "Last time: $lastAction"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textXS)
                            setTextColor(DesignTokens.textTertiary)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                leftMargin = 4.dpToPx(context)
                            }
                        }
                        memTv.setOnClickListener {
                            etUserQuery?.setText(lastAction)
                            etUserQuery?.setSelection(lastAction.length)
                        }
                        memoryRow.addView(clockTv)
                        memoryRow.addView(memTv)
                        memoryRow.visibility = View.VISIBLE
                        memoryRow.alpha = 0f
                        memoryRow.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
        }

        // Scrollable middle section — actions + response + smart actions scroll together;
        // inputBar is pinned OUTSIDE this scroll so it is always visible.
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 12.dpToPx(context))
        }
        val nestedScroll = androidx.core.widget.NestedScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(scrollContent, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        contentScrollView = nestedScroll
        panel.addView(nestedScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Actions container — inside scrollContent
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(context), 0, 16.dpToPx(context), 8.dpToPx(context))
        }
        actionsContainer = actions
        buildActionButtons(screenContext, actions)
        scrollContent.addView(actions)

        // Response card — directly inside scrollContent; NestedScrollView handles all scrolling
        val rc = createResponseCard()
        responseCard = rc
        rc.visibility = View.GONE
        responseScrollView = null
        val rcLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 16.dpToPx(context)
            rightMargin = 16.dpToPx(context)
            topMargin = 8.dpToPx(context)
            bottomMargin = 8.dpToPx(context)
        }
        scrollContent.addView(rc, rcLp)

        // Smart actions row — inside scrollContent
        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        smartActionsRow = scrollView
        val sac = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dpToPx(context), 8.dpToPx(context), 16.dpToPx(context), 8.dpToPx(context))
        }
        smartActionsContainer = sac
        scrollView.addView(sac)
        scrollContent.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Input bar — pinned outside the scroll, always visible at the bottom of the panel
        val inputBar = createInputBar()
        panel.addView(inputBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return panel
    }

    private fun createContextPill(ctx: ScreenContext): LinearLayout {
        val appColor = packageToColor(ctx.appPackage)
        val isDark = context.isDarkMode()
        val bg = surfaceOverlayColor(context)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = DesignTokens.radiusPill.dpToPx(context)
            }
            setPadding(12.dpToPx(context), 6.dpToPx(context), 12.dpToPx(context), 6.dpToPx(context))

            // App color dot
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(appColor)
                }
                layoutParams = LinearLayout.LayoutParams(8.dpToPx(context), 8.dpToPx(context))
            }
            addView(dot)

            // App name
            val appName = TextView(context).apply {
                text = ctx.appName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
                setTextColor(textSecondaryColor(context))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = 6.dpToPx(context)
                }
            }
            addView(appName)

            // Separator
            val sep = TextView(context).apply {
                text = "·"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
                setTextColor(DesignTokens.textTertiary)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = 6.dpToPx(context)
                    rightMargin = 6.dpToPx(context)
                }
            }
            addView(sep)

            // Context type label
            val label = TextView(context).apply {
                text = contextTypeLabel(ctx.detectedType.name)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
                setTextColor(DesignTokens.brandPrimary)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            addView(label)
        }
    }

    private fun buildActionButtons(ctx: ScreenContext, container: LinearLayout) {
        container.removeAllViews()
        val chips = when (ctx.detectedType) {
            ContextType.JOB_POST -> listOf(
                Triple("Draft Application Email", "Write a professional cover letter", "✉"),
                Triple("Summarize Requirements", "Key requirements at a glance", "📋"),
                Triple("Check My Fit", "How well you match this role", "⭐")
            )
            ContextType.FOOD_ORDER -> listOf(
                Triple("Summarize Cart", "What's in your order", "🛒"),
                Triple("Suggest Alternatives", "Healthier or cheaper options", "💡"),
                Triple("Calculate Calories", "Estimate total calories", "🔢")
            )
            ContextType.TRAVEL -> listOf(
                Triple("Plan This Trip", "Full trip breakdown", "🗺"),
                Triple("Estimate Total Cost", "Including taxes and fees", "💰"),
                Triple("Add to Calendar", "Save key dates", "📅")
            )
            else -> listOf(
                Triple("Summarize Page", "Key information at a glance", "📄"),
                Triple("Ask AI", "Type your own question", "💬"),
                Triple("Copy Key Info", "Extract important details", "📎")
            )
        }

        val prompts = when (ctx.detectedType) {
            ContextType.JOB_POST -> listOf(
                "Draft a professional application email for this job posting.",
                "Summarize the key requirements and responsibilities for this job.",
                "Analyze how well my profile fits this job. Be specific about matching skills and gaps."
            )
            ContextType.FOOD_ORDER -> listOf(
                "Summarize my current cart and total order.",
                "Suggest healthier or cheaper alternatives to items in my cart.",
                "Estimate the total calorie count of my order."
            )
            ContextType.TRAVEL -> listOf(
                "Help me plan the details for this trip.",
                "Estimate the total cost for this trip including taxes and fees.",
                "Extract key travel dates and create a calendar summary."
            )
            else -> listOf(
                "Summarize the key information on this screen.",
                "What can I help you with?",
                "Extract and list the most important information from this screen."
            )
        }

        chips.forEachIndexed { i, (title, subtitle, icon) ->
            val btn = createActionButton(title, subtitle, icon, i == 0) {
                selectActionButton(container, i)
                sendQuery(prompts[i], ctx)
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dpToPx(context)).apply {
                topMargin = if (i == 0) 0 else 8.dpToPx(context)
            }
            btn.layoutParams = lp
            btn.alpha = 0f
            btn.translationY = 16.dpToPx(context).toFloat()
            container.addView(btn)
            mainHandler.postDelayed({
                btn.animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(240).setInterpolator(DecelerateInterpolator())
                    .start()
            }, i * 60L)
        }
    }

    private fun createActionButton(
        title: String,
        subtitle: String,
        iconText: String,
        isPrimary: Boolean,
        onClick: () -> Unit
    ): View {
        val isDark = context.isDarkMode()
        val bgColor = if (isPrimary) DesignTokens.brandPrimary else surfaceRaisedColor(context)
        val titleColor = if (isPrimary) Color.WHITE else textPrimaryColor(context)
        val subtitleColor = if (isPrimary) 0xB3FFFFFF.toInt() else textSecondaryColor(context)
        val iconBg = if (isPrimary) 0x26FFFFFF.toInt() else (DesignTokens.brandPrimary and 0x1AFFFFFF.toInt()).let { DesignTokens.brandPrimary and 0x00FFFFFF or 0x1A000000 }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(context), 0, 16.dpToPx(context), 0)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = DesignTokens.radiusMD.dpToPx(context)
                if (!isPrimary) {
                    setStroke(1, surfaceBorderColor(context))
                }
            }
            isClickable = true
            isFocusable = true
        }

        // Icon circle
        val iconCircle = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(40.dpToPx(context), 40.dpToPx(context))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isPrimary) 0x26FFFFFF.toInt() else (DesignTokens.brandPrimary and 0x1AFFFFFF.toInt()))
            }
        }
        val iconTv = TextView(context).apply {
            text = iconText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }
        iconCircle.addView(iconTv)
        row.addView(iconCircle)

        // Text block
        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 12.dpToPx(context)
            }
        }
        val titleTv = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textMD)
            setTextColor(titleColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitleTv = TextView(context).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
            setTextColor(subtitleColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 2.dpToPx(context)
            }
        }
        textBlock.addView(titleTv)
        textBlock.addView(subtitleTv)
        row.addView(textBlock)

        row.setOnClickListener { onClick() }
        row.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(OvershootInterpolator(1.5f)).start()
            }
            false
        }

        return row
    }

    private fun createResponseCard(): FrameLayout {
        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(surfaceRaisedColor(context))
                cornerRadius = DesignTokens.radiusMD.dpToPx(context)
                setStroke(1, surfaceBorderColor(context))
            }
            setPadding(16.dpToPx(context), 16.dpToPx(context), 16.dpToPx(context), 16.dpToPx(context))
        }

        // Shimmer container
        val shimmer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        shimmerContainer = shimmer
        listOf(0.85f, 0.65f, 0.75f).forEachIndexed { i, widthFraction ->
            val line = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(surfaceBorderColor(context))
                    cornerRadius = 6.dpToPx(context).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, 12.dpToPx(context)).apply {
                    width = (context.resources.displayMetrics.widthPixels * widthFraction * 0.7f).toInt()
                    topMargin = if (i == 0) 0 else 8.dpToPx(context)
                }
                tag = i
            }
            shimmer.addView(line)
            ValueAnimator.ofFloat(0.4f, 0.9f, 0.4f).apply {
                duration = 1200
                startDelay = i * 150L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { line.alpha = it.animatedValue as Float }
                start()
            }
        }
        card.addView(shimmer)

        // Response text view
        val tv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textMD)
            setTextColor(textPrimaryColor(context))
            setLineSpacing(0f, 1.5f)
            visibility = View.GONE
        }
        tvResponse = tv
        card.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // Retry button
        val retry = TextView(context).apply {
            text = context.getString(R.string.btn_retry)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
            setTextColor(DesignTokens.brandPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                topMargin = 8.dpToPx(context)
            }
        }
        btnRetry = retry
        card.addView(retry)

        return card
    }

    private fun createInputBar(): LinearLayout {
        val isDark = context.isDarkMode()
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor(context))
        }

        // Top separator
        val sep = View(context).apply {
            setBackgroundColor(surfaceBorderColor(context))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        bar.addView(sep)

        // Measure navigation bar height so the input row is never hidden behind it
        val navBarH = run {
            val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        }

        // Row with input + send button
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context) + navBarH)
        }

        // Input pill
        val inputPill = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(surfaceOverlayColor(context))
                cornerRadius = DesignTokens.radiusPill.dpToPx(context)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 8.dpToPx(context)
            }
        }
        val et = EditText(context).apply {
            hint = context.getString(R.string.hint_ask_anything)
            setHintTextColor(DesignTokens.textTertiary)
            setTextColor(textPrimaryColor(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textMD)
            background = null
            setPadding(16.dpToPx(context), 10.dpToPx(context), 8.dpToPx(context), 10.dpToPx(context))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        etUserQuery = et
        inputPill.addView(et)
        row.addView(inputPill)

        // Mic button (hold to speak)
        val mic = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(34.dpToPx(context), 34.dpToPx(context)).apply {
                rightMargin = 6.dpToPx(context)
            }
        }
        val micTv = TextView(context).apply {
            text = "🎙"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mic.addView(micTv)
        micButton = mic
        row.addView(mic)

        // Send button
        val send = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(DesignTokens.brandPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(34.dpToPx(context), 34.dpToPx(context))
        }
        val arrow = TextView(context).apply {
            text = "↑"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        send.addView(arrow)
        btnSend = send
        row.addView(send)

        bar.addView(row)

        send.setOnClickListener {
            val query = et.text?.toString()?.trim()
            if (!query.isNullOrBlank()) {
                HapticEngine.actionSelected(context)
                sendQuery(query, currentContext ?: return@setOnClickListener)
            }
        }
        send.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(2f)).start()
            }
            false
        }

        setupVoiceInput(mic, et)

        // Close button (top right via overlay)
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(DesignTokens.textTertiary)
            setPadding(8.dpToPx(context), 4.dpToPx(context), 8.dpToPx(context), 4.dpToPx(context))
            setOnClickListener { dismiss() }
        }
        val closeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(8.dpToPx(context), 4.dpToPx(context), 8.dpToPx(context), 0)
            addView(closeBtn)
        }
        bar.addView(closeRow, 0)

        return bar
    }

    private fun setupPanelDrag(panel: LinearLayout, handle: View) {
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    panelStartTransY = panel.translationY
                    dragVelocityTracker = VelocityTracker.obtain()
                    dragVelocityTracker?.addMovement(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    dragVelocityTracker?.addMovement(event)
                    val dy = event.rawY - dragStartY
                    if (dy > 0) panel.translationY = dy
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragVelocityTracker?.computeCurrentVelocity(1000)
                    val vy = dragVelocityTracker?.yVelocity ?: 0f
                    dragVelocityTracker?.recycle()
                    dragVelocityTracker = null
                    val panelHeight = panel.height.toFloat()
                    if (vy > 800 || panel.translationY > panelHeight * 0.4f) {
                        dismiss()
                    } else {
                        panel.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
                    }
                }
            }
            true
        }
    }

    fun parseResponse(raw: String, detection: LanguageDetector.DetectionResult): BilingualResponse {
        val sepIndex = raw.indexOf("\n---\n")
        return if (sepIndex >= 0) {
            val native = raw.substring(0, sepIndex).trim()
            val afterSep = raw.substring(sepIndex + 5).trim()
            val english = if (afterSep.startsWith("English:", ignoreCase = true)) {
                afterSep.substring(8).trim()
            } else afterSep
            BilingualResponse(native, english.ifBlank { null }, detection.languageCode, detection.languageName, detection.isHinglish)
        } else {
            BilingualResponse(raw.trim(), null, detection.languageCode, detection.languageName, detection.isHinglish)
        }
    }

    fun displayBilingualResponse(response: BilingualResponse) {
        val tv = tvResponse ?: return
        val rc = responseCard ?: return

        tv.text = applyMarkdown(response.nativeText)

        // Remove old badge/chip if present
        languageBadgeView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        englishTranslationCard?.let { (it.parent as? ViewGroup)?.removeView(it) }
        englishToggleChip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        englishExpanded = false

        if (response.englishText != null) {
            val badge = TextView(context).apply {
                val label = if (response.isHinglish) "Hinglish" else response.languageName
                text = "  $label  "
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, DesignTokens.textXS)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(DesignTokens.brandPrimary)
                    cornerRadius = DesignTokens.radiusPill.dpToPx(context)
                }
                setPadding(0, 3.dpToPx(context), 0, 3.dpToPx(context))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
            }
            languageBadgeView = badge
            rc.addView(badge)

            val chip = TextView(context).apply {
                text = "Show in English"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
                setTextColor(DesignTokens.brandPrimary)
                background = GradientDrawable().apply {
                    setColor(surfaceOverlayColor(context))
                    cornerRadius = DesignTokens.radiusPill.dpToPx(context)
                    setStroke(1, DesignTokens.brandPrimary)
                }
                setPadding(12.dpToPx(context), 4.dpToPx(context), 12.dpToPx(context), 4.dpToPx(context))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    topMargin = 8.dpToPx(context)
                }
            }
            englishToggleChip = chip

            val translationCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                background = GradientDrawable().apply {
                    setColor(surfaceRaisedColor(context))
                    cornerRadius = DesignTokens.radiusMD.dpToPx(context)
                    setStroke(1, DesignTokens.brandPrimary)
                }
                setPadding(12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context), 12.dpToPx(context))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 48.dpToPx(context)
                }
            }
            val translationTv = TextView(context).apply {
                text = applyMarkdown(response.englishText)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
                setTextColor(textSecondaryColor(context))
                setLineSpacing(0f, 1.4f)
            }
            englishTranslationText = translationTv
            translationCard.addView(translationTv)
            englishTranslationCard = translationCard

            chip.setOnClickListener {
                englishExpanded = !englishExpanded
                translationCard.visibility = if (englishExpanded) View.VISIBLE else View.GONE
                chip.text = if (englishExpanded) "Hide English" else "Show in English"
            }

            rc.addView(chip)
            rc.addView(translationCard)
        } else if (response.languageCode == "en" || response.languageCode.isEmpty()) {
            // English response — offer Hindi translation chip hint (cosmetic only)
        }
    }

    private fun sendQuery(query: String, screenContext: ScreenContext) {
        val tv = tvResponse ?: return
        val rc = responseCard ?: return
        val shimmer = shimmerContainer ?: return
        currentDetection = LanguageDetector.detect(query)
        streamJob?.cancel()
        cursorHandler.removeCallbacks(cursorRunnable)

        // Reset bilingual UI
        languageBadgeView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        englishTranslationCard?.let { (it.parent as? ViewGroup)?.removeView(it) }
        englishToggleChip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        languageBadgeView = null; englishTranslationCard = null; englishToggleChip = null
        englishExpanded = false

        responseCard?.visibility = View.VISIBLE
        // Scroll NestedScrollView to show the response card (it was GONE before, layout needs a post)
        rc.post { contentScrollView?.smoothScrollTo(0, rc.top) }
        if (!whatIsThisMode) {
            shimmer.visibility = View.VISIBLE
        }
        tv.visibility = View.GONE
        btnRetry?.visibility = View.GONE
        smartActionsRow?.visibility = View.GONE
        smartActionsContainer?.removeAllViews()
        et()?.setText(query)
        et()?.clearFocus()
        hideKeyboard()

        val fullResponse = StringBuilder()

        streamJob = scope.launch {
            val completed = withTimeoutOrNull(15_000L) {
                onQuerySubmit(query, screenContext).collect { result ->
                    mainHandler.post {
                        when (result) {
                            is ApiResult.Success -> {
                                val loadingVisible = shimmer.visibility == View.VISIBLE
                                    || (pulsingDotContainer?.visibility == View.VISIBLE)
                                if (loadingVisible) {
                                    shimmer.visibility = View.GONE
                                    pulsingDotContainer?.visibility = View.GONE
                                    tv.visibility = View.VISIBLE
                                    cursorHandler.post(cursorRunnable)
                                    HapticEngine.aiResponseStart(context)
                                }
                                fullResponse.append(result.data)
                                val display = "${fullResponse}${if (cursorVisible) "▋" else ""}"
                                tv.text = display
                                // Keep latest streamed text in view
                                contentScrollView?.post { contentScrollView?.fullScroll(View.FOCUS_DOWN) }
                            }
                            is ApiResult.Error -> {
                                shimmer.visibility = View.GONE
                                pulsingDotContainer?.visibility = View.GONE
                                tv.visibility = View.VISIBLE
                                if (fullResponse.isEmpty()) {
                                    tv.text = result.message
                                    btnRetry?.visibility = View.VISIBLE
                                    btnRetry?.setOnClickListener { sendQuery(query, screenContext) }
                                }
                            }
                        }
                    }
                }
            }
            mainHandler.post {
                cursorHandler.removeCallbacks(cursorRunnable)
                shimmer.visibility = View.GONE
                tv.visibility = View.VISIBLE
                val responseText = fullResponse.toString()
                if (completed == null && responseText.isBlank()) {
                    tv.text = context.getString(R.string.error_timeout)
                    btnRetry?.visibility = View.VISIBLE
                    btnRetry?.setOnClickListener { sendQuery(query, screenContext) }
                } else if (responseText.isNotBlank()) {
                    val detection = currentDetection
                    if (detection != null) {
                        val bilingual = parseResponse(responseText, detection)
                        displayBilingualResponse(bilingual)
                    } else {
                        tv.text = applyMarkdown(responseText)
                    }
                    if (whatIsThisMode) {
                        tv.maxLines = 4
                        showWhatIsThisChips(responseText)
                        whatIsThisMode = false
                    } else {
                        showSmartActions(responseText, screenContext)
                    }
                    // Scroll back to top of response so user reads from the beginning
                    mainHandler.postDelayed({
                        contentScrollView?.smoothScrollTo(0, rc.top)
                    }, 120)
                    HapticEngine.actionComplete(context)
                    onSaveHistory(query, responseText, screenContext)
                    saveMemory(query, responseText, screenContext)
                }
            }
        }
    }

    private fun et(): EditText? = etUserQuery

    private fun applyMarkdown(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        text.lines().forEachIndexed { idx, rawLine ->
            if (idx > 0) sb.append('\n')
            val line = rawLine.trimEnd()
            when {
                line.startsWith("### ") -> {
                    val start = sb.length
                    sb.append(line.removePrefix("### "))
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    val start = sb.length
                    sb.append(line.substring(2))
                    sb.setSpan(
                        BulletSpan(8.dpToPx(context), DesignTokens.brandPrimary),
                        start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                line == "---" -> {
                    sb.append("─────────────────────────")
                }
                else -> appendInlineMarkdown(sb, line)
            }
        }
        return sb
    }

    private fun appendInlineMarkdown(sb: SpannableStringBuilder, line: String) {
        var rem = line
        while (rem.isNotEmpty()) {
            when {
                rem.startsWith("**") -> {
                    val end = rem.indexOf("**", 2)
                    if (end == -1) { sb.append(rem); return }
                    val start = sb.length
                    sb.append(rem.substring(2, end))
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    rem = rem.substring(end + 2)
                }
                rem.startsWith("*") -> {
                    val end = rem.indexOf("*", 1)
                    if (end == -1) { sb.append(rem); return }
                    val start = sb.length
                    sb.append(rem.substring(1, end))
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    rem = rem.substring(end + 1)
                }
                rem.startsWith("`") -> {
                    val end = rem.indexOf("`", 1)
                    if (end == -1) { sb.append(rem); return }
                    val start = sb.length
                    sb.append(rem.substring(1, end))
                    sb.setSpan(BackgroundColorSpan(surfaceOverlayColor(context)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    rem = rem.substring(end + 1)
                }
                else -> {
                    val nextSpecial = listOf(rem.indexOf("**"), rem.indexOf("*"), rem.indexOf("`"))
                        .filter { it > 0 }.minOrNull() ?: rem.length
                    sb.append(rem.substring(0, nextSpecial))
                    rem = rem.substring(nextSpecial)
                }
            }
        }
    }

    private fun showSmartActions(response: String, screenContext: ScreenContext) {
        val container = smartActionsContainer ?: return
        val scrollRow = smartActionsRow ?: return
        container.removeAllViews()
        scrollRow.visibility = View.VISIBLE

        val actions = mutableListOf(
            Pair(context.getString(R.string.action_copy)) { copyToClipboard(response) },
            Pair(context.getString(R.string.action_share)) { shareText(response) }
        )
        if (screenContext.detectedEmails.isNotEmpty() || screenContext.detectedType == ContextType.JOB_POST) {
            actions.add(Pair(context.getString(R.string.action_open_gmail)) { openGmailWithDraft(response, screenContext) })
        }
        if (screenContext.detectedType == ContextType.TRAVEL ||
            response.contains(Regex("\\d{1,2}/\\d{1,2}|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec", RegexOption.IGNORE_CASE))
        ) {
            actions.add(Pair(context.getString(R.string.action_add_calendar)) { addToCalendar(response) })
        }
        actions.add(Pair(context.getString(R.string.action_new_conversation)) {
            onNewConversation()
            responseCard?.visibility = View.GONE
            tvResponse?.text = ""
            smartActionsRow?.visibility = View.GONE
            smartActionsContainer?.removeAllViews()
            etUserQuery?.setText("")
        })

        // Bug 2: prepend app-open chip if AI response contains "open X" / "launch X"
        val appToOpen = AppLaunchHelper.extractAppOpenIntent(response)
        if (appToOpen != null) {
            val label = "Open ${appToOpen.trim().replaceFirstChar { it.uppercase() }}"
            actions.add(0, Pair(label) {
                val ok = AppLaunchHelper.openApp(context, appToOpen)
                if (!ok) Toast.makeText(context, "Could not find $appToOpen", Toast.LENGTH_SHORT).show()
            })
        }

        // Bug 4: use marginStart instead of broken spacer-View approach
        actions.forEachIndexed { i, (label, action) ->
            val chip = createSmartChip(label, action)
            chip.scaleX = 0.7f; chip.scaleY = 0.7f; chip.alpha = 0f
            val chipLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) marginStart = 10.dpToPx(context) }
            chip.layoutParams = chipLp
            container.addView(chip)
            mainHandler.postDelayed({
                chip.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(200).setInterpolator(OvershootInterpolator(1.3f)).start()
            }, i * 50L)
        }
    }

    private fun createSmartChip(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
            setTextColor(DesignTokens.brandPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(14.dpToPx(context), 10.dpToPx(context), 14.dpToPx(context), 10.dpToPx(context))
            background = GradientDrawable().apply {
                setColor(surfaceOverlayColor(context))
                cornerRadius = DesignTokens.radiusPill.dpToPx(context)
                setStroke(1, DesignTokens.brandSecondary)
            }
            setOnClickListener { onClick() }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(60).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
                false
            }
        }
    }

    // Bug 1: correct selection highlight on action buttons
    private fun selectActionButton(container: LinearLayout, selectedIdx: Int) {
        selectedActionIndex = selectedIdx
        for (i in 0 until container.childCount) {
            val btn = container.getChildAt(i) ?: continue
            val isSelected = i == selectedIdx
            btn.background = GradientDrawable().apply {
                setColor(if (isSelected) Color.argb(26, 108, 99, 255) else surfaceRaisedColor(context))
                cornerRadius = DesignTokens.radiusMD.dpToPx(context)
                if (!isSelected) setStroke(1, surfaceBorderColor(context))
            }
        }
    }

    // Bug 3: keyboard detection for overlay windows (ViewTreeObserver fallback)
    private fun setupKeyboardListener(rootView: View) {
        val rect = android.graphics.Rect()
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenH = rootView.rootView.height
            val keypadH = screenH - rect.bottom
            if (keypadH > screenH * 0.15f) {
                onKeyboardHeightChanged(keypadH)
            } else {
                onKeyboardHeightChanged(0)
            }
        }
    }

    private fun onKeyboardHeightChanged(keyboardHeight: Int) {
        val panel = panelView ?: return
        if (keyboardHeight > 0 && !keyboardVisible) {
            keyboardVisible = true
            val newH = screenHeight - keyboardHeight
            val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
            lp.height = newH
            panel.layoutParams = lp
            mainHandler.postDelayed({ contentScrollView?.fullScroll(View.FOCUS_DOWN) }, 200)
        } else if (keyboardHeight == 0 && keyboardVisible) {
            keyboardVisible = false
            val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
            lp.height = (screenHeight * 0.88f).toInt()
            panel.layoutParams = lp
        }
    }

    private fun animateIn() {
        val panel = panelView ?: return
        val backdrop = backdropView ?: return
        panel.animate()
            .translationY(0f).alpha(1f)
            .setDuration(DesignTokens.durationEnter)
            .setInterpolator(DecelerateInterpolator(2.5f))
            .start()
        backdrop.animate().alpha(1f).setDuration(DesignTokens.durationEnter).start()
    }

    private fun animateOut(onEnd: () -> Unit) {
        val panel = panelView
        val backdrop = backdropView
        if (panel == null) { onEnd(); return }
        panel.animate()
            .translationY(panel.height.toFloat().coerceAtLeast(600f))
            .alpha(0f)
            .setDuration(DesignTokens.durationExit)
            .setInterpolator(AccelerateInterpolator(2f))
            .withEndAction(onEnd)
            .start()
        backdrop?.animate()?.alpha(0f)?.setDuration(DesignTokens.durationExit)?.start()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ContextAI Response", text))
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openGmailWithDraft(response: String, screenContext: ScreenContext) {
        val fallbackEmail = screenContext.detectedEmails.firstOrNull()
        val lines = response.lines()
        var to = fallbackEmail ?: ""
        var subject = ""
        var bodyStart = 0
        lines.forEachIndexed { i, line ->
            when {
                line.startsWith("TO:", ignoreCase = true) -> {
                    val parsed = line.removePrefix("TO:").removePrefix("to:").trim()
                    if (parsed.contains("@") && parsed != "UNKNOWN") to = parsed
                    bodyStart = i + 1
                }
                line.startsWith("SUBJECT:", ignoreCase = true) -> {
                    subject = line.removePrefix("SUBJECT:").removePrefix("subject:").trim()
                    bodyStart = i + 1
                }
            }
        }
        if (subject.isBlank()) subject = lines.firstOrNull()?.take(60) ?: "Follow up"
        val body = lines.drop(bodyStart).dropWhile { it.isBlank() }.joinToString("\n").trim()

        val mailtoUri = "mailto:${Uri.encode(to)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
        val primaryIntent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailtoUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pm = context.packageManager
        when {
            primaryIntent.resolveActivity(pm) != null ->
                runCatching { context.startActivity(primaryIntent) }
                    .onFailure { Toast.makeText(context, R.string.error_open_gmail, Toast.LENGTH_SHORT).show() }
            fallbackIntent.resolveActivity(pm) != null ->
                runCatching {
                    context.startActivity(Intent.createChooser(fallbackIntent, "Send email via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { Toast.makeText(context, R.string.error_open_gmail, Toast.LENGTH_SHORT).show() }
            else -> Toast.makeText(context, R.string.error_open_gmail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun addToCalendar(response: String) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, currentContext?.appName ?: "Event")
            putExtra(CalendarContract.Events.DESCRIPTION, response.take(500))
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, System.currentTimeMillis())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, R.string.error_open_calendar, Toast.LENGTH_SHORT).show() }
    }

    private fun hideKeyboard() {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            etUserQuery?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        }
    }

    // ── Enhancement 3: Voice input ────────────────────────────────────────────

    private fun setupVoiceInput(micBtn: View, inputField: EditText) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            micBtn.visibility = View.GONE
            return
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) {
                val partial = partialResults
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                mainHandler.post {
                    inputField.setText(partial)
                    inputField.setSelection(partial.length)
                }
            }

            override fun onResults(results: Bundle) {
                val text = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                mainHandler.post {
                    inputField.setText(text)
                    inputField.setSelection(text.length)
                    stopMicAnimation(micBtn)
                    mainHandler.postDelayed({
                        if (inputField.text.toString() == text) {
                            HapticEngine.actionSelected(context)
                            sendQuery(text, currentContext ?: return@postDelayed)
                        }
                    }, 600)
                }
            }

            override fun onError(error: Int) {
                mainHandler.post {
                    stopMicAnimation(micBtn)
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { /* silent */ }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            Toast.makeText(context, "Microphone permission denied. Grant it in App Settings.", Toast.LENGTH_LONG).show()
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            Toast.makeText(context, "Mic is busy, try again", Toast.LENGTH_SHORT).show()
                        else ->
                            Toast.makeText(context, "Couldn't hear that, try again", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) { mainHandler.post { startMicAnimation(micBtn) } }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                val scale = 1f + (rmsdB.coerceIn(0f, 10f) / 10f) * 0.3f
                mainHandler.post { micBtn.animate().scaleX(scale).scaleY(scale).setDuration(80).start() }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        micBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    HapticEngine.bubbleTap(context)
                    val gate = microphoneGate
                    if (gate != null) {
                        if (gate.open(context)) {
                            recognizer.startListening(recognizerIntent)
                            // Hard-limit: auto-stop after 60 seconds
                            mainHandler.postDelayed({
                                gate.enforceLimit {
                                    recognizer.stopListening()
                                    stopMicAnimation(micBtn)
                                }
                            }, MicrophoneGate.MAX_RECORDING_MS + 500)
                        } else {
                            Toast.makeText(context,
                                "Microphone permission not granted. Enable it in App Settings.",
                                Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // Fallback: no gate injected, use raw permission check
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                            recognizer.startListening(recognizerIntent)
                        } else {
                            Toast.makeText(context,
                                "Microphone permission not granted. Enable it in App Settings.",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    microphoneGate?.close()
                    recognizer.stopListening()
                    true
                }
                else -> false
            }
        }
    }

    private fun startMicAnimation(micBtn: View) {
        micBtn.setBackgroundColor(DesignTokens.brandPrimary)
        micBtn.animate().scaleX(1.15f).scaleY(1.15f).setDuration(200).start()
    }

    private fun stopMicAnimation(micBtn: View) {
        micBtn.setBackgroundColor(Color.TRANSPARENT)
        micBtn.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    // ── Enhancement 4: What is this? ──────────────────────────────────────────

    fun triggerWhatIsThis(screenContext: ScreenContext) {
        whatIsThisMode = true
        if (!isShown) {
            show(screenContext)
            mainHandler.postDelayed({ startWhatIsThisQuery(screenContext) }, 200)
        } else {
            startWhatIsThisQuery(screenContext)
        }
    }

    private fun startWhatIsThisQuery(screenContext: ScreenContext) {
        val tv = tvResponse ?: return
        val rc = responseCard ?: return
        val shimmer = shimmerContainer ?: return

        streamJob?.cancel()
        cursorHandler.removeCallbacks(cursorRunnable)
        responseCard?.visibility = View.VISIBLE
        shimmer.visibility = View.GONE
        tv.visibility = View.GONE
        tv.maxLines = 4
        btnRetry?.visibility = View.GONE
        smartActionsRow?.visibility = View.GONE
        smartActionsContainer?.removeAllViews()

        // Single pulsing dot instead of shimmer
        val dotContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dpToPx(context), 0, 8.dpToPx(context))
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(DesignTokens.brandPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(8.dpToPx(context), 8.dpToPx(context))
        }
        val label = TextView(context).apply {
            text = "explaining..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DesignTokens.textSM)
            setTextColor(DesignTokens.textTertiary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 8.dpToPx(context) }
        }
        dotContainer.addView(dot)
        dotContainer.addView(label)
        pulsingDotContainer = dotContainer

        rc.removeAllViews()
        rc.addView(dotContainer)
        rc.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        btnRetry?.let { rc.addView(it) }

        ValueAnimator.ofFloat(1f, 0.25f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }

        val prompt = """
            In exactly 2 sentences, explain what the user is currently looking at.
            Be plain and simple — explain it like the user is not an expert.
            App: ${screenContext.appName}
            Visible content: ${screenContext.keyEntities.entries.take(8).joinToString(", ") { "${it.key}: ${it.value}" }}
            Raw text snippet: ${screenContext.rawText.take(300)}
        """.trimIndent()

        sendQuery(prompt, screenContext)
    }

    private fun showWhatIsThisChips(response: String) {
        val container = smartActionsContainer ?: return
        val scrollRow = smartActionsRow ?: return
        container.removeAllViews()
        scrollRow.visibility = View.VISIBLE

        val actions = listOf(
            Pair("Tell me more") { expandToFullConversation(response) },
            Pair("In Hindi") { retranslate(response, "Hindi") },
            Pair("Copy") { copyToClipboard(response) }
        )

        actions.forEachIndexed { i, (chipLabel, action) ->
            val chip = createSmartChip(chipLabel, action)
            chip.scaleX = 0.7f; chip.scaleY = 0.7f; chip.alpha = 0f
            container.addView(chip)
            if (i < actions.size - 1) {
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(8.dpToPx(context), 0)
                })
            }
            mainHandler.postDelayed({
                chip.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(200).setInterpolator(OvershootInterpolator(1.3f)).start()
            }, i * 50L)
        }
    }

    private fun expandToFullConversation(existingResponse: String) {
        tvResponse?.maxLines = Int.MAX_VALUE
        etUserQuery?.requestFocus()
    }

    private fun retranslate(originalResponse: String, language: String) {
        val ctx = currentContext ?: return
        sendQuery("Translate this to $language: $originalResponse", ctx)
    }

    // ── Enhancement 1: Clipboard content as context ───────────────────────────

    fun showWithClipboardContent(
        label: String,
        type: ClipboardWatcherService.ClipType,
        content: String,
        screenContext: ScreenContext
    ) {
        if (!isShown) {
            show(screenContext)
            mainHandler.postDelayed({ sendClipboardQuery(type, content, screenContext) }, 200)
        } else {
            sendClipboardQuery(type, content, screenContext)
        }
    }

    private fun sendClipboardQuery(
        type: ClipboardWatcherService.ClipType,
        content: String,
        screenContext: ScreenContext
    ) {
        if (type == ClipboardWatcherService.ClipType.URL && urlSummarizer != null) {
            sendUrlSummaryQuery(content, screenContext)
            return
        }
        val prompt = when (type) {
            ClipboardWatcherService.ClipType.EMAIL -> "Draft a professional email to: $content"
            ClipboardWatcherService.ClipType.PHONE -> "What can you tell me about this phone number: $content"
            ClipboardWatcherService.ClipType.ADDRESS -> "Help me with this address. What is it and how do I get there?: $content"
            ClipboardWatcherService.ClipType.LONG_TEXT -> "Summarize this text concisely in 3 bullet points:\n$content"
            else -> "Help me with this: $content"
        }
        sendQuery(prompt, screenContext)
    }

    // ── Enhancement 7: URL summarizer ────────────────────────────────────────

    private fun sendUrlSummaryQuery(url: String, screenContext: ScreenContext) {
        val tv = tvResponse ?: return
        val shimmer = shimmerContainer ?: return

        streamJob?.cancel()
        responseCard?.visibility = View.VISIBLE
        shimmer.visibility = View.VISIBLE
        tv.visibility = View.GONE

        streamJob = scope.launch {
            val pageText = urlSummarizer?.fetchAndExtract(url) ?: ""
            val prompt = if (pageText.isBlank()) {
                "Summarize this URL in 4-5 bullet points. Each bullet: one clear sentence. End with: 'Bottom line: [key takeaway]'\nURL: $url"
            } else {
                """
                Summarize this web page in 4-5 bullet points.
                Each bullet: one clear sentence.
                End with one sentence: "Bottom line: [key takeaway]"

                Page content:
                $pageText
                """.trimIndent()
            }
            mainHandler.post { sendQuery(prompt, screenContext) }
        }
    }

    // ── Enhancement 2: Memory saving ─────────────────────────────────────────

    private fun saveMemory(query: String, response: String, screenContext: ScreenContext) {
        val dao = appMemoryDao ?: return
        val actionSummary = buildActionSummary(query, response)
        val contextSummary = screenContext.keyEntities.entries
            .take(3).joinToString(", ") { "${it.key}: ${it.value}" }
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.saveMemory(
                    AppMemoryEntity(
                        packageName = screenContext.appPackage,
                        appName = screenContext.appName,
                        lastAction = actionSummary,
                        lastContextSummary = contextSummary,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun buildActionSummary(query: String, response: String): String {
        val firstLine = response.lines().firstOrNull { it.isNotBlank() } ?: query
        return firstLine.take(80).let { if (firstLine.length > 80) "$it…" else it }
    }

    fun destroy() {
        streamJob?.cancel()
        cursorHandler.removeCallbacks(cursorRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        whatIsThisMode = false
        if (isShown) {
            runCatching { hideKeyboard() }
            runCatching { windowManager.removeView(rootView) }
                .onFailure { Timber.w(it, "removeView destroy") }
            isShown = false
        }
        rootView = null
    }
}
