package com.reader.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.repository.ConfigRepository
import com.reader.app.data.repository.SpeakerEnrollmentRepository
import com.reader.app.di.ServiceLocator
import com.reader.app.domain.model.ApiConfig
import com.reader.app.domain.model.AppMode
import com.reader.app.domain.model.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings = two BYOK rows (Mode 1 LLM, Mode 2 LLM) + voice enrollment block.
 *
 * STT is done with the device's [android.speech.SpeechRecognizer] (free,
 * on-device) so no Whisper API key row is needed.
 */
class SettingsViewModel(
    private val configRepo: ConfigRepository,
    private val enrollmentRepo: SpeakerEnrollmentRepository
) : ViewModel() {

    data class ModeForm(
        val mode: AppMode,
        val provider: LlmProvider = LlmProvider.Groq,
        val apiKey: String = "",
        val modelName: String = ""
    ) {
        fun toConfig() = ApiConfig(mode = mode, provider = provider, apiKey = apiKey, modelName = modelName)
    }

    data class UiState(
        val reading: ModeForm = ModeForm(AppMode.Reading),
        val discussion: ModeForm = ModeForm(AppMode.Discussion),
        val generate: ModeForm = ModeForm(AppMode.Generate),
        val enrollmentUpdatedAt: Long? = null,
        val savedMessage: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            configRepo.get(AppMode.Reading)?.let { c ->
                _state.update { it.copy(reading = ModeForm(c.mode, c.provider, c.apiKey, c.modelName)) }
            }
            configRepo.get(AppMode.Discussion)?.let { c ->
                _state.update { it.copy(discussion = ModeForm(c.mode, c.provider, c.apiKey, c.modelName)) }
            }
            configRepo.get(AppMode.Generate)?.let { c ->
                _state.update { it.copy(generate = ModeForm(c.mode, c.provider, c.apiKey, c.modelName)) }
            }
            enrollmentRepo.get()?.let { e ->
                _state.update { it.copy(enrollmentUpdatedAt = e.updatedAt) }
            }
        }
    }

    fun update(mode: AppMode, transform: (ModeForm) -> ModeForm) {
        _state.update { s ->
            when (mode) {
                AppMode.Reading    -> s.copy(reading    = transform(s.reading))
                AppMode.Discussion -> s.copy(discussion = transform(s.discussion))
                AppMode.Generate   -> s.copy(generate   = transform(s.generate))
            }
        }
    }

    fun save(mode: AppMode) {
        val form = when (mode) {
            AppMode.Reading    -> _state.value.reading
            AppMode.Discussion -> _state.value.discussion
            AppMode.Generate   -> _state.value.generate
        }
        viewModelScope.launch {
            configRepo.save(form.toConfig())
            _state.update { it.copy(savedMessage = "${mode.name} saved") }
        }
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
                    enrollmentRepo = ServiceLocator.speakerEnrollmentRepository
                ) as T
        }
    }
}
