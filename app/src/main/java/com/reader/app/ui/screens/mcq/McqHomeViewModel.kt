package com.reader.app.ui.screens.mcq

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.local.entity.McqQuizEntity
import com.reader.app.data.repository.DocumentRepository
import com.reader.app.data.repository.McqRepository
import com.reader.app.di.GenerationManager
import com.reader.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MCQ home / list screen ViewModel.
 *
 * Generation now runs through [GenerationManager] (Application-scoped
 * coroutine). The screen can show a status banner while a generation
 * is in flight and the user can press Back without cancelling — they
 * get a system notification when the new quiz is ready.
 *
 * Auto-navigate-on-fresh-quiz is preserved: when the manager reports
 * `Status.Done`, we expose the new `quizId` via [UiState.freshlyCreatedQuizId]
 * and the screen jumps into the attempt screen on the same recompose.
 * If the user has already left the screen by the time it finishes,
 * the notification is what they see; the next time they revisit the
 * MCQ home it shows the new quiz at the top of "Previous tests".
 */
class McqHomeViewModel(
    application: Application,
    private val documentId: Long,
    private val docs: DocumentRepository,
    private val mcq: McqRepository,
) : AndroidViewModel(application) {

    data class UiState(
        val title: String = "",
        val genStatus: GenerationManager.Status = GenerationManager.Status.Idle,
        /**
         * Set non-null once generation succeeds in this VM's lifetime,
         * cleared by [consumeFreshQuizNav] after navigation. Decoupled
         * from [genStatus] so navigating away + returning doesn't
         * re-trigger the jump.
         */
        val freshlyCreatedQuizId: Long? = null,
    ) {
        val isBusy: Boolean get() = genStatus is GenerationManager.Status.Running
        val progressLabel: String? get() = (genStatus as? GenerationManager.Status.Running)?.message
        val errorMessage: String? get() = (genStatus as? GenerationManager.Status.Failed)?.message
    }

    private val key = GenerationManager.Key(GenerationManager.Type.Mcq, documentId)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Stream of all quizzes for this doc — drives the "previous tests" list. */
    val quizzes: StateFlow<List<McqQuizEntity>> = mcq
        .observeQuizzesForDocument(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        viewModelScope.launch {
            docs.get(documentId)?.let { d -> _state.update { it.copy(title = d.title) } }
        }
        viewModelScope.launch {
            GenerationManager.statusFor(getApplication(), key).collect { status ->
                _state.update { it.copy(genStatus = status) }
                // When a generation finishes successfully, set the
                // navigation trigger. The screen consumes it via
                // consumeFreshQuizNav() so a re-collection of the
                // status flow doesn't re-fire navigation.
                if (status is GenerationManager.Status.Done) {
                    _state.update { it.copy(freshlyCreatedQuizId = status.resultId) }
                    GenerationManager.consume(getApplication(), key)
                }
            }
        }
    }

    fun consumeError() {
        if (_state.value.genStatus is GenerationManager.Status.Failed) {
            GenerationManager.consume(getApplication(), key)
        }
    }

    fun consumeFreshQuizNav() = _state.update { it.copy(freshlyCreatedQuizId = null) }

    /**
     * Kick off a background MCQ generation. Returns immediately;
     * status updates flow in via [GenerationManager.statusFor]. A
     * second call while one is in flight is a no-op.
     */
    fun generateQuiz() {
        val app = getApplication<Application>()
        GenerationManager.startMcq(
            application = app,
            documentId = documentId,
            documentTitle = _state.value.title.ifBlank { "MCQ Test" },
        )
    }

    suspend fun deleteQuiz(quizId: Long) {
        mcq.deleteQuiz(quizId)
    }

    companion object {
        fun factory(documentId: Long, application: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                McqHomeViewModel(
                    application = application,
                    documentId = documentId,
                    docs = ServiceLocator.documentRepository,
                    mcq = ServiceLocator.mcqRepository,
                ) as T
        }
    }
}
