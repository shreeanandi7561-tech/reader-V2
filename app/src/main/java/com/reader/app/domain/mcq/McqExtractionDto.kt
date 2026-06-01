package com.reader.app.domain.mcq

import com.squareup.moshi.JsonClass

/**
 * JSON shape we ask the LLM to produce when extracting MCQs from a
 * transcript. Kept narrow on purpose — the more fields we ask for, the
 * more the model freelances.
 *
 *   {
 *     "questions": [
 *       {
 *         "question": "What is photosynthesis?",
 *         "options": ["...", "...", "...", "..."],
 *         "correctAnswer": 0,
 *         "source": "transcript",
 *         "confidence": 0.92,
 *         "originalSnippet": "verbatim slice from transcript"
 *       }
 *     ]
 *   }
 *
 * Lists shorter than 4 are padded by the post-processor (see
 * [McqGenerator]); longer than 4 are trimmed. `correctAnswer` is
 * 0-based against the (possibly-padded) options array.
 */
@JsonClass(generateAdapter = true)
data class McqExtractionResultDto(
    val questions: List<McqExtractedQuestionDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class McqExtractedQuestionDto(
    val question: String? = null,
    val options: List<String>? = null,
    val correctAnswer: Int? = null,
    /** "transcript" (purely lifted) or "ai_filled" (AI completed missing options). */
    val source: String? = null,
    val confidence: Double? = null,
    val originalSnippet: String? = null,
)

/**
 * Minimal eligibility-check shape — first pass before the heavy
 * extraction. We only need a yes/no + reason, so a tiny prompt + a
 * tiny response keeps this latency-cheap.
 */
@JsonClass(generateAdapter = true)
data class McqEligibilityDto(
    val containsMcqs: Boolean = false,
    val reason: String? = null,
)
