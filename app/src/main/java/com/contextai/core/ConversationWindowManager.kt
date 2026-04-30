package com.contextai.core

import com.contextai.domain.model.ClaudeMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationWindowManager @Inject constructor() {

    private val messages = mutableListOf<ClaudeMessage>()
    private var windowTokens = 0

    private val _sessionTokensUsed = MutableStateFlow(0)
    val sessionTokensUsed: StateFlow<Int> = _sessionTokensUsed.asStateFlow()

    companion object {
        private const val TOKEN_BUDGET = 2000
        fun estimateTokens(text: String): Int = maxOf(1, text.length / 4)
    }

    fun reset() {
        messages.clear()
        windowTokens = 0
    }

    fun addUserMessage(content: String) {
        val tokens = estimateTokens(content)
        messages.add(ClaudeMessage(role = "user", content = content))
        windowTokens += tokens
        _sessionTokensUsed.value += tokens
        trimToFit()
    }

    fun addAssistantMessage(content: String) {
        val tokens = estimateTokens(content)
        messages.add(ClaudeMessage(role = "assistant", content = content))
        windowTokens += tokens
        _sessionTokensUsed.value += tokens
        trimToFit()
    }

    fun getMessages(): List<ClaudeMessage> = messages.toList()

    fun isFollowUp(): Boolean = messages.isNotEmpty()

    private fun trimToFit() {
        while (windowTokens > TOKEN_BUDGET && messages.size > 2) {
            val removed = messages.removeAt(0)
            windowTokens -= estimateTokens(removed.content)
        }
    }
}
