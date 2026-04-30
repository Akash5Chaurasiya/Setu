package com.contextai.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object HapticEngine {

    private var enabled = true

    fun setEnabled(value: Boolean) { enabled = value }
    fun isEnabled(): Boolean = enabled

    fun bubbleTap(context: Context) = vibrate(context, 28L, 180)
    fun actionSelected(context: Context) = vibrate(context, 42L, 220)
    fun aiResponseStart(context: Context) = vibratePattern(
        context, longArrayOf(0, 20, 40, 20), intArrayOf(0, 160, 0, 80)
    )
    fun actionComplete(context: Context) = vibratePattern(
        context, longArrayOf(0, 30, 60, 50), intArrayOf(0, 200, 0, 255)
    )
    fun error(context: Context) = vibratePattern(
        context, longArrayOf(0, 50, 30, 50), intArrayOf(0, 255, 0, 180)
    )
    fun dismiss(context: Context) = vibrate(context, 18L, 120)
    fun shakeDetected(context: Context) = vibrate(context, 60L, 255)
    fun clipDetected(context: Context) = vibrate(context, 22L, 140)

    private fun vibrate(context: Context, duration: Long, amplitude: Int) {
        if (!enabled) return
        runCatching {
            getVibrator(context).vibrate(
                VibrationEffect.createOneShot(duration, amplitude.coerceIn(1, 255))
            )
        }
    }

    private fun vibratePattern(context: Context, timings: LongArray, amplitudes: IntArray) {
        if (!enabled) return
        runCatching {
            getVibrator(context).vibrate(
                VibrationEffect.createWaveform(
                    timings,
                    amplitudes.map { it.coerceIn(1, 255) }.toIntArray(),
                    -1
                )
            )
        }
    }

    private fun getVibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
