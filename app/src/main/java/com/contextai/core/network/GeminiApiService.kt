package com.contextai.core.network

import com.contextai.domain.model.OpenAiRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Streaming

interface GeminiApiService {

    @Streaming
    @Headers(
        "content-type: application/json",
        "accept: text/event-stream"
    )
    @POST("chat/completions")
    suspend fun streamMessage(
        @Body request: OpenAiRequest
    ): Response<ResponseBody>
}
