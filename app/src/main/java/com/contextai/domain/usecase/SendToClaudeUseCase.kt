package com.contextai.domain.usecase

import com.contextai.core.network.ClaudeRepository
import com.contextai.core.security.SensitiveAppFilter
import com.contextai.data.preferences.SecurePreferencesManager
import com.contextai.domain.model.ApiResult
import com.contextai.domain.model.ScreenContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Sends a user message plus screen context to Claude and returns a streaming Flow.
 * Validates API key presence before making the network call.
 */
class SendToClaudeUseCase @Inject constructor(
    private val repository: ClaudeRepository,
    private val prefsManager: SecurePreferencesManager
) {
    operator fun invoke(
        userMessage: String,
        screenContext: ScreenContext
    ): Flow<ApiResult<String>> {
        if (!prefsManager.hasApiKey()) {
            return flow {
                emit(ApiResult.Error("No API key configured. Please add your Claude API key in Settings."))
            }
        }
        if (screenContext.isBlocked) {
            return flow {
                emit(ApiResult.Error("ContextAI does not read sensitive apps for your privacy."))
            }
        }
        val safeMessage = SensitiveAppFilter.sanitizeText(userMessage)
        return repository.streamResponse(safeMessage, screenContext)
    }
}
