package com.contextai.core

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View

object DesignTokens {

    // Brand colors
    val brandPrimary = Color.parseColor("#6C63FF")
    val brandSecondary = Color.parseColor("#A78BFA")
    val brandAccent = Color.parseColor("#38BDF8")

    // Surfaces — light mode
    val surfaceBase = Color.parseColor("#FFFFFF")
    val surfaceRaised = Color.parseColor("#F7F7FB")
    val surfaceOverlay = Color.parseColor("#F0F0F8")
    val surfaceBorder = Color.parseColor("#E8E8F2")

    // Surfaces — dark mode
    val surfaceBaseDark = Color.parseColor("#0F0F14")
    val surfaceRaisedDark = Color.parseColor("#1A1A24")
    val surfaceOverlayDark = Color.parseColor("#22223A")
    val surfaceBorderDark = Color.parseColor("#2E2E48")

    // Text
    val textPrimary = Color.parseColor("#0F0F14")
    val textSecondary = Color.parseColor("#6B6B80")
    val textTertiary = Color.parseColor("#A0A0B8")
    val textPrimaryDark = Color.parseColor("#F0F0FA")
    val textSecondaryDark = Color.parseColor("#8888A8")

    // Semantic
    val success = Color.parseColor("#22C55E")
    val warning = Color.parseColor("#F59E0B")
    val danger = Color.parseColor("#EF4444")

    // Radii
    const val radiusXS = 8f
    const val radiusSM = 12f
    const val radiusMD = 16f
    const val radiusLG = 24f
    const val radiusXL = 32f
    const val radiusPill = 100f

    // Spacing (dp)
    const val space4 = 4
    const val space8 = 8
    const val space12 = 12
    const val space16 = 16
    const val space20 = 20
    const val space24 = 24

    // Typography (sp)
    const val textXS = 11f
    const val textSM = 13f
    const val textMD = 15f
    const val textLG = 17f
    const val textXL = 22f
    const val textDisplay = 28f

    // Motion durations (ms)
    const val durationFast = 150L
    const val durationNormal = 280L
    const val durationSlow = 420L
    const val durationEnter = 320L
    const val durationExit = 220L
}

fun Context.isDarkMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

fun surfaceColor(context: Context): Int =
    if (context.isDarkMode()) DesignTokens.surfaceBaseDark else DesignTokens.surfaceBase

fun surfaceRaisedColor(context: Context): Int =
    if (context.isDarkMode()) DesignTokens.surfaceRaisedDark else DesignTokens.surfaceRaised

fun surfaceOverlayColor(context: Context): Int =
    if (context.isDarkMode()) DesignTokens.surfaceOverlayDark else DesignTokens.surfaceOverlay

fun surfaceBorderColor(context: Context): Int =
    if (context.isDarkMode()) DesignTokens.surfaceBorderDark else DesignTokens.surfaceBorder

fun textPrimaryColor(context: Context): Int =
    if (context.isDarkMode()) DesignTokens.textPrimaryDark else DesignTokens.textPrimary

fun textSecondaryColor(context: Context): Int =
    if (context.isDarkMode()) DesignTokens.textSecondaryDark else DesignTokens.textSecondary

fun backdropColor(context: Context): Int =
    if (context.isDarkMode()) 0x8C000000.toInt() else 0x52000000.toInt()

fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()

fun Float.dpToPx(context: Context): Float =
    this * context.resources.displayMetrics.density

fun Int.spToPx(context: Context): Float =
    this * context.resources.displayMetrics.scaledDensity

fun View.applyRipple(color: Int = DesignTokens.brandPrimary) {
    val states = ColorStateList.valueOf(color and 0x33FFFFFF.toInt())
    background = RippleDrawable(states, background, null)
}

fun packageToColor(pkg: String): Int = when {
    pkg.contains("linkedin") -> Color.parseColor("#0A66C2")
    pkg.contains("swiggy") -> Color.parseColor("#FC8019")
    pkg.contains("zomato") -> Color.parseColor("#E23744")
    pkg.contains("goibibo") -> Color.parseColor("#E63946")
    pkg.contains("makemytrip") -> Color.parseColor("#1A5276")
    pkg.contains("gmail") -> Color.parseColor("#EA4335")
    pkg.contains("maps") -> Color.parseColor("#34A853")
    pkg.contains("whatsapp") -> Color.parseColor("#25D366")
    pkg.contains("youtube") -> Color.parseColor("#FF0000")
    pkg.contains("amazon") -> Color.parseColor("#FF9900")
    pkg.contains("flipkart") -> Color.parseColor("#2874F0")
    pkg.contains("instagram") -> Color.parseColor("#E1306C")
    pkg.contains("twitter") -> Color.parseColor("#1DA1F2")
    pkg.contains("airbnb") -> Color.parseColor("#FF5A5F")
    pkg.contains("expedia") -> Color.parseColor("#00355F")
    pkg.contains("naukri") -> Color.parseColor("#4A90D9")
    pkg.contains("indeed") -> Color.parseColor("#2557A7")
    else -> DesignTokens.brandPrimary
}

fun contextTypeLabel(typeName: String): String = when (typeName.uppercase()) {
    "JOB_POST" -> "Job post"
    "FOOD_ORDER" -> "Food order"
    "TRAVEL" -> "Flight search"
    "EMAIL" -> "Email"
    else -> "General"
}
