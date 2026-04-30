package com.contextai.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.contextai.domain.model.AiProvider
import com.contextai.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurePreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val plainPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PLAIN_PREFS_FILE, Context.MODE_PRIVATE)
    }

    private val deviceSalt: String by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "default"
        val installTime = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime.toString()
        }.getOrDefault("0")
        (androidId + installTime).take(32).padEnd(32, '0')
    }

    private fun xorEncode(input: String, key: String): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        return input.toByteArray(Charsets.UTF_8)
            .mapIndexed { i, b -> (b.toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() }
            .let { android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP) }
    }

    private fun xorDecode(encoded: String, key: String): String {
        return runCatching {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                .mapIndexed { i, b -> (b.toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() }
                .toByteArray().toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun saveApiKey(provider: AiProvider, apiKey: String) {
        // Migrate any existing plain key first, then save split
        val baseKey = keyForProvider(provider)
        val salt = deviceSalt
        val half = apiKey.length / 2
        val part1 = xorEncode(apiKey.substring(0, half), salt)
        val part2 = xorEncode(apiKey.substring(half), salt.reversed())
        encryptedPrefs.edit()
            .putString("k1_$baseKey", part1)
            .putString("k2_$baseKey", part2)
            .remove(baseKey) // remove legacy plain key
            .apply()
    }

    fun getApiKey(provider: AiProvider): String {
        val baseKey = keyForProvider(provider)
        val salt = deviceSalt
        val part1Enc = encryptedPrefs.getString("k1_$baseKey", null)
        val part2Enc = encryptedPrefs.getString("k2_$baseKey", null)
        if (part1Enc != null && part2Enc != null) {
            val part1 = xorDecode(part1Enc, salt)
            val part2 = xorDecode(part2Enc, salt.reversed())
            return part1 + part2
        }
        // Legacy fallback — migrate on next save
        return encryptedPrefs.getString(baseKey, "") ?: ""
    }

    fun hasApiKey(provider: AiProvider): Boolean = getApiKey(provider).isNotBlank()

    fun getApiKey(): String = getApiKey(getProvider())

    fun hasApiKey(): Boolean = hasApiKey(getProvider())

    private fun keyForProvider(provider: AiProvider) = when (provider) {
        AiProvider.ANTHROPIC -> KEY_ANTHROPIC_API_KEY
        AiProvider.OPENAI -> KEY_OPENAI_API_KEY
        AiProvider.GEMINI -> KEY_GEMINI_API_KEY
        AiProvider.GROQ -> KEY_GROQ_API_KEY
    }

    fun saveUserProfile(profile: UserProfile) {
        encryptedPrefs.edit()
            .putString(KEY_USER_NAME, profile.name)
            .putString(KEY_USER_ROLE, profile.currentRole)
            .putString(KEY_USER_SKILLS, profile.skills)
            .putString(KEY_USER_EXPERIENCE, profile.experience)
            .apply()
        // Migrate: clear any legacy plaintext values
        plainPrefs.edit()
            .remove(KEY_USER_NAME).remove(KEY_USER_ROLE)
            .remove(KEY_USER_SKILLS).remove(KEY_USER_EXPERIENCE)
            .apply()
    }

    fun getUserProfile(): UserProfile = UserProfile(
        name = encryptedPrefs.getString(KEY_USER_NAME, "") ?: "",
        currentRole = encryptedPrefs.getString(KEY_USER_ROLE, "") ?: "",
        skills = encryptedPrefs.getString(KEY_USER_SKILLS, "") ?: "",
        experience = encryptedPrefs.getString(KEY_USER_EXPERIENCE, "") ?: ""
    )

    fun setShowBubbleOnLockScreen(show: Boolean) {
        plainPrefs.edit().putBoolean(KEY_SHOW_ON_LOCK_SCREEN, show).apply()
    }

    fun getShowBubbleOnLockScreen(): Boolean =
        plainPrefs.getBoolean(KEY_SHOW_ON_LOCK_SCREEN, false)

    fun setAutoDetectContext(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_AUTO_DETECT, enabled).apply()
    }

    fun getAutoDetectContext(): Boolean =
        plainPrefs.getBoolean(KEY_AUTO_DETECT, true)

    fun setHapticEnabled(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    fun isHapticEnabled(): Boolean = plainPrefs.getBoolean(KEY_HAPTIC_ENABLED, true)

    fun saveResumeText(text: String) {
        encryptedPrefs.edit().putString(KEY_RESUME_TEXT, text).apply()
    }

    fun getResumeText(): String = encryptedPrefs.getString(KEY_RESUME_TEXT, "") ?: ""

    fun setSaveHistory(enabled: Boolean) {
        plainPrefs.edit().putBoolean(KEY_SAVE_HISTORY, enabled).apply()
    }

    fun isSaveHistoryEnabled(): Boolean = plainPrefs.getBoolean(KEY_SAVE_HISTORY, true)

    fun saveProvider(provider: AiProvider) {
        plainPrefs.edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun getProvider(): AiProvider {
        val name = plainPrefs.getString(KEY_PROVIDER, AiProvider.GROQ.name)
        return runCatching { AiProvider.valueOf(name ?: "") }.getOrDefault(AiProvider.GROQ)
    }

    fun setOnboardingComplete(complete: Boolean) {
        plainPrefs.edit().putBoolean(KEY_ONBOARDING_DONE, complete).apply()
    }

    fun isOnboardingComplete(): Boolean =
        plainPrefs.getBoolean(KEY_ONBOARDING_DONE, false)

    companion object {
        private const val ENCRYPTED_PREFS_FILE = "contextai_secure_prefs"
        private const val PLAIN_PREFS_FILE = "contextai_prefs"
        private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_USER_SKILLS = "user_skills"
        private const val KEY_USER_EXPERIENCE = "user_experience"
        private const val KEY_SHOW_ON_LOCK_SCREEN = "show_on_lock_screen"
        private const val KEY_AUTO_DETECT = "auto_detect_context"
        private const val KEY_ONBOARDING_DONE = "onboarding_complete"
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        private const val KEY_RESUME_TEXT = "resume_text"
        private const val KEY_SAVE_HISTORY = "save_history"
    }
}
