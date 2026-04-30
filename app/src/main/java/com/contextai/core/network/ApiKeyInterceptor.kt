package com.contextai.core.network

import com.contextai.data.preferences.SecurePreferencesManager
import com.contextai.domain.model.AiProvider
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject

class ApiKeyInterceptor @Inject constructor(
    private val prefsManager: SecurePreferencesManager
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val provider = prefsManager.getProvider()
        val key = prefsManager.getApiKey(provider)
        val request = chain.request()

        val contentLength = request.body?.contentLength() ?: -1L
        val estimatedTokens = if (contentLength > 0) contentLength / 4 else 0L
        Timber.d("→ %s | body %d bytes | ~%d input tokens", provider.name, contentLength, estimatedTokens)

        val builder = request.newBuilder()
        when (provider) {
            AiProvider.ANTHROPIC -> builder.addHeader("x-api-key", key)
            AiProvider.OPENAI, AiProvider.GEMINI, AiProvider.GROQ ->
                builder.addHeader("Authorization", "Bearer $key")
        }
        return chain.proceed(builder.build())
    }
}
