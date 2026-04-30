package com.contextai.core.network

import com.contextai.core.ConversationWindowManager
import com.contextai.core.SmartContextBuilder
import com.contextai.data.preferences.SecurePreferencesManager
import com.contextai.domain.model.AiProvider
import com.contextai.domain.model.ApiResult
import com.contextai.domain.model.ClaudeMessage
import com.contextai.domain.model.ClaudeRequest
import com.contextai.domain.model.OpenAiRequest
import com.contextai.domain.model.ScreenContext
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ClaudeRepository @Inject constructor(
    private val claudeApiService: ClaudeApiService,
    @Named("openai") private val openAiApiService: OpenAiApiService,
    @Named("groq") private val groqApiService: OpenAiApiService,
    private val geminiApiService: GeminiApiService,
    private val streamingHandler: StreamingResponseHandler,
    private val prefsManager: SecurePreferencesManager,
    private val conversationManager: ConversationWindowManager,
    private val smartContextBuilder: SmartContextBuilder
) {
    fun streamResponse(
        userMessage: String,
        screenContext: ScreenContext
    ): Flow<ApiResult<String>> {
        val isFollowUp = conversationManager.isFollowUp()
        conversationManager.addUserMessage(userMessage)
        val systemPrompt = smartContextBuilder.build(screenContext, isFollowUp, userMessage)
        val messages = conversationManager.getMessages()

        val baseFlow = when (prefsManager.getProvider()) {
            AiProvider.ANTHROPIC -> streamAnthropic(systemPrompt, messages)
            AiProvider.OPENAI -> streamOpenAi(systemPrompt, messages)
            AiProvider.GEMINI -> streamGemini(systemPrompt, messages)
            AiProvider.GROQ -> streamGroq(systemPrompt, messages)
        }
        return baseFlow.trackResponse()
    }

    private fun Flow<ApiResult<String>>.trackResponse(): Flow<ApiResult<String>> = flow {
        val sb = StringBuilder()
        collect { result ->
            if (result is ApiResult.Success) sb.append(result.data)
            emit(result)
        }
        if (sb.isNotBlank()) conversationManager.addAssistantMessage(sb.toString())
    }

    private fun parseApiError(code: Int, body: String?): String {
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("error")
                ?.get("message")?.asString
        }.getOrNull()
        return when {
            code == 429 && message?.contains("quota", ignoreCase = true) == true ->
                "Quota exceeded — check your billing/credits at the provider dashboard."
            code == 429 ->
                "Rate limit exceeded. Please wait and try again."
            message != null -> "Error $code: $message"
            else -> "API error $code"
        }
    }

    private fun streamAnthropic(
        systemPrompt: String,
        messages: List<ClaudeMessage>
    ): Flow<ApiResult<String>> {
        val request = ClaudeRequest(
            maxTokens = 4096,
            system = systemPrompt,
            messages = messages
        )
        return flow {
            val response = claudeApiService.streamMessage(request)
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body == null) {
                        emit(ApiResult.Error("Empty response body from Anthropic API"))
                        return@flow
                    }
                    streamingHandler.parseStream(body, AiProvider.ANTHROPIC).collect { chunk ->
                        emit(ApiResult.Success(chunk))
                    }
                }
                response.code() == 401 -> emit(ApiResult.Error("Invalid API key. Please update in Settings."))
                response.code() == 529 -> emit(ApiResult.Error("Claude API is overloaded. Please try again."))
                else -> {
                    val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                    emit(ApiResult.Error(parseApiError(response.code(), errorBody)))
                }
            }
        }.catch { e ->
            Timber.e(e, "Network error streaming Anthropic response")
            when (e) {
                is IOException -> emit(ApiResult.Error("Network error: ${e.message}. Check your connection.", e))
                else -> emit(ApiResult.Error("Unexpected error: ${e.message}", e))
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun streamOpenAi(
        systemPrompt: String,
        messages: List<ClaudeMessage>
    ): Flow<ApiResult<String>> {
        val request = OpenAiRequest(
            maxTokens = 4096,
            messages = listOf(ClaudeMessage(role = "system", content = systemPrompt)) + messages
        )
        return flow {
            val response = openAiApiService.streamMessage(request)
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body == null) {
                        emit(ApiResult.Error("Empty response body from OpenAI API"))
                        return@flow
                    }
                    streamingHandler.parseStream(body, AiProvider.OPENAI).collect { chunk ->
                        emit(ApiResult.Success(chunk))
                    }
                }
                response.code() == 401 -> emit(ApiResult.Error("Invalid API key. Please update in Settings."))
                else -> {
                    val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                    emit(ApiResult.Error(parseApiError(response.code(), errorBody)))
                }
            }
        }.catch { e ->
            Timber.e(e, "Network error streaming OpenAI response")
            when (e) {
                is IOException -> emit(ApiResult.Error("Network error: ${e.message}. Check your connection.", e))
                else -> emit(ApiResult.Error("Unexpected error: ${e.message}", e))
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun streamGemini(
        systemPrompt: String,
        messages: List<ClaudeMessage>
    ): Flow<ApiResult<String>> {
        val request = OpenAiRequest(
            model = "gemini-2.0-flash",
            maxTokens = 4096,
            messages = listOf(ClaudeMessage(role = "system", content = systemPrompt)) + messages
        )
        return flow {
            val response = geminiApiService.streamMessage(request)
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body == null) {
                        emit(ApiResult.Error("Empty response body from Gemini API"))
                        return@flow
                    }
                    streamingHandler.parseStream(body, AiProvider.OPENAI).collect { chunk ->
                        emit(ApiResult.Success(chunk))
                    }
                }
                response.code() == 401 -> emit(ApiResult.Error("Invalid API key. Please update in Settings."))
                else -> {
                    val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                    emit(ApiResult.Error(parseApiError(response.code(), errorBody)))
                }
            }
        }.catch { e ->
            Timber.e(e, "Network error streaming Gemini response")
            when (e) {
                is IOException -> emit(ApiResult.Error("Network error: ${e.message}. Check your connection.", e))
                else -> emit(ApiResult.Error("Unexpected error: ${e.message}", e))
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun streamGroq(
        systemPrompt: String,
        messages: List<ClaudeMessage>
    ): Flow<ApiResult<String>> {
        val request = OpenAiRequest(
            model = "llama-3.1-8b-instant",
            maxTokens = 4096,
            messages = listOf(ClaudeMessage(role = "system", content = systemPrompt)) + messages
        )
        return flow {
            val response = groqApiService.streamMessage(request)
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body == null) {
                        emit(ApiResult.Error("Empty response body from Groq API"))
                        return@flow
                    }
                    streamingHandler.parseStream(body, AiProvider.GROQ).collect { chunk ->
                        emit(ApiResult.Success(chunk))
                    }
                }
                response.code() == 401 -> emit(ApiResult.Error("Invalid API key. Please update in Settings."))
                else -> {
                    val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                    emit(ApiResult.Error(parseApiError(response.code(), errorBody)))
                }
            }
        }.catch { e ->
            Timber.e(e, "Network error streaming Groq response")
            when (e) {
                is IOException -> emit(ApiResult.Error("Network error: ${e.message}. Check your connection.", e))
                else -> emit(ApiResult.Error("Unexpected error: ${e.message}", e))
            }
        }.flowOn(Dispatchers.IO)
    }
}
