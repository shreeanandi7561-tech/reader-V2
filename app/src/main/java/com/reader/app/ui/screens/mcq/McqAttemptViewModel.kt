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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the entire attempt lifecycle:
 *
 *   - Loads quiz + questions on init.
 *   - Resumes the most recent in-progress attempt for this quiz, OR
 *     creates a fresh attempt row if none exists.
 *   - Runs a 1-second tick that bumps `elapsedSeconds`, persists every
 *     [PERSIST_TICK_INTERVAL] seconds (cheap), auto-submits at limit.
 *   - Auto-saves on every selection change AND every cursor move.
 *   - On Submit (manual or timer), computes correctness counters with
 *     +1 / -0.33 (3 wrong = -1 mark) and writes a `submitted` row.
 *
 * The view-side contract is dead-simple: the screen reads [state],
 * calls [select(qIdx, optIdx)] / [goTo(qIdx)] / [submit], and is told
 * via [UiState.submittedAttemptId] when to navigate to the result
 * screen.
 */
class McqAttemptViewModel(
    private val quizId: Long,
    private val mcq: McqRepository,
) : ViewModel() {

    enum class Phase { Loading, Active, Submitting, Done, Error }

    data class UiState(
        val phase: Phase = Phase.Loading,
        val quiz: McqQuizEntity? = null,
        val questions: List<McqQuestionEntity> = emptyList(),
        val attemptId: Long = 0L,
        val currentIndex: Int = 0,
        val selections: String = "",
        val elapsedSeconds: Int = 0,
        val timeLimitSeconds: Int = 0,
        val showSubmitConfirm: Boolean = false,
        val showQuestionPalette: Boolean = false,
        /** Non-null when this attempt has been submitted — drives nav. */
        val submittedAttemptId: Long? = null,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var tickerJob: Job? = null

    init { bootstrap() }

    private fun bootstrap() {
        viewModelScope.launch {
            val quiz = mcq.getQuiz(quizId)
            if (quiz == null) {
                _state.update {
                    it.copy(
                        phase = Phase.Error,
                        errorMessage = "Quiz nahi mila — list pe wapas jaayein."
                    )
                }
                return@launch
            }
            val qs = mcq.getQuestionsForQuiz(quizId)
            if (qs.isEmpty()) {
                _state.update {
                    it.copy(
                        phase = Phase.Error,
                        errorMessage = "Iss quiz mein koi question nahi — phir generate karein."
                    )
                }
                return@launch
            }

            // Resume in-progress, else fresh attempt.
            val resumed = mcq.getInProgressAttempt(quizId)
            val attempt = resumed ?: McqAttemptEntity(
                quizId           = quiz.id,
                selectedAnswers  = mcq.emptySelections(qs.size),
                currentIndex     = 0,
                elapsedSeconds   = 0,
                timeLimitSeconds = quiz.timeLimitSeconds,
                status           = "in_progress",
            )
            val attemptId = if (resumed == null) mcq.upsertAttempt(attempt) else resumed.id

            _state.update {
                it.copy(
                    phase            = Phase.Active,
                    quiz             = quiz,
                    questions        = qs,
                    attemptId        = attemptId,
                    currentIndex     = attempt.currentIndex.coerceIn(0, qs.size - 1),
                    selections       = attempt.selectedAnswers.takeIf { s -> s.length == qs.size }
                                        ?: mcq.emptySelections(qs.size),
                    elapsedSeconds   = attempt.elapsedSeconds,
                    timeLimitSeconds = attempt.timeLimitSeconds.coerceAtLeast(quiz.timeLimitSeconds),
                )
            }
            startTicker()
        }
    }

    /* ---------- ticker ---------- */

    /**
     * Persist the row every ~5 seconds so resume-from-kill never loses
     * more than ~5 s of elapsed time. Selecting / moving cursor bumps
     * sooner than this cadence, so the only thing the timer-only
     * persist captures is "user is sitting on the same question".
     */
    private val PERSIST_TICK_INTERVAL = 5

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val s = _state.value
                if (s.phase != Phase.Active) break
                val nextElapsed = s.elapsedSeconds + 1
                _state.update { it.copy(elapsedSeconds = nextElapsed) }
                if (nextElapsed >= s.timeLimitSeconds) {
                    // Time's up → auto-submit.
                    submit(force = true)
                    break
                }
                if (nextElapsed % PERSIST_TICK_INTERVAL == 0) {
                    persistCurrent()
                }
            }
        }
    }

    /* ---------- selection / nav ---------- */

    fun select(questionIndex: Int, optionIndex: Int) {
        val s = _state.value
        if (s.phase != Phase.Active) return
        val newSelections = mcq.setSelection(s.selections, questionIndex, optionIndex)
        _state.update { it.copy(selections = newSelections) }
        viewModelScope.launch { persistCurrent() }
    }

    fun clearSelection(questionIndex: Int) {
        val s = _state.value
        if (s.phase != Phase.Active) return
        val newSelections = mcq.setSelection(s.selections, questionIndex, null)
        _state.update { it.copy(selections = newSelections) }
        viewModelScope.launch { persistCurrent() }
    }

    fun goTo(questionIndex: Int) {
        val s = _state.value
        if (s.phase != Phase.Active) return
        val clamped = questionIndex.coerceIn(0, s.questions.size - 1)
        if (clamped == s.currentIndex) return
        _state.update { it.copy(currentIndex = clamped, showQuestionPalette = false) }
        viewModelScope.launch { persistCurrent() }
    }

    fun next() = goTo(_state.value.currentIndex + 1)
    fun prev() = goTo(_state.value.currentIndex - 1)

    fun openPalette()  = _state.update { it.copy(showQuestionPalette = true) }
    fun closePalette() = _state.update { it.copy(showQuestionPalette = false) }

    /* ---------- submit ---------- */

    fun requestSubmit()  = _state.update { it.copy(showSubmitConfirm = true) }
    fun cancelSubmit()   = _state.update { it.copy(showSubmitConfirm = false) }

    fun submit(force: Boolean = false) {
        val s = _state.value
        if (s.phase != Phase.Active) return
        val quiz = s.quiz ?: return
        val qs = s.questions
        if (qs.isEmpty()) return

        // Cancel ticker so it doesn't race with our final write.
        tickerJob?.cancel()

        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.Submitting, showSubmitConfirm = false) }

            var correct = 0
            var wrong = 0
            var skipped = 0
            qs.forEachIndexed { i, q ->
                val sel = mcq.selectionAt(s.selections, i)
                when {
                    sel == null              -> skipped++
                    sel == q.correctIndex    -> correct++
                    else                     -> wrong++
                }
            }
            // +1 / -0.33 — spec says "3 wrong = -1 mark".
            val marks = correct * quiz.markPerCorrect - wrong * quiz.negativeMarkPerWrong
            val maxMarks = qs.size.toDouble() * quiz.markPerCorrect
            val now = System.currentTimeMillis()

            val submitted = McqAttemptEntity(
                id               = s.attemptId,
                quizId           = quiz.id,
                createdAt        = mcq.getAttempt(s.attemptId)?.createdAt ?: now,
                updatedAt        = now,
                selectedAnswers  = s.selections,
                currentIndex     = s.currentIndex,
                elapsedSeconds   = s.elapsedSeconds,
                timeLimitSeconds = s.timeLimitSeconds,
                status           = "submitted",
                correctCount     = correct,
                wrongCount       = wrong,
                skippedCount     = skipped,
                marksObtained    = marks,
                maxMarks         = maxMarks,
            )
            val id = mcq.upsertAttempt(submitted)
            _state.update {
                it.copy(
                    phase              = Phase.Done,
                    submittedAttemptId = id,
                    showSubmitConfirm  = false,
                )
            }
            // Touch [force] so unused-param warning doesn't bite — also
            // useful if we later differentiate "auto-submit on timeout"
            // from a manual submit (e.g. surface a different toast).
            @Suppress("UNUSED_VARIABLE")
            val timeUp = force
        }
    }

    /* ---------- persistence ---------- */

    private suspend fun persistCurrent() {
        val s = _state.value
        if (s.phase != Phase.Active) return
        val quiz = s.quiz ?: return
        val now = System.currentTimeMillis()
        val current = mcq.getAttempt(s.attemptId) ?: return
        mcq.upsertAttempt(
            current.copy(
                quizId           = quiz.id,
                updatedAt        = now,
                selectedAnswers  = s.selections,
                currentIndex     = s.currentIndex,
                elapsedSeconds   = s.elapsedSeconds,
                timeLimitSeconds = s.timeLimitSeconds,
                status           = "in_progress",
            )
        )
    }

    fun consumeNav() = _state.update { it.copy(submittedAttemptId = null) }

    override fun onCleared() {
        tickerJob?.cancel()
        // Best-effort save on dispose so backgrounding-the-app-mid-test
        // never loses the latest tick.
        viewModelScope.launch { persistCurrent() }
        super.onCleared()
    }

    /* ---------- helpers exposed to UI ---------- */

    fun selectionFor(questionIndex: Int): Int? =
        mcq.selectionAt(_state.value.selections, questionIndex)

    companion object {
        fun factory(quizId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                McqAttemptViewModel(
                    quizId = quizId,
                    mcq    = ServiceLocator.mcqRepository,
                ) as T
        }
    }
}
