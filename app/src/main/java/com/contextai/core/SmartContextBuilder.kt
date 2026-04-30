package com.contextai.core

import com.contextai.data.preferences.SecurePreferencesManager
import com.contextai.domain.model.ContextType
import com.contextai.domain.model.ScreenContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartContextBuilder @Inject constructor(
    private val prefsManager: SecurePreferencesManager
) {

    fun build(ctx: ScreenContext, isFollowUp: Boolean, userQuery: String = ""): String {
        val detection = LanguageDetector.detect(userQuery)
        return if (isFollowUp) {
            buildString {
                appendUserIdentity()
                append("App: ${ctx.appName}. Reply concisely.")
                appendLanguageInstruction(detection)
            }
        } else {
            buildFullContext(ctx, detection)
        }
    }

    fun buildWithLanguage(
        ctx: ScreenContext,
        userQuery: String,
        detection: LanguageDetector.DetectionResult
    ): Pair<String, String> {
        val systemPrompt = buildFullContext(ctx, detection)
        return Pair(userQuery, systemPrompt)
    }

    private fun StringBuilder.appendLanguageInstruction(detection: LanguageDetector.DetectionResult?) {
        if (detection == null) return
        val langLabel = if (detection.isHinglish) "Hinglish (Hindi-English mix)" else "${detection.languageName} (${detection.nativeName})"
        appendLine()
        appendLine()
        append("IMPORTANT — Language: The user's message is in $langLabel.")
        appendLine()
        append("1. Write your complete response in ${detection.languageName} first.")
        appendLine()
        append("2. After your ${detection.languageName} response, add exactly this separator on its own line: ---")
        appendLine()
        append("3. Below the separator, write: English: [complete English translation of everything you wrote above]")
    }

    private fun StringBuilder.appendUserIdentity() {
        val profile = prefsManager.getUserProfile()
        val hasProfile = profile.name.isNotBlank() || profile.currentRole.isNotBlank()
        if (hasProfile) {
            append("You are assisting")
            if (profile.name.isNotBlank()) append(" ${profile.name}")
            if (profile.currentRole.isNotBlank()) append(", a ${profile.currentRole}")
            if (profile.skills.isNotBlank()) append(" with skills in ${profile.skills}")
            append(".")
            appendLine()
        }
        val resume = prefsManager.getResumeText()
        if (resume.length >= 40) {
            val letterRatio = resume.count { it.isLetter() }.toFloat() / resume.length
            if (letterRatio > 0.40f) {
                appendLine("Their resume/background (PDF-extracted — minor word-spacing artifacts possible; interpret character clusters as single words):")
                appendLine(resume.take(1500))
            }
        }
    }

    private fun buildFullContext(ctx: ScreenContext, lang: LanguageDetector.DetectionResult? = null): String = buildString {
        appendUserIdentity()
        append("App: ${ctx.appName} | ${ctx.detectedType.displayName}")
        val cappedEntities = ctx.keyEntities.entries.take(10)
        if (cappedEntities.isNotEmpty()) {
            append(" | ")
            append(cappedEntities.joinToString(", ") { "${it.key}: ${it.value.take(100)}" })
        }
        if (ctx.detectedEmails.isNotEmpty()) append(" | email: ${ctx.detectedEmails.first()}")
        appendLine()
        appendLine("Screen:")
        append(compressedText(ctx.rawText))
        appendLine()
        if (ctx.detectedType == ContextType.JOB_POST) {
            appendLine()
            append("If writing an email draft, begin your response with exactly:\nTO: [recipient email or UNKNOWN]\nSUBJECT: [subject line]\nThen write the email body.")
        } else {
            append("Reply concisely.")
        }
        appendLanguageInstruction(lang)
    }

    private fun compressedText(rawText: String): String {
        if (rawText.isBlank()) return ""
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
        val joined = lines.joinToString("\n")
        return if (joined.length > 1200) joined.take(1200) + "…" else joined
    }
}
