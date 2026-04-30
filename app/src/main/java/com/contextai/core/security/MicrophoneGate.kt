package com.contextai.core.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrophoneGate @Inject constructor() {

    companion object {
        const val MAX_RECORDING_MS = 60_000L
    }

    @Volatile private var isRecording = false
    @Volatile private var recordingStartTime = 0L

    fun open(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Timber.w("MicrophoneGate: RECORD_AUDIO not granted")
            return false
        }
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        Timber.d("MicrophoneGate: opened")
        return true
    }

    fun close() {
        isRecording = false
        Timber.d("MicrophoneGate: closed")
    }

    fun isOpen(): Boolean = isRecording

    fun hasExceededLimit(): Boolean {
        if (!isRecording) return false
        return System.currentTimeMillis() - recordingStartTime > MAX_RECORDING_MS
    }

    fun enforceLimit(onLimitExceeded: () -> Unit) {
        if (hasExceededLimit()) {
            close()
            onLimitExceeded()
        }
    }
}
