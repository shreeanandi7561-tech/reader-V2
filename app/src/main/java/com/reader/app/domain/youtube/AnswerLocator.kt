package com.reader.app.domain.youtube

import com.reader.app.data.remote.LlmClientFactory
import com.reader.app.data.repository.LlmRepository
import com.reader.app.domain.model.ApiConfig
import com.reader.app.domain.rag.PromptBuilder
import com.squareup.moshi.JsonClass

/**
 * LLM-backed transcript retrieval.
 *
 * Given the student's question and the full caption transcript of the
 * video, makes ONE small Gemini call (text-only, non-streaming) that
 * returns up to 5 disjoint timestamp ranges where the ANSWER for the
 * question actually lives in the video — which may be different from
 * (and in addition to) the moment the student paused.
 *
 * # Why this exists
 *
 * The earlier `QuestionWindowDetector` always anchored on the
 * paused-at moment. That works when the student's doubt is about
 * "yeh step / abhi jo bola" — the answer IS at the pause. But when
 * the question is about a concept / method / earlier-derived formula
 * ("matrix kya hota hai", "Pythagoras kaise prove hua tha", "graph
 * waala part dobara samjhao"), the answer might be discussed minutes
 * earlier or even in multiple parts of the video. Sampling frames
 * around the pause moment in those cases captures the WRONG visuals;
 * the model then either ignores them or hallucinates around them.
 *
 * The locator solves this by ASKING the LLM: "given this question,
 * where in this transcript is the answer?" The LLM is much better
 * than any keyword-search heuristic at recognising semantic links
 * across paraphrased / topical content.
 *
 * # Cost
 *
 * One extra call per doubt, on the SAME provider (Gemini) the user
 * already configured. Latency: ~1–3 s for typical 30-min transcripts
 * (~10 KB cue text). The downstream multimodal answer call benefits
 * directly — it now sees frames that are aligned with where the
 * answer is, not where the student paused — so the perceived latency
 * "feels" the same to the student because they wait for ONE coherent
 * answer either way.
 *
 * # Failure handling
 *
 * Every failure mode (LLM unreachable, malformed JSON, empty array,
 * out-of-range segments) → returns an empty list. Caller treats that
 * as "fall back to the existing pause-window heuristic" so a
 * locator outage never breaks the doubt path; the student just gets
 * the previous (pause-anchored) frame quality on those turns.
 */
object AnswerLocator {

