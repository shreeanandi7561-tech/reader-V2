package com.reader.app.ui.screens.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.repository.ApiKeyStatus
import com.reader.app.data.repository.ConfigRepository
import com.reader.app.data.repository.SpeakerEnrollmentRepository
import com.reader.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val configRepo: ConfigRepository,
    private val enrollmentRepo: SpeakerEnrollmentRepository,
    private val sharedPrefs: SharedPreferences
) : ViewModel() {

    data class UiState(
        val apiKeys: List<String> = List(10) { "" },
        val originalApiKeys: List<String> = List(10) { "" },
        val keyStatuses: List<ApiKeyStatus> = emptyList(),
        val enrollmentUpdatedAt: Long? = null,
        val savedMessage: String? = null,
        val videoQuality: String = "Auto"
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val keys = configRepo.getKeys()
            val quality = sharedPrefs.getString("preferred_quality", "Auto") ?: "Auto"
            _state.update { it.copy(apiKeys = keys, originalApiKeys = keys, videoQuality = quality) }
            
            enrollmentRepo.get()?.let { e ->
                _state.update { it.copy(enrollmentUpdatedAt = e.updatedAt) }
            }
        }
        viewModelScope.launch {
            configRepo.statusFlow.collect { statuses ->
                _state.update { it.copy(keyStatuses = statuses) }
            }
        }
    }

    fun updateVideoQuality(quality: String) {
        sharedPrefs.edit().putString("preferred_quality", quality).apply()
        _state.update { it.copy(videoQuality = quality, savedMessage = "Video streaming quality set to $quality") }
    }

    fun updateKey(index: Int, value: String) {
        _state.update { s ->
            val newKeys = s.apiKeys.toMutableList()
            if (index in newKeys.indices) {
                newKeys[index] = value
            }
            s.copy(apiKeys = newKeys)
        }
    }

    fun saveKeys() {
        val keys = _state.value.apiKeys
        viewModelScope.launch {
            configRepo.saveKeys(keys)
            _state.update { s ->
                s.copy(
                    originalApiKeys = keys,
                    savedMessage = "Global API Keys saved and status reset"
                )
            }
        }
    }

    fun resetStats() {
        configRepo.resetStats()
        _state.update { it.copy(savedMessage = "API Key metrics reset completed") }
    }

    fun clearEnrollment() {
        viewModelScope.launch {
            enrollmentRepo.clear()
            _state.update { it.copy(enrollmentUpdatedAt = null, savedMessage = "Voice enrollment cleared") }
        }
    }

    fun consumeMessage() = _state.update { it.copy(savedMessage = null) }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                SettingsViewModel(
                    configRepo     = ServiceLocator.configRepository,
                    enrollmentRepo = ServiceLocator.speakerEnrollmentRepository,
                    sharedPrefs    = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                        .getSharedPreferences("video_playback_prefs", Context.MODE_PRIVATE)
                ) as T
        }
    }
}
