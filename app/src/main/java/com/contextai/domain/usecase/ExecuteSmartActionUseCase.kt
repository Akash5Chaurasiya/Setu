package com.contextai.domain.usecase

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.contextai.data.local.ConversationDao
import com.contextai.domain.model.ConversationEntity
import com.contextai.domain.model.ContextType
import com.contextai.domain.model.ScreenContext
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

enum class SmartAction {
    COPY, SHARE, OPEN_GMAIL, ADD_CALENDAR, SAVE_HISTORY
}

/**
 * Executes post-response smart actions (copy, share, Gmail, calendar, save).
 */
class ExecuteSmartActionUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationDao: ConversationDao
) {
    suspend fun execute(
        action: SmartAction,
        response: String,
        query: String,
        screenContext: ScreenContext
    ) {
        when (action) {
            SmartAction.COPY -> copyToClipboard(response)
            SmartAction.SHARE -> shareText(response)
            SmartAction.OPEN_GMAIL -> openGmail(response, screenContext)
            SmartAction.ADD_CALENDAR -> addToCalendar(response, screenContext)
            SmartAction.SAVE_HISTORY -> saveHistory(query, response, screenContext)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ContextAI", text))
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openGmail(response: String, ctx: ScreenContext) {
        val draft = parseEmailDraft(response, ctx.detectedEmails.firstOrNull())

        // Primary: mailto: URI with all fields — Gmail and most email clients honour this
        val mailtoUri = buildString {
            append("mailto:")
            append(Uri.encode(draft.to))
            append("?subject="); append(Uri.encode(draft.subject))
            append("&body="); append(Uri.encode(draft.body))
        }.let { Uri.parse(it) }

        val primaryIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Fallback: ACTION_SEND with chooser
        val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            if (draft.to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(draft.to))
            putExtra(Intent.EXTRA_SUBJECT, draft.subject)
            putExtra(Intent.EXTRA_TEXT, draft.body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pm = context.packageManager
        when {
            primaryIntent.resolveActivity(pm) != null ->
                runCatching { context.startActivity(primaryIntent) }
                    .onFailure { Timber.e(it, "Gmail mailto failed") }
            fallbackIntent.resolveActivity(pm) != null -> {
                val chooser = Intent.createChooser(fallbackIntent, "Send email via")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(chooser) }
                    .onFailure { Timber.e(it, "Gmail chooser failed") }
            }
            else -> Timber.w("No email client found")
        }
    }

    private data class EmailDraft(val to: String, val subject: String, val body: String)

    private fun parseEmailDraft(response: String, fallbackEmail: String?): EmailDraft {
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

        val body = lines.drop(bodyStart).dropWhile { it.isBlank() }.joinToString("\n").trim()
        return EmailDraft(to = to, subject = subject, body = body)
    }

    private fun addToCalendar(response: String, ctx: ScreenContext) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, ctx.appName)
            putExtra(CalendarContract.Events.DESCRIPTION, response.take(500))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure { Timber.e(it, "Calendar open failed") }
    }

    private suspend fun saveHistory(query: String, response: String, ctx: ScreenContext) {
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
        }.onFailure { Timber.e(it, "Failed to save history") }
    }
}
