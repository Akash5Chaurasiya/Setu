package com.contextai.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.contextai.R
import com.contextai.core.DigestWorker
import com.contextai.core.HapticEngine
import com.contextai.core.overlay.FloatingBubbleService
import com.contextai.core.consent.ConsentManager
import com.contextai.data.preferences.SecurePreferencesManager
import com.contextai.databinding.ActivityMainBinding
import com.contextai.presentation.consent.ConsentActivity
import com.contextai.presentation.onboarding.OnboardingActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var prefsManager: SecurePreferencesManager
    @Inject lateinit var consentManager: ConsentManager

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* system handles rationale; results visible in next onResume check */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            if (!consentManager.hasValidConsent()) {
                startActivity(Intent(this@MainActivity, ConsentActivity::class.java))
                finish()
                return@launch
            }
            if (!prefsManager.isOnboardingComplete()) {
                startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
                finish()
                return@launch
            }
            initMainUi()
        }
    }

    private fun initMainUi() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
        ensureBubbleServiceRunning()
        scheduleDigestWorker()
        HapticEngine.setEnabled(prefsManager.isHapticEnabled())

        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            navHostFragment.navController.navigate(R.id.settingsFragment)
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val missing = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isEmpty()) return

        val descriptions = missing.map { perm ->
            when (perm) {
                Manifest.permission.RECORD_AUDIO ->
                    "• Microphone — enables voice input on the floating bubble"
                Manifest.permission.POST_NOTIFICATIONS ->
                    "• Notifications — sends your daily activity digest at 9 AM"
                else -> "• ${perm.substringAfterLast('.')}"
            }
        }.joinToString("\n")

        MaterialAlertDialogBuilder(this)
            .setTitle("Enable features")
            .setMessage("Grant these permissions to unlock full ContextAI functionality:\n\n$descriptions")
            .setPositiveButton("Grant") { _, _ ->
                permissionLauncher.launch(missing.toTypedArray())
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)
    }

    private fun ensureBubbleServiceRunning() {
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, FloatingBubbleService::class.java))
        }
    }

    private fun scheduleDigestWorker() {
        val request = PeriodicWorkRequestBuilder<DigestWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(calculateDelayUntil9AM(), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DigestWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun calculateDelayUntil9AM(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }
}
