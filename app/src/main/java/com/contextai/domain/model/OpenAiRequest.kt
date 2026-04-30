package com.contextai.domain.model

import com.google.gson.annotations.SerializedName

data class OpenAiRequest(
    @SerializedName("model") val model: String = "gpt-4o-mini",
    @SerializedName("max_tokens") val maxTokens: Int = 1024,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("messages") val messages: List<ClaudeMessage>
)

data class OpenAiStreamEvent(
    @SerializedName("choices") val choices: List<OpenAiChoice>?
)

data class OpenAiChoice(
    @SerializedName("delta") val delta: OpenAiDelta?
)

data class OpenAiDelta(
    @SerializedName("content") val content: String?
)
