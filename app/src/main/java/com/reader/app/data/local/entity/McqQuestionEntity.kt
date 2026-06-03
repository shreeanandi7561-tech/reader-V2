package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One MCQ inside an [McqQuizEntity].
 *
 * Options are stored as four separate columns instead of a JSON list so
 * Room/SQL queries (e.g. analytics, future "weakest topic" report) stay
 * trivially queryable without a TypeConverter. Exactly four — that's a
 * v1 invariant enforced at extraction time (the LLM prompt + post-
 * processor pad / trim to four).
 *
 * `correctIndex` is 0-based against [optionA] … [optionD].
 *
 * `confidence` is the LLM's self-reported confidence (0..1) that this
 * question + correct answer was actually present in the transcript. We
 * use it both to filter at generation time and to decorate the result
 * screen ("low-confidence — review original") so the student never
 * trusts a hallucinated MCQ blindly.
 *
 * `originalSnippet` is the verbatim slice of transcript the question
 * was derived from. Lets the result screen show "yeh question is line
 * se aaya hai" so the student can verify against the source.
 */
@Entity(
    tableName = "mcq_question",
    foreignKeys = [
        ForeignKey(
            entity        = McqQuizEntity::class,
            parentColumns = ["id"],
            childColumns  = ["quizId"],
            onDelete      = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["quizId", "orderIndex"])]
)
data class McqQuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val quizId: Long,
    val orderIndex: Int,
    val question: String,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    /** 0..3 → A..D */
    val correctIndex: Int,
    val confidence: Double = 1.0,
    /** "transcript" | "ai_filled" — for UI decoration on the result screen. */
    val source: String = "transcript",
    val originalSnippet: String? = null,
    
    // Advanced Exam-reconstruction fields:
    val sourceType: String = "extracted",
    val confidenceLevel: String = "high",
    val shortSolution: String = "",
    val conceptTested: String = "",
    val difficulty: String = "Standard Competitive",
)
