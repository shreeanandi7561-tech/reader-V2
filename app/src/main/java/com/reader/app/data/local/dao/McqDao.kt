package com.reader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reader.app.data.local.entity.McqAttemptEntity
import com.reader.app.data.local.entity.McqQuestionEntity
import com.reader.app.data.local.entity.McqQuizEntity
import kotlinx.coroutines.flow.Flow

/**
 * One DAO covers all three MCQ tables — they're never queried in
 * isolation by callers (the repository always wants the quiz with its
 * questions, or the attempt with its quiz), so co-locating the queries
 * here keeps Room's generated code surface small.
 */
@Dao
interface McqDao {

    /* ---------- Quiz ---------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuiz(quiz: McqQuizEntity): Long

    @Query("SELECT * FROM mcq_quiz WHERE documentId = :documentId ORDER BY createdAt DESC")
    fun observeQuizzesForDocument(documentId: Long): Flow<List<McqQuizEntity>>

    @Query("SELECT * FROM mcq_quiz WHERE id = :quizId")
    suspend fun getQuiz(quizId: Long): McqQuizEntity?

    @Query("DELETE FROM mcq_quiz WHERE id = :quizId")
    suspend fun deleteQuiz(quizId: Long)

    /* ---------- Question ---------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<McqQuestionEntity>)

    @Query("SELECT * FROM mcq_question WHERE quizId = :quizId ORDER BY orderIndex ASC")
    suspend fun getQuestionsForQuiz(quizId: Long): List<McqQuestionEntity>

    /* ---------- Attempt ---------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttempt(attempt: McqAttemptEntity): Long

    @Query("SELECT * FROM mcq_attempt WHERE id = :attemptId")
    suspend fun getAttempt(attemptId: Long): McqAttemptEntity?

    @Query("SELECT * FROM mcq_attempt WHERE quizId = :quizId ORDER BY updatedAt DESC")
    fun observeAttemptsForQuiz(quizId: Long): Flow<List<McqAttemptEntity>>

    /**
     * The single attempt the student should be asked to "Resume" — the
     * most-recently-touched row that hasn't been submitted yet. Null
     * when there's nothing in flight, in which case the home screen
     * shows "Start new attempt".
     */
    @Query(
        """
        SELECT * FROM mcq_attempt
        WHERE quizId = :quizId AND status = 'in_progress'
        ORDER BY updatedAt DESC LIMIT 1
        """
    )
    suspend fun getInProgressAttempt(quizId: Long): McqAttemptEntity?

    /**
     * All questions ever generated for a document, across ALL quizzes.
     * Used by the "regenerate with exclusion" flow: the caller collects
     * the normalised question texts from previous sets and injects them
     * into the prompt so the LLM avoids repeating them.
     *
     * Ordered by quizId (oldest quiz first) so the earliest-generated
     * question wins the normalisation dedup if there was overlap.
     */
    @Query(
        """
        SELECT q.* FROM mcq_question q
        INNER JOIN mcq_quiz z ON z.id = q.quizId
        WHERE z.documentId = :documentId
        ORDER BY z.createdAt ASC, q.orderIndex ASC
        """
    )
    suspend fun getAllQuestionsForDocument(documentId: Long): List<McqQuestionEntity>
}
