package com.contextai.core.security

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilityGate @Inject constructor() {

    companion object {
        const val ACTIVE_WINDOW_MS = 30_000L
    }

    @Volatile private var lastActivationTime = 0L
    @Volatile private var isGateOpen = false

    fun open() {
        lastActivationTime = System.currentTimeMillis()
        isGateOpen = true
        Timber.d("AccessibilityGate: opened")
    }

    fun close() {
        isGateOpen = false
        Timber.d("AccessibilityGate: closed")
    }

    fun isAllowedToRead(): Boolean {
        if (!isGateOpen) return false
        val elapsed = System.currentTimeMillis() - lastActivationTime
        if (elapsed > ACTIVE_WINDOW_MS) {
            isGateOpen = false
            Timber.d("AccessibilityGate: expired after ${elapsed}ms")
            return false
        }
        return true
    }

    fun remainingMs(): Long {
        if (!isGateOpen) return 0L
        val elapsed = System.currentTimeMillis() - lastActivationTime
        return (ACTIVE_WINDOW_MS - elapsed).coerceAtLeast(0L)
    }

    fun remainingFraction(): Float {
        if (!isGateOpen) return 0f
        return (remainingMs().toFloat() / ACTIVE_WINDOW_MS).coerceIn(0f, 1f)
    }

    fun isOpen(): Boolean = isGateOpen
}
