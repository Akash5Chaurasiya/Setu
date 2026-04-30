package com.contextai.core

import com.contextai.domain.model.ScreenContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local event bus for inter-service communication.
 * Both FloatingBubbleService and ContextAIAccessibilityService share this singleton.
 */
object SharedContextBus {
    private val _contextRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contextRequest: SharedFlow<Unit> = _contextRequest.asSharedFlow()

    private val _contextResult = MutableSharedFlow<ScreenContext>(extraBufferCapacity = 1)
    val contextResult: SharedFlow<ScreenContext> = _contextResult.asSharedFlow()

    private val _currentContext = MutableStateFlow<ScreenContext?>(null)
    val currentContext: StateFlow<ScreenContext?> = _currentContext.asStateFlow()

    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    fun requestContext() {
        _contextRequest.tryEmit(Unit)
    }

    fun publishContext(context: ScreenContext) {
        _currentContext.value = context
        _contextResult.tryEmit(context)
    }

    fun setAccessibilityEnabled(enabled: Boolean) {
        _accessibilityEnabled.value = enabled
    }
}
