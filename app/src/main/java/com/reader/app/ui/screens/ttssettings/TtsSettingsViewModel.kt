package com.reader.app.ui.screens.ttssettings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.local.entity.TtsPreferencesEntity
import com.reader.app.data.repository.TtsPreferencesRepository
import com.reader.app.di.ServiceLocator
import com.reader.app.domain.tts.TtsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * TTS Settings — language, pitch, speech rate, voice selection.
 *
 * **Persistence model**: every value is saved to Room **as soon as it
 * changes** (the user does NOT have to click Save). Save is preserved as
 * an optional "force-write & give feedback" button, but the writes are
 * already happening in the background. This is what the user was missing
 * earlier — they'd tweak a slider, never click Save, and the change
 * would silently revert when Reading / Discussion modes loaded their own
 * TtsController and re-read prefs from Room.
 *
 * **Live preview**: each change is also pushed to the live TTS engine
 * (the same instance that powers the in-screen "Test voice" button) so
 * the user can hear the difference before leaving the screen.
 */
class TtsSettingsViewModel(
    private val repo: TtsPreferencesRepository,
    val tts: TtsController
) : ViewModel() {

    data class UiState(
        val languageTag: String = "hi-IN",
        val pitch: Float = 1.0f,
        val speechRate: Float = 1.0f,
        val voiceName: String? = null,
        val availableLanguages: List<LanguageOption> = SUPPORTED_LANGUAGES,
        val availableVoices: List<VoiceOption> = emptyList(),
        val savedMessage: String? = null,
        val isLoading: Boolean = true
    )

    data class LanguageOption(val tag: String, val display: String)
    data class VoiceOption(val name: String, val display: String)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun ensureReady() {
        if (tts.state.value !is TtsController.State.Ready) {
            viewModelScope.launch {
                val saved = repo.get()
                tts.init(
                    languageTag = saved.languageTag,
                    pitch       = saved.pitch,
                    speechRate  = saved.speechRate,
                    voiceName   = saved.voiceName
                ) {
                    refreshFromTts(saved)
                }
            }
        } else {
            viewModelScope.launch { refreshFromTts(repo.get()) }
        }
        // Mirror voice list updates from TTS
        viewModelScope.launch {
            tts.availableVoices.collect { voices ->
                _state.update {
                    it.copy(availableVoices = voices.map { v ->
                        VoiceOption(v.name, formatVoiceName(v.name))
                    })
                }
            }
        }
    }

    private fun refreshFromTts(saved: TtsPreferencesEntity) {
        _state.update {
            it.copy(
                languageTag = saved.languageTag,
                pitch       = saved.pitch,
                speechRate  = saved.speechRate,
                voiceName   = saved.voiceName,
                isLoading   = false
            )
        }
    }

    fun setLanguage(tag: String) {
        // Switching language invalidates a previously-selected voice
        // because each voice is locale-bound. Clear it so the user
        // doesn't end up with e.g. a Hindi voice silently still active
        // after they switched to Tamil.
        _state.update { it.copy(languageTag = tag, voiceName = null) }
        tts.setLanguage(tag)
        tts.setVoice(null)
        persistCurrent()
    }

    fun setPitch(value: Float) {
        _state.update { it.copy(pitch = value) }
        tts.setPitch(value)
        persistCurrent()
    }

    fun setSpeechRate(value: Float) {
        _state.update { it.copy(speechRate = value) }
        tts.setSpeechRate(value)
        persistCurrent()
    }

    fun setVoice(name: String?) {
        _state.update { it.copy(voiceName = name) }
        tts.setVoice(name)
        persistCurrent()
    }

    fun testVoice() {
        val s = _state.value
        val sample = sampleFor(s.languageTag)
        tts.speakOneShot(sample)
    }

    /**
     * Explicit Save still works — flushes the current state to Room and
     * surfaces a snackbar. With the auto-persist-on-change above this is
     * mostly redundant but kept as visible confirmation feedback.
     */
    fun save() {
        viewModelScope.launch {
            persistCurrentSync()
            _state.update { it.copy(savedMessage = "Voice settings saved") }
        }
    }

    /** Fire-and-forget persist of the current state. */
    private fun persistCurrent() {
        viewModelScope.launch { persistCurrentSync() }
    }

    private suspend fun persistCurrentSync() {
        val s = _state.value
        // Skip while the screen is still hydrating from Room — otherwise
        // the very first state emission (defaults) would clobber what
        // the user actually has saved.
        if (s.isLoading) return
        repo.save(
            TtsPreferencesEntity(
                languageTag = s.languageTag,
                pitch       = s.pitch,
                speechRate  = s.speechRate,
                voiceName   = s.voiceName
            )
        )
    }

    fun consumeMessage() = _state.update { it.copy(savedMessage = null) }

    override fun onCleared() {
        tts.shutdown()
        super.onCleared()
    }

    private fun sampleFor(tag: String): String = when {
        tag.startsWith("hi") -> "नमस्ते, मेरा नाम Reader है। यह आपकी आवाज़ की झलक है।"
        tag.startsWith("mr") -> "नमस्कार, माझे नाव Reader आहे."
        tag.startsWith("bn") -> "নমস্কার, আমার নাম Reader।"
        tag.startsWith("ta") -> "வணக்கம், என் பெயர் Reader."
        else                 -> "Hello, my name is Reader. This is a preview of how I sound."
    }

    private fun formatVoiceName(name: String): String {
        // Voice names look like "hi-in-x-hid-network" — make them friendlier.
        val parts = name.split('-')
        return parts.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    companion object {
        val SUPPORTED_LANGUAGES = listOf(
            LanguageOption("hi-IN", "Hindi (India)"),
            LanguageOption("en-IN", "English (India)"),
            LanguageOption("en-US", "English (US)"),
            LanguageOption("mr-IN", "Marathi (India)"),
            LanguageOption("bn-IN", "Bengali (India)"),
            LanguageOption("ta-IN", "Tamil (India)"),
            LanguageOption("te-IN", "Telugu (India)"),
            LanguageOption("gu-IN", "Gujarati (India)"),
            LanguageOption("kn-IN", "Kannada (India)"),
            LanguageOption("ml-IN", "Malayalam (India)"),
            LanguageOption("pa-IN", "Punjabi (India)"),
            LanguageOption("ur-IN", "Urdu (India)")
        )

        fun factory(appContext: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = appContext.applicationContext
                return TtsSettingsViewModel(
                    repo = ServiceLocator.ttsPreferencesRepository,
                    tts  = TtsController(app)
                ) as T
            }
        }
    }
}
