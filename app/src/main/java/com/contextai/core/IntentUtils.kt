package com.contextai.core

import android.content.Intent

object IntentUtils {
    fun ensureNewTask(intent: Intent): Intent = intent.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
