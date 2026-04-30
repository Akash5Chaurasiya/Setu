package com.contextai.core.network

import com.contextai.domain.model.ClaudeRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Streaming

interface ClaudeApiService {

    @Streaming
    @Headers(
        "anthropic-version: 2023-06-01",
        "content-type: application/json",
        "accept: text/event-stream"
    )
    @POST("v1/messages")
    suspend fun streamMessage(
        @Body request: ClaudeRequest
    ): Response<ResponseBody>
}
