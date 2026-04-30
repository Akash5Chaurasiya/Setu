package com.contextai.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class UrlSummarizer @Inject constructor(
    @Named("summarizer") private val okHttpClient: OkHttpClient
) {
    suspend fun fetchAndExtract(url: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; ContextAI/1.0)")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@runCatching ""
            extractReadableText(html)
        }.getOrElse {
            Timber.w(it, "UrlSummarizer: fetch failed for $url")
            ""
        }
    }

    private fun extractReadableText(html: String): String =
        html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", setOf(RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", setOf(RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&nbsp;", " ")
            .replace("&#39;", "'").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(3000)
}