    /** One inferred location of the answer in the video. */
    data class Segment(
        val startSec: Double,
        val endSec: Double,
        /** One-line "why this segment matters" hint from the LLM. */
        val reason: String = ""
    ) {
        val durationSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)
    }

    /**
     * Up to 5 disjoint, sorted, sanitised segments of the transcript
     * where the answer for [question] is discussed.
     *
     * Empty list ⇒ fall back to pause-window heuristic.
     */
    suspend fun locate(
        llm: LlmRepository,
        config: ApiConfig,
        cues: List<TranscriptCue>,
        pausedAtSec: Double,
        question: String,
        history: List<PromptBuilder.Turn> = emptyList(),
    ): List<Segment> {
        if (cues.isEmpty() || question.isBlank()) return emptyList()

        val systemPrompt = SYSTEM_PROMPT
        val userPrompt = buildUserPrompt(
            cues        = cues,
            pausedAtSec = pausedAtSec,
            question    = question,
            history     = history,
        )

        val raw = llm.ask(config, systemPrompt, userPrompt).getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()

        val parsed = parseSegments(raw)
        if (parsed.isEmpty()) return emptyList()

        return sanitise(parsed, cues)
    }

    /* ------------------------- Prompt ------------------------- */

    private val SYSTEM_PROMPT = """
        You are a video-transcript retrieval engine. The student is
        watching a Hindi / Hinglish tutorial video and paused at a
        specific moment to ask a question. You are given the FULL
        transcript with per-cue timestamps.

        Your single job: identify EVERY range of the transcript where
        the ANSWER to that question is actually discussed by the
        teacher — concept introduction, derivation, worked example,
        recap, or the explicit step the student is asking about.
        Multiple ranges in different parts of the video are not just
        allowed, they are encouraged when the answer is split across
        the video.

        RULES (NON-NEGOTIABLE):
        1. Return UP TO 5 segments. Fewer is fine when fewer apply.
        2. Each segment is one CONTIGUOUS [startSec, endSec] range
           expressed in seconds (Doubles allowed).
        3. Each segment must be AT LEAST 10 s long and AT MOST 90 s
           long. Trim aggressively: if only 20 s of a 60 s span are
           actually about the answer, return those 20 s.
        4. Sort segments ascending by startSec. Segments must NOT
           overlap. If two adjacent topical mentions are within ~15 s,
           merge them into one segment instead of returning two.
        5. The student's pause moment is a STRONG hint when the
           question is referential ("yeh", "abhi", "is step"). For
           topical questions ("matrix kya hota hai", "graph waala
           part") the answer may be far from the pause — return
           wherever it actually is.
        6. If you genuinely cannot find any relevant range in the
           transcript, return `{"segments":[]}`. Do NOT invent.
        7. Output: ONE JSON object, NO markdown fences, NO commentary,
           NO preamble:
           {"segments":[{"startSec":N,"endSec":N,"reason":"short why"}]}
           `reason` must be ≤ 12 words and explain why the segment is
           relevant (e.g. "teacher derives integration-by-parts
           formula", "worked example using same method").
        8. Times are in seconds, NOT milliseconds, NOT mm:ss strings.
    """.trimIndent()

    /**
     * Build the user message: paused-at moment + question + recent
     * Q&A history (so follow-up references like "us approach mein"
     * resolve correctly) + the full timestamped cue list.
     *
     * Cues are encoded as `[mm:ss] text` lines — dense, easy for the
     * LLM to scan. We do NOT chunk or sub-sample: even a 60-min video
     * is only ~1500 cues = ~30 KB of text, well within Gemini Flash's
     * context budget.
     */
    private fun buildUserPrompt(
        cues: List<TranscriptCue>,
        pausedAtSec: Double,
        question: String,
        history: List<PromptBuilder.Turn>,
    ): String = buildString {
        append("=== STUDENT PAUSED AT ===\n")
        append('[').append(formatTimestamp(pausedAtSec)).append(']')
        append("  (").append(pausedAtSec).append(" sec)\n\n")

        append("=== STUDENT'S QUESTION ===\n")
        append(question.trim())
        append("\n\n")

        if (history.isNotEmpty()) {
            // Last 4 turns is plenty — older history rarely changes
            // where the answer lives. Trims locator-prompt size on
            // long sessions.
            val recent = history.takeLast(4)
            append("=== RECENT Q&A (for follow-up context) ===\n")
            recent.forEachIndexed { i, t ->
                append("Q").append(i + 1).append(": ")
                    .append(t.question.trim().ifBlank { "(empty)" }).append('\n')
                append("A").append(i + 1).append(": ")
                    .append(t.answer.trim().ifBlank { "(empty)" }).append("\n\n")
            }
        }

        append("=== TRANSCRIPT (one cue per line, [mm:ss] text) ===\n")
        for (c in cues) {
            append('[').append(formatTimestamp(c.startSec)).append("] ")
            append(c.text.trim().replace('\n', ' ').replace('\r', ' '))
            append('\n')
        }
    }

    /* ------------------------- Parsing ------------------------- */

    @JsonClass(generateAdapter = true)
    internal data class LocateResponseDto(
        val segments: List<SegmentDto>? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class SegmentDto(
        val startSec: Double? = null,
        val endSec: Double? = null,
        val reason: String? = null,
    )

    private val locateAdapter by lazy {
        LlmClientFactory.moshiInstance().adapter(LocateResponseDto::class.java)
    }

    /**
     * Robustly extract segments from the model's raw output.
     *
     * The system prompt asks for raw JSON, but real models occasionally
     * wrap it in markdown fences, prepend explanations, or trail a
     * sentence after. We:
     *   1. Trim markdown fences if present.
     *   2. Find the first balanced `{...}` block and try to parse it.
     *   3. On any failure, return empty list — caller treats that as
     *      "locator unavailable" and falls back to the heuristic
     *      window detector.
     */
    private fun parseSegments(raw: String): List<Segment> {
        val cleaned = stripMarkdownFences(raw.trim())
        val jsonBlock = extractFirstJsonObject(cleaned) ?: return emptyList()
        val parsed = runCatching { locateAdapter.fromJson(jsonBlock) }.getOrNull()
            ?: return emptyList()
        val raws = parsed.segments ?: return emptyList()
        return raws.mapNotNull { dto ->
            val s = dto.startSec ?: return@mapNotNull null
            val e = dto.endSec ?: return@mapNotNull null
            if (e <= s) return@mapNotNull null
            Segment(
                startSec = s,
                endSec   = e,
                reason   = dto.reason?.trim().orEmpty()
            )
        }
    }

    /**
     * If [raw] starts with ```json or ``` (with optional language tag)
     * and ends with ```, strip those fences. Tolerates leading
     * whitespace inside the fence.
     */
    private fun stripMarkdownFences(raw: String): String {
        if (!raw.startsWith("```")) return raw
        // Drop the opening fence line.
        val firstNewline = raw.indexOf('\n')
        if (firstNewline < 0) return raw
        var body = raw.substring(firstNewline + 1)
        // Drop a trailing fence if present.
        val close = body.lastIndexOf("```")
        if (close >= 0) body = body.substring(0, close)
        return body.trim()
    }

    /**
     * Find the first balanced `{...}` block in [raw], honouring
     * string literals (so braces inside `"..."` don't throw off the
     * counter). Returns the matched substring including the outer
     * braces, or `null` when no balanced block is found.
     */
    private fun extractFirstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (escape) { escape = false; continue }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"'  -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /* ------------------------ Sanitisation ----------------------- */

    /** Per-segment hard caps. */
    private const val MIN_SEGMENT_SEC: Double = 8.0
    private const val MAX_SEGMENT_SEC: Double = 90.0

    /** Adjacent segments closer than this (in seconds) are merged. */
    private const val MERGE_GAP_SEC: Double = 5.0

    /** Hard cap on total segments returned to the caller. */
    private const val MAX_SEGMENTS: Int = 5

    /**
     * Clamp segments to [0, lastCueEnd], drop too-short / inverted
     * ranges, sort, merge near-adjacent ones, cap segment duration,
     * and cap total count. Defensive against models that emit
     * out-of-range or overlapping ranges.
     */
    private fun sanitise(
        segments: List<Segment>,
        cues: List<TranscriptCue>,
    ): List<Segment> {
        if (segments.isEmpty()) return emptyList()
        val maxEnd = cues.lastOrNull()?.endSec?.coerceAtLeast(0.0)
            ?: return emptyList()

        // Step 1: clamp + drop invalids.
        val clamped = segments.mapNotNull { seg ->
            val s = seg.startSec.coerceIn(0.0, maxEnd)
            val e = seg.endSec.coerceIn(0.0, maxEnd)
            val dur = e - s
            when {
                dur < MIN_SEGMENT_SEC -> null
                dur > MAX_SEGMENT_SEC -> seg.copy(startSec = s, endSec = s + MAX_SEGMENT_SEC)
                else -> seg.copy(startSec = s, endSec = e)
            }
        }
        if (clamped.isEmpty()) return emptyList()

        // Step 2: sort + merge.
        val sorted = clamped.sortedBy { it.startSec }
        val merged = ArrayList<Segment>(sorted.size)
        for (seg in sorted) {
            val last = merged.lastOrNull()
            if (last != null && seg.startSec - last.endSec <= MERGE_GAP_SEC) {
                val combinedEnd = maxOf(last.endSec, seg.endSec)
                val cappedEnd = minOf(combinedEnd, last.startSec + MAX_SEGMENT_SEC)
                val combinedReason = listOf(last.reason, seg.reason)
                    .filter { it.isNotBlank() }
                    .joinToString(separator = " + ")
                merged[merged.size - 1] = last.copy(
                    endSec = cappedEnd,
                    reason = combinedReason
                )
            } else {
                merged += seg
            }
        }

        // Step 3: hard cap.
        return if (merged.size <= MAX_SEGMENTS) merged
        else merged.take(MAX_SEGMENTS)
    }

    /** Local copy of the same `mm:ss` formatter PromptBuilder uses. */
    private fun formatTimestamp(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }
}
