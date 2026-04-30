package com.contextai

import com.contextai.core.network.ClaudeApiService
import com.contextai.core.network.ClaudeRepository
import com.contextai.core.network.StreamingResponseHandler
import com.contextai.domain.model.ApiResult
import com.contextai.domain.model.ContextType
import com.contextai.domain.model.ScreenContext
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.Response

class ClaudeRepositoryTest {

    private lateinit var apiService: ClaudeApiService
    private lateinit var streamingHandler: StreamingResponseHandler
    private lateinit var repository: ClaudeRepository

    private val testContext = ScreenContext(
        appPackage = "com.example",
        appName = "Example",
        activityTitle = "MainActivity",
        rawText = "Hello World",
        detectedEmails = emptyList(),
        detectedUrls = emptyList(),
        detectedType = ContextType.GENERIC,
        keyEntities = emptyMap()
    )

    @Before
    fun setup() {
        apiService = mock()
        streamingHandler = StreamingResponseHandler(GsonBuilder().create())
        repository = ClaudeRepository(apiService, streamingHandler)
    }

    @Test
    fun `returns error when API returns 401`() = runTest {
        val errorResponse = Response.error<okhttp3.ResponseBody>(
            401,
            "Unauthorized".toResponseBody("application/json".toMediaType())
        )
        whenever(apiService.streamMessage(any())).thenReturn(errorResponse)

        val results = repository.streamResponse("test", testContext).toList()
        assertTrue(results.isNotEmpty())
        val error = results.first()
        assertTrue(error is ApiResult.Error)
        assertTrue((error as ApiResult.Error).message.contains("API key", ignoreCase = true))
    }

    @Test
    fun `returns error when API returns 429`() = runTest {
        val errorResponse = Response.error<okhttp3.ResponseBody>(
            429,
            "Too Many Requests".toResponseBody("application/json".toMediaType())
        )
        whenever(apiService.streamMessage(any())).thenReturn(errorResponse)

        val results = repository.streamResponse("test", testContext).toList()
        val error = results.first()
        assertTrue(error is ApiResult.Error)
        assertTrue((error as ApiResult.Error).message.contains("Rate limit", ignoreCase = true))
    }

    @Test
    fun `parses SSE stream correctly`() = runTest {
        val sseData = buildString {
            appendLine("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}")
            appendLine()
            appendLine("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" World\"}}")
            appendLine()
            appendLine("data: {\"type\":\"message_stop\"}")
            appendLine()
        }

        val responseBody = sseData.toResponseBody("text/event-stream".toMediaType())
        val chunks = streamingHandler.parseStream(responseBody).toList()

        assertEquals(2, chunks.size)
        assertEquals("Hello", chunks[0])
        assertEquals(" World", chunks[1])
    }

    @Test
    fun `SSE parser skips non-delta events`() = runTest {
        val sseData = buildString {
            appendLine("data: {\"type\":\"message_start\",\"message\":{\"id\":\"123\"}}")
            appendLine()
            appendLine("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
            appendLine()
            appendLine("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"AI response\"}}")
            appendLine()
            appendLine("data: {\"type\":\"message_stop\"}")
        }

        val responseBody = sseData.toResponseBody("text/event-stream".toMediaType())
        val chunks = streamingHandler.parseStream(responseBody).toList()

        assertEquals(1, chunks.size)
        assertEquals("AI response", chunks[0])
    }
}
