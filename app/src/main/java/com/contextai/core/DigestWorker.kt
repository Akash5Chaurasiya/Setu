package com.contextai.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.contextai.R
import com.contextai.data.local.ConversationDao
import com.contextai.domain.model.ContextType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val conversationDao: ConversationDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val yesterday = System.currentTimeMillis() - 86_400_000L
        val recentConvos = conversationDao.getConversationsSince(yesterday)

        if (recentConvos.isEmpty()) return Result.success()

        val count = recentConvos.size
        val topApp = recentConvos
            .groupBy { it.appName }
            .maxByOrNull { it.value.size }?.key ?: "various apps"
        val tip = getTipOfTheDay(recentConvos.map { it.contextType }.toSet())

        ensureChannel()
        showNotification(count, topApp, tip)
        return Result.success()
    }

    private fun getTipOfTheDay(usedTypes: Set<ContextType>): String = when {
        ContextType.JOB_POST !in usedTypes ->
            "Tip: Try tapping the bubble on a LinkedIn job post to draft an email instantly"
        ContextType.TRAVEL !in usedTypes ->
            "Tip: On Goibibo or MakeMyTrip, ask AI to plan your full trip budget"
        ContextType.FOOD_ORDER !in usedTypes ->
            "Tip: On Swiggy, ask AI to estimate calories before you order"
        else ->
            "Tip: Hold the bubble to open quick actions without opening the full panel"
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Daily Digest", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Daily usage summary from ContextAI"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun showNotification(count: Int, topApp: String, tip: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble_notification)
            .setContentTitle("ContextAI helped you $count times yesterday")
            .setContentText("Most used with $topApp")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Most used with $topApp.\n\n$tip")
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(DIGEST_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "contextai_digest"
        const val DIGEST_NOTIFICATION_ID = 1002
        const val WORK_NAME = "daily_digest"
    }
}
