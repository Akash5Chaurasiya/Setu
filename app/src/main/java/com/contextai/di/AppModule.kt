package com.contextai.di

import android.content.Context
import android.view.WindowManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.contextai.core.consent.ConsentManager
import com.contextai.core.security.AccessibilityGate
import com.contextai.core.security.MicrophoneGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.consentDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "consent_prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWindowManager(@ApplicationContext context: Context): WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Provides
    @Singleton
    fun provideConsentDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.consentDataStore

    @Provides
    @Singleton
    fun provideConsentManager(dataStore: DataStore<Preferences>): ConsentManager =
        ConsentManager(dataStore)

    @Provides
    @Singleton
    fun provideAccessibilityGate(): AccessibilityGate = AccessibilityGate()

    @Provides
    @Singleton
    fun provideMicrophoneGate(): MicrophoneGate = MicrophoneGate()
}
