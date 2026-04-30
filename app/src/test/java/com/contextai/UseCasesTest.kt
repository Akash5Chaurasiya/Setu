package com.contextai

import com.contextai.core.network.ClaudeRepository
import com.contextai.data.preferences.SecurePreferencesManager
import com.contextai.domain.model.ApiResult
import com.contextai.domain.model.ContextType
import com.contextai.domain.model.ScreenContext
import com.contextai.domain.usecase.SendToClaudeUseCase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UseCasesTest {

    private lateinit var repository: ClaudeRepository
    private lateinit var prefsManager: SecurePreferencesManager
    private lateinit var sendToClaudeUseCase: SendToClaudeUseCase

    private val testContext = ScreenContext(
        appPackage = "com.example",
        appName = "Example",
        activityTitle = "Activity",
        rawText = "Sample text",
        detectedEmails = emptyList(),
        detectedUrls = emptyList(),
        detectedType = ContextType.GENERIC,
        keyEntities = emptyMap()
    )

    @Before
    fun setup() {
        repository = mock()
        prefsManager = mock()
        sendToClaudeUseCase = SendToClaudeUseCase(repository, prefsManager)
    }

    @Test
    fun `returns error when no API key configured`() = runTest {
        whenever(prefsManager.hasApiKey()).thenReturn(false)

        val results = sendToClaudeUseCase("test query", testContext).toList()

        assertTrue(results.isNotEmpty())
        val error = results.first()
        assertTrue(error is ApiResult.Error)
        assertTrue((error as ApiResult.Error).message.contains("API key", ignoreCase = true))
        verify(repository, never()).streamResponse(any(), any())
    }

    @Test
    fun `delegates to repository when API key is present`() = runTest {
        whenever(prefsManager.hasApiKey()).thenReturn(true)
        whenever(repository.streamResponse(any(), any())).thenReturn(
            flowOf(ApiResult.Success("Hello"), ApiResult.Success(" World"))
        )

        val results = sendToClaudeUseCase("test query", testContext).toList()

        assertTrue(results.size == 2)
        assertTrue(results[0] is ApiResult.Success)
        assertTrue(results[1] is ApiResult.Success)
        verify(repository).streamResponse("test query", testContext)
    }

    @Test
    fun `propagates repository errors`() = runTest {
        whenever(prefsManager.hasApiKey()).thenReturn(true)
        whenever(repository.streamResponse(any(), any())).thenReturn(
            flowOf(ApiResult.Error("Network error"))
        )

        val results = sendToClaudeUseCase("test query", testContext).toList()

        assertTrue(results.first() is ApiResult.Error)
    }
}
