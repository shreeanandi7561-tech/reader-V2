package com.reader.app.ui.screens.mcq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.local.entity.McqAttemptEntity
import com.reader.app.data.local.entity.McqQuestionEntity
import com.reader.app.data.local.entity.McqQuizEntity
import com.reader.app.data.repository.McqRepository
import com.reader.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loads the submitted attempt + its quiz + questions so the Result
 * screen can show the score and a per-question breakdown.
 *
 * No timer, no auto-save — strictly read-only.
 */
class McqResultViewModel(
    private val attemptId: Long,
    private val mcq: McqRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val attempt: McqAttemptEntity? = null,
        val quiz: McqQuizEntity? = null,
        val questions: List<McqQuestionEntity> = emptyList(),
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val a = mcq.getAttempt(attemptId)
            if (a == null) {
                _state.update { it.copy(isLoading = false, errorMessage = "Attempt nahi mila.") }
                return@launch
            }
            val q = mcq.getQuiz(a.quizId)
            val qs = mcq.getQuestionsForQuiz(a.quizId)
            _state.update {
                it.copy(
                    isLoading = false,
                    attempt   = a,
                    quiz      = q,
                    questions = qs,
                )
            }
        }
    }

    /** Decoded selection for [questionIndex], or null if the student skipped. */
    fun selectionAt(questionIndex: Int): Int? =
        _state.value.attempt?.let { mcq.selectionAt(it.selectedAnswers, questionIndex) }

    companion object {
        fun factory(attemptId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                McqResultViewModel(
                    attemptId = attemptId,
                    mcq       = ServiceLocator.mcqRepository,
                ) as T
        }
    }
}
