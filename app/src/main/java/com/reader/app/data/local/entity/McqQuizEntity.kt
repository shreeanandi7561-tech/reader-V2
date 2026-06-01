package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One generation of a quiz — i.e. one LLM call's worth of MCQs extracted
 * from a document's transcript.
 *
 * A document can have multiple quizzes (the student may regenerate to
 * pick up newly added options or after we tweak the prompt). Cascading
 * delete on `documentId` so quizzes go away with their parent doc, and
 * a parallel cascade on `quizId` from [McqQuestionEntity] /
 * [McqAttemptEntity] keeps the whole tree consistent.
 *
 * `negativeMarkPerWrong` is stored on the quiz so a quiz generated
 * under the v1 rule (-0.33 per wrong) keeps its scoring even after we
 * change the default later.
 */
@Entity(
    tableName = "mcq_quiz",
    foreignKeys = [
        ForeignKey(
            entity        = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns  = ["documentId"],
            onDelete      = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId"])]
)
data class McqQuizEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val documentId: Long,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Number of questions surviving confidence + dedupe filters. */
    val questionCount: Int,
    /** Computed at generation time: max((N − 1), 1) minutes. */
    val timeLimitSeconds: Int,
    /** Mark per correct answer. v1 = 1.0. */
    val markPerCorrect: Double = 1.0,
    /** Penalty per wrong answer. v1 = 0.33 (i.e. 3 wrong = −1). */
    val negativeMarkPerWrong: Double = 0.33,
)
