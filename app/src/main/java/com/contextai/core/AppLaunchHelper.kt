package com.contextai.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import timber.log.Timber

object AppLaunchHelper {

    private val appNameToPackage = mapOf(
        "swiggy" to "in.swiggy.android",
        "zomato" to "com.application.zomato",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "linkedin" to "com.linkedin.android",
        "goibibo" to "com.goibibo",
        "makemytrip" to "com.makemytrip",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "phonepe" to "com.phonepe.app",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "google pay" to "com.google.android.apps.nbu.paisa.user",
        "paytm" to "net.one97.paytm",
        "instagram" to "com.instagram.android",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "spotify" to "com.spotify.music",
        "chrome" to "com.android.chrome",
        "settings" to "com.android.settings",
        "calculator" to "com.android.calculator2",
        "calendar" to "com.google.android.calendar",
        "contacts" to "com.android.contacts",
        "messages" to "com.google.android.apps.messaging",
        "photos" to "com.google.android.apps.photos",
        "drive" to "com.google.android.apps.docs",
        "google drive" to "com.google.android.apps.docs",
        "meet" to "com.google.android.apps.tachyon",
        "zoom" to "us.zoom.videomeetings",
        "uber" to "com.ubercab",
        "ola" to "com.olacabs.customer",
        "rapido" to "com.rapido.passenger",
        "blinkit" to "com.grofers.customerapp",
        "zepto" to "com.zeptoconsumer",
        "myntra" to "com.myntra.android",
        "meesho" to "com.meesho.supply",
        "hotstar" to "in.startv.hotstar",
        "jiocinema" to "com.jio.jiocinema",
        "netflix" to "com.netflix.mediaclient"
    )

    fun openApp(context: Context, appNameOrPackage: String): Boolean {
        val pkg = resolvePackage(context, appNameOrPackage) ?: return false
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to open app: $pkg")
            false
        }
    }

    private fun resolvePackage(context: Context, input: String): String? {
        val lower = input.lowercase().trim()
        if (lower.contains(".") && isInstalled(context, lower)) return lower
        appNameToPackage[lower]?.let { if (isInstalled(context, it)) return it }
        appNameToPackage.entries
            .firstOrNull { lower.contains(it.key) || it.key.contains(lower) }
            ?.let { if (isInstalled(context, it.value)) return it.value }
        return searchInstalledApps(context, lower)
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) { false }

    private fun searchInstalledApps(context: Context, query: String): String? {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .firstOrNull { app ->
                val label = pm.getApplicationLabel(app).toString().lowercase()
                label.contains(query) || query.contains(label)
            }?.packageName
    }

    fun extractAppOpenIntent(text: String): String? {
        val patterns = listOf(
            Regex("open\\s+([\\w\\s]+?)(?:\\s+app)?(?:\\s*$|\\s*[.,!])", RegexOption.MULTILINE),
            Regex("launch\\s+([\\w\\s]+?)(?:\\s+app)?(?:\\s*$|\\s*[.,!])", RegexOption.MULTILINE),
            Regex("opening\\s+([\\w\\s]+?)(?:\\s+app)?(?:\\s*$|\\s*[.,!])", RegexOption.MULTILINE)
        )
        for (pattern in patterns) {
            val match = pattern.find(text.lowercase()) ?: continue
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotBlank() && candidate.length < 30) return candidate
        }
        return null
    }
}
