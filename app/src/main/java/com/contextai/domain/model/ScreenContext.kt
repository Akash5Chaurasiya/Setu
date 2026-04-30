package com.contextai.domain.model

/**
 * Structured representation of the currently visible screen content,
 * captured by the AccessibilityService on demand.
 */
data class ScreenContext(
    val appPackage: String,
    val appName: String,
    val activityTitle: String,
    val rawText: String,
    val detectedEmails: List<String>,
    val detectedUrls: List<String>,
    val detectedType: ContextType,
    val keyEntities: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false
) {
    fun isEmpty(): Boolean = rawText.isBlank()

    fun toSystemPrompt(): String = buildString {
        append("App: $appName | ${detectedType.displayName}")
        if (keyEntities.isNotEmpty()) {
            append(" | ${keyEntities.entries.joinToString(", ") { "${it.key}: ${it.value}" }}")
        }
        if (detectedEmails.isNotEmpty()) append(" | emails: ${detectedEmails.first()}")
        appendLine()
        appendLine("Screen:")
        appendLine(compressedRawText())
        append("Reply concisely.")
    }

    private fun compressedRawText(): String {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
        val joined = lines.joinToString("\n")
        return if (joined.length > 1500) joined.take(1500) + "…" else joined
    }
}

val EmptyScreenContext = ScreenContext(
    appPackage = "",
    appName = "Unknown",
    activityTitle = "",
    rawText = "",
    detectedEmails = emptyList(),
    detectedUrls = emptyList(),
    detectedType = ContextType.GENERIC,
    keyEntities = emptyMap()
)

fun blockedScreenContext(packageName: String, appName: String) = ScreenContext(
    appPackage = packageName,
    appName = appName,
    activityTitle = "",
    rawText = "",
    detectedEmails = emptyList(),
    detectedUrls = emptyList(),
    detectedType = ContextType.GENERIC,
    keyEntities = emptyMap(),
    isBlocked = true
)
