package com.reader.app.ui.screens.enrollment

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.repository.SpeakerEnrollmentRepository
import com.reader.app.di.ServiceLocator
import com.reader.app.domain.audio.AudioRecorder
import com.reader.app.domain.audio.SpeakerEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-shot voice enrollment. Records ~5 seconds, computes a speaker embedding,
 * stores it on the single-row [SpeakerEnrollmentRepository] table.
 */
class EnrollmentViewModel(
    private val enrollment: SpeakerEnrollmentRepository,
    private val recorder: AudioRecorder = AudioRecorder()
) : ViewModel() {

    enum class Phase { Idle, Recording, Processing, Saved, Failed }

    data class UiState(
        val phase: Phase = Phase.Idle,
        val message: String? = null
    )

    private val _state          = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun startRecording() {
        if (_state.value.phase == Phase.Recording) return
        if (!recorder.start()) {
            _state.update { it.copy(phase = Phase.Failed, message = "Could not start microphone") }
            return
        }
        _state.update { it.copy(phase = Phase.Recording, message = null) }
    }

    fun stopRecording() {
        if (_state.value.phase != Phase.Recording) return
        _state.update { it.copy(phase = Phase.Processing) }
        viewModelScope.launch {
            val pcm = withContext(Dispatchers.IO) { recorder.stop() }
            val emb = withContext(Dispatchers.Default) { SpeakerEmbedder.embed(pcm) }
            if (emb == null) {
                _state.update {
                    it.copy(phase = Phase.Failed, message = "Couldn't extract a voice template — please try again, hold the mic for ~5 seconds.")
                }
                return@launch
            }
            enrollment.save(emb)
            _state.update { it.copy(phase = Phase.Saved, message = "Voice template saved") }
        }
    }

    override fun onCleared() {
        recorder.release()
        super.onCleared()
    }

    companion object {
        fun factory(@Suppress("UNUSED_PARAMETER") appContext: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                EnrollmentViewModel(ServiceLocator.speakerEnrollmentRepository) as T
        }
    }
}
