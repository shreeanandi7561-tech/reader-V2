package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One student attempt at one quiz.
 *
 * Designed for resume-after-close. Auto-saved on every:
 *   - selection change   (selectedAnswers JSON)
 *   - tick of the clock  (elapsedSeconds bump every ~5 s)
 *   - cursor move        (currentIndex)
 *
 * `selectedAnswers` is a compact comma-separated string of length
 * `questionCount`, one char per question:
 *   '_'        → not yet answered
 *   '0'..'3'   → option A..D selected
 * SQL-cheap, easy to render in a palette, no TypeConverter needed.
 *
 * `status` lifecycle:
 *   "in_progress"  ← created
 *   "submitted"    ← user hit Submit (or timer expired)
 *
 * On submit, [scoreRaw] / [marksObtained] / [maxMarks] are populated
 * once and never changed; correctness counters too. The result screen
 * reads straight off the attempt row, no recomputation.
 */
@Entity(
    tableName = "mcq_attempt",
    foreignKeys = [
        ForeignKey(
            entity        = McqQuizEntity::class,
            parentColumns = ["id"],
            childColumns  = ["quizId"],
            onDelete      = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["quizId", "status"])]
)
data class McqAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val quizId: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Encoded selections, length = quiz.questionCount. */
    val selectedAnswers: String,
    val currentIndex: Int = 0,
    val elapsedSeconds: Int = 0,
    val timeLimitSeconds: Int,
    val status: String = "in_progress",
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val skippedCount: Int = 0,
    val marksObtained: Double = 0.0,
    val maxMarks: Double = 0.0,
)
