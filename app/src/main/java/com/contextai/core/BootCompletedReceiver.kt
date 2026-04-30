package com.contextai.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.contextai.core.overlay.FloatingBubbleService
import timber.log.Timber

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Timber.i("Boot/package-replaced — scheduling FloatingBubbleService start")
            Handler(Looper.getMainLooper()).postDelayed({
                val serviceIntent = Intent(context, FloatingBubbleService::class.java)
                context.startForegroundService(serviceIntent)
            }, 3000L)
        }
    }
}
