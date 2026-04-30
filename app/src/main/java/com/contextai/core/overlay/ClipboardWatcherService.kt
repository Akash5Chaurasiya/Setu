package com.contextai.core.overlay

import android.content.ClipboardManager
import android.content.Context
import android.util.Patterns

class ClipboardWatcherService(
    private val context: Context,
    private val onNewClip: (label: String, type: ClipType, content: String) -> Unit
) {
    enum class ClipType { EMAIL, URL, PHONE, ADDRESS, LONG_TEXT, SHORT_TEXT }

    private val clipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private var lastClipText = ""
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    fun startWatching() {
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener
            if (text == lastClipText || text.isBlank()) return@OnPrimaryClipChangedListener
            lastClipText = text
            handleNewClip(text)
        }
        clipboardManager.addPrimaryClipChangedListener(listener!!)
    }

    fun stopWatching() {
        listener?.let { clipboardManager.removePrimaryClipChangedListener(it) }
        listener = null
    }

    private fun handleNewClip(text: String) {
        val type = detectClipType(text)
        val label = when (type) {
            ClipType.EMAIL -> "Draft email to ${text.take(28)}?"
            ClipType.URL -> "Summarize this link?"
            ClipType.PHONE -> "Look up this number?"
            ClipType.ADDRESS -> "Open in Maps?"
            ClipType.LONG_TEXT -> "Summarize copied text?"
            ClipType.SHORT_TEXT -> return
        }
        onNewClip(label, type, text)
    }

    private fun detectClipType(text: String): ClipType {
        val trimmed = text.trim()
        return when {
            Patterns.EMAIL_ADDRESS.matcher(trimmed).matches() -> ClipType.EMAIL
            Patterns.WEB_URL.matcher(trimmed).matches() -> ClipType.URL
            Patterns.PHONE.matcher(trimmed).matches() -> ClipType.PHONE
            trimmed.length > 200 -> ClipType.LONG_TEXT
            trimmed.contains(" ") && trimmed.length > 40 -> ClipType.LONG_TEXT
            else -> ClipType.SHORT_TEXT
        }
    }
}
