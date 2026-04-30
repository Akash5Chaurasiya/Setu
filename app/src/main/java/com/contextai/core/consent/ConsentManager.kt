package com.contextai.core.consent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConsentManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        // Increment when privacy policy changes materially — triggers re-consent
        const val CURRENT_POLICY_VERSION = 1

        val KEY_CONSENT_GIVEN = booleanPreferencesKey("consent_given")
        val KEY_CONSENT_VERSION = intPreferencesKey("consent_version")
        val KEY_CONSENT_TIMESTAMP = longPreferencesKey("consent_timestamp")
        val KEY_CONSENT_LOCALE = stringPreferencesKey("consent_locale")
    }

    suspend fun hasValidConsent(): Boolean {
        val prefs = dataStore.data.first()
        val given = prefs[KEY_CONSENT_GIVEN] ?: false
        val version = prefs[KEY_CONSENT_VERSION] ?: 0
        return given && version >= CURRENT_POLICY_VERSION
    }

    suspend fun recordConsent() {
        dataStore.edit { prefs ->
            prefs[KEY_CONSENT_GIVEN] = true
            prefs[KEY_CONSENT_VERSION] = CURRENT_POLICY_VERSION
            prefs[KEY_CONSENT_TIMESTAMP] = System.currentTimeMillis()
            prefs[KEY_CONSENT_LOCALE] = TimeZone.getDefault().id
        }
    }

    suspend fun revokeConsent() {
        dataStore.edit { prefs ->
            prefs[KEY_CONSENT_GIVEN] = false
            prefs[KEY_CONSENT_VERSION] = 0
            prefs[KEY_CONSENT_TIMESTAMP] = 0L
        }
    }

    suspend fun getConsentTimestamp(): Long =
        dataStore.data.first()[KEY_CONSENT_TIMESTAMP] ?: 0L

    suspend fun getConsentVersion(): Int =
        dataStore.data.first()[KEY_CONSENT_VERSION] ?: 0
}
