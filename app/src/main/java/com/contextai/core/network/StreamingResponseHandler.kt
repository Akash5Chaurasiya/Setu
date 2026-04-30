package com.contextai.core.network

import com.contextai.domain.model.AiProvider
import com.contextai.domain.model.ClaudeStreamEvent
import com.contextai.domain.model.OpenAiStreamEvent
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.ResponseBody
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamingResponseHandler @Inject constructor(
    private val gson: Gson
) {
    fun parseStream(body: ResponseBody, provider: AiProvider): Flow<String> = flow {
        val reader = body.charStream().buffered()
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.startsWith("data: ")) {
                    val json = trimmed.removePrefix("data: ").trim()
                    if (json == "[DONE]") break
                    val text = when (provider) {
                        AiProvider.ANTHROPIC -> extractAnthropicChunk(json)
                        AiProvider.OPENAI, AiProvider.GEMINI, AiProvider.GROQ -> extractOpenAiChunk(json)
                    }
                    if (text != null) emit(text)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error reading SSE stream")
            throw e
        } finally {
            runCatching { reader.close() }
            runCatching { body.close() }
        }
    }

    private fun extractAnthropicChunk(json: String): String? = try {
        val event = gson.fromJson(json, ClaudeStreamEvent::class.java)
        if (event.type == "content_block_delta" && event.delta?.type == "text_delta") {
            event.delta.text
        } else null
    } catch (e: JsonSyntaxException) {
        Timber.w("Skipping malformed Anthropic SSE event: $json")
        null
    }

    private fun extractOpenAiChunk(json: String): String? = try {
        val event = gson.fromJson(json, OpenAiStreamEvent::class.java)
        event.choices?.firstOrNull()?.delta?.content
    } catch (e: JsonSyntaxException) {
        Timber.w("Skipping malformed OpenAI SSE event: $json")
        null
    }
}
