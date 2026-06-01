package com.reader.app.data.repository

import com.reader.app.data.local.dao.McqDao
import com.reader.app.data.local.entity.McqAttemptEntity
import com.reader.app.data.local.entity.McqQuestionEntity
import com.reader.app.data.local.entity.McqQuizEntity
import kotlinx.coroutines.flow.Flow

/**
 * Thin wrapper over [McqDao] — the DAO already covers all three tables,
 * so this layer mostly exists to (a) provide a stable seam the
 * ViewModels see and (b) own the small "fresh attempt" / "encode
 * selections" helpers that aren't a query.
 */
class McqRepository(private val dao: McqDao) {

    /* ---------- quizzes ---------- */

    fun observeQuizzesForDocument(documentId: Long): Flow<List<McqQuizEntity>> =
        dao.observeQuizzesForDocument(documentId)

    suspend fun getQuiz(quizId: Long): McqQuizEntity? = dao.getQuiz(quizId)

    suspend fun deleteQuiz(quizId: Long) = dao.deleteQuiz(quizId)

    /**
     * Persist a fresh quiz + its questions atomically-enough for our
     * purposes — quizzes are only ever created from a single VM
     * coroutine, so a partial failure mid-insert is recoverable by
     * just deleting the orphan quiz row.
     *
     * Returns the new quiz id.
     */
    suspend fun saveQuizWithQuestions(
        quiz: McqQuizEntity,
        questions: List<McqQuestionEntity>
    ): Long {
        val quizId = dao.insertQuiz(quiz)
        // Re-stamp each question with the freshly-assigned quiz id +
        // ensure orderIndex is sequential (0..N-1) regardless of what
        // the caller passed.
        val rows = questions.mapIndexed { i, q -> q.copy(quizId = quizId, orderIndex = i) }
        dao.insertQuestions(rows)
        return quizId
    }

    suspend fun getQuestionsForQuiz(quizId: Long): List<McqQuestionEntity> =
        dao.getQuestionsForQuiz(quizId)

    /**
     * All questions ever generated for [documentId], across every quiz.
     * Used by the "regenerate with exclusion" flow to avoid re-asking
     * the same questions in a new set.
     */
    suspend fun getAllQuestionsForDocument(documentId: Long): List<McqQuestionEntity> =
        dao.getAllQuestionsForDocument(documentId)

    /* ---------- attempts ---------- */

    suspend fun upsertAttempt(attempt: McqAttemptEntity): Long =
        dao.upsertAttempt(attempt)

    suspend fun getAttempt(attemptId: Long): McqAttemptEntity? = dao.getAttempt(attemptId)

    suspend fun getInProgressAttempt(quizId: Long): McqAttemptEntity? =
        dao.getInProgressAttempt(quizId)

    fun observeAttemptsForQuiz(quizId: Long): Flow<List<McqAttemptEntity>> =
        dao.observeAttemptsForQuiz(quizId)

    /* ---------- selection encoding ----------
     *
     * Selections are stored as a length-N string of '_' / '0'..'3' so
     * the DB column is cheap on every auto-save. These helpers keep
     * the encoding in one place — DO NOT inline them in callers.
     */

    /** Underscore-filled empty selection of length [count]. */
    fun emptySelections(count: Int): String = "_".repeat(count)

    /** Decoded selection or null if the question is unanswered. */
    fun selectionAt(encoded: String, index: Int): Int? {
        if (index < 0 || index >= encoded.length) return null
        return when (val c = encoded[index]) {
            '_' -> null
            in '0'..'3' -> c - '0'
            else -> null
        }
    }

    /** New encoded string with [index] set to [optionIndex] (or cleared if null). */
    fun setSelection(encoded: String, index: Int, optionIndex: Int?): String {
        val sb = StringBuilder(encoded)
        if (index in 0 until sb.length) {
            sb[index] = optionIndex?.let { ('0' + it) } ?: '_'
        }
        return sb.toString()
    }
}
