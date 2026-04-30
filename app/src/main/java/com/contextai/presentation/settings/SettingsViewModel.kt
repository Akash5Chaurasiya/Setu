package com.contextai.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextai.core.ConversationWindowManager
import com.contextai.core.SharedContextBus
import com.contextai.data.local.ConversationDao
import com.contextai.data.preferences.SecurePreferencesManager
import com.contextai.domain.model.AiProvider
import com.contextai.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val selectedProvider: AiProvider = AiProvider.GROQ,
    val anthropicHasKey: Boolean = false,
    val openaiHasKey: Boolean = false,
    val geminiHasKey: Boolean = false,
    val groqHasKey: Boolean = false,
    val userProfile: UserProfile = UserProfile(),
    val showBubbleOnLockScreen: Boolean = false,
    val autoDetectContext: Boolean = true,
    val hapticEnabled: Boolean = true,
    val resumeText: String = "",
    val accessibilityEnabled: Boolean = false,
    val historyCount: Int = 0,
    val sessionTokensUsed: Int = 0,
    val saveHistory: Boolean = true
)

sealed class SettingsEvent {
    data object ApiKeySaved : SettingsEvent()
    data object ProfileSaved : SettingsEvent()
    data object HistoryCleared : SettingsEvent()
    data class Error(val message: String) : SettingsEvent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsManager: SecurePreferencesManager,
    private val conversationDao: ConversationDao,
    private val conversationManager: ConversationWindowManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        loadSettings()
        observeAccessibilityStatus()
        observeTokenUsage()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val count = conversationDao.count()
            _uiState.value = SettingsUiState(
                selectedProvider = prefsManager.getProvider(),
                anthropicHasKey = prefsManager.hasApiKey(AiProvider.ANTHROPIC),
                openaiHasKey = prefsManager.hasApiKey(AiProvider.OPENAI),
                geminiHasKey = prefsManager.hasApiKey(AiProvider.GEMINI),
                groqHasKey = prefsManager.hasApiKey(AiProvider.GROQ),
                userProfile = prefsManager.getUserProfile(),
                showBubbleOnLockScreen = prefsManager.getShowBubbleOnLockScreen(),
                autoDetectContext = prefsManager.getAutoDetectContext(),
                hapticEnabled = prefsManager.isHapticEnabled(),
                resumeText = prefsManager.getResumeText(),
                accessibilityEnabled = SharedContextBus.accessibilityEnabled.value,
                historyCount = count,
                sessionTokensUsed = conversationManager.sessionTokensUsed.value,
                saveHistory = prefsManager.isSaveHistoryEnabled()
            )
        }
    }

    private fun observeAccessibilityStatus() {
        viewModelScope.launch {
            SharedContextBus.accessibilityEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(accessibilityEnabled = enabled)
            }
        }
    }

    private fun observeTokenUsage() {
        viewModelScope.launch {
            conversationManager.sessionTokensUsed.collect { tokens ->
                _uiState.value = _uiState.value.copy(sessionTokensUsed = tokens)
            }
        }
    }

    fun setActiveProvider(provider: AiProvider) {
        prefsManager.saveProvider(provider)
        _uiState.value = _uiState.value.copy(selectedProvider = provider)
    }

    fun saveApiKey(provider: AiProvider, apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch { _events.emit(SettingsEvent.Error("API key cannot be empty")) }
            return
        }
        prefsManager.saveApiKey(provider, trimmed)
        _uiState.value = when (provider) {
            AiProvider.ANTHROPIC -> _uiState.value.copy(anthropicHasKey = true)
            AiProvider.OPENAI -> _uiState.value.copy(openaiHasKey = true)
            AiProvider.GEMINI -> _uiState.value.copy(geminiHasKey = true)
            AiProvider.GROQ -> _uiState.value.copy(groqHasKey = true)
        }
        viewModelScope.launch { _events.emit(SettingsEvent.ApiKeySaved) }
    }

    fun saveUserProfile(name: String, role: String, skills: String, experience: String) {
        val profile = UserProfile(name = name, currentRole = role, skills = skills, experience = experience)
        prefsManager.saveUserProfile(profile)
        _uiState.value = _uiState.value.copy(userProfile = profile)
        viewModelScope.launch { _events.emit(SettingsEvent.ProfileSaved) }
    }

    fun setShowBubbleOnLockScreen(show: Boolean) {
        prefsManager.setShowBubbleOnLockScreen(show)
        _uiState.value = _uiState.value.copy(showBubbleOnLockScreen = show)
    }

    fun setAutoDetectContext(enabled: Boolean) {
        prefsManager.setAutoDetectContext(enabled)
        _uiState.value = _uiState.value.copy(autoDetectContext = enabled)
    }

    fun setHapticEnabled(enabled: Boolean) {
        prefsManager.setHapticEnabled(enabled)
        _uiState.value = _uiState.value.copy(hapticEnabled = enabled)
    }

    fun saveResumeText(text: String) {
        prefsManager.saveResumeText(text)
        _uiState.value = _uiState.value.copy(resumeText = text)
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { conversationDao.deleteAll() }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(historyCount = 0)
                    _events.emit(SettingsEvent.HistoryCleared)
                }
                .onFailure { _events.emit(SettingsEvent.Error(it.message ?: "Failed to clear history")) }
        }
    }

    fun setSaveHistory(enabled: Boolean) {
        prefsManager.setSaveHistory(enabled)
        _uiState.value = _uiState.value.copy(saveHistory = enabled)
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            runCatching { conversationDao.deleteAll() }
                .onSuccess { _uiState.value = _uiState.value.copy(historyCount = 0) }
        }
    }
}
