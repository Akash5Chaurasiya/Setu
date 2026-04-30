package com.contextai.domain.model

import com.google.gson.annotations.SerializedName

data class ClaudeRequest(
    @SerializedName("model") val model: String = "claude-haiku-4-5-20251001",
    @SerializedName("max_tokens") val maxTokens: Int = 1024,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("system") val system: String,
    @SerializedName("messages") val messages: List<ClaudeMessage>
)

data class ClaudeMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class ClaudeStreamEvent(
    @SerializedName("type") val type: String,
    @SerializedName("index") val index: Int? = null,
    @SerializedName("delta") val delta: ContentDelta? = null
)

data class ContentDelta(
    @SerializedName("type") val type: String,
    @SerializedName("text") val text: String? = null
)

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : ApiResult<Nothing>()
}
