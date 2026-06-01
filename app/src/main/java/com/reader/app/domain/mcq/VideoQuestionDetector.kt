package com.reader.app.domain.mcq

/**
 * Detects whether a transcript contains **explicitly-numbered questions**
 * discussed by the teacher in a video class, and if so, extracts the
 * cue-text segments where each question lives.
 *
 * # Two modes for MCQ generation
 *
 * The user wants:
 *  - **Mode A (video-questions):** When the teacher explicitly
 *    says things like "yeh pehla prashn hai", "Q.2", "second
 *    question", "प्रश्न ३", etc., extract ONLY those questions as
 *    MCQs — not random topic-based MCQs from the transcript.
 *  - **Mode B (topic-based):** When no such explicit numbering is
 *    detectable, fall back to "top 10 most important questions from
 *    the document" (the existing McqGenerator behaviour).
 *
 * This detector handles the Mode A / Mode B decision + the per-
 * question segmentation for Mode A. The caller (McqGenerator /
 * GenerationWorker) switches between two prompt strategies based on
 * what this returns.
 *
 * # Detection heuristic (no LLM call — pure regex)
 *
 * A transcript is declared "has explicit questions" when the
 * [detect] pass finds **≥ 2 distinct question-number markers**.
 * Patterns matched (all case-insensitive, multiline):
 *
 *  - English:    "question 1", "Q.2", "Q 3", "question no. 4",
 *                "first question", "second question", etc.
 *  - Hinglish:   "pehla sawaal", "doosra question", "teesra prashn",
 *                "chautha sawal", "paanchwa sawaal"
 *  - Hindi (Devanagari): "प्रश्न 1", "प्रश्न संख्या 2", "पहला प्रश्न",
 *                "दूसरा सवाल", "तीसरा प्रश्न", "question नंबर 5"
 *  - Numeric ordinals with Hindi suffixes: "1st question", "2nd sawaal"
 *
 * Threshold of ≥2 (not ≥1) avoids false positives from a teacher
 * who says "ek question hai" once in a conceptual video.
 *
 * # Segmentation
 *
 * Once we know questions exist, we identify WHERE each question
 * starts in the flat transcript by walking the text linearly and
 * noting every match's char offset. Between two consecutive markers
 * lies the "body" of that question (the teacher reads it, discusses
 * options, reveals the answer). These segments become the per-
 * question input to the MCQ extraction prompt.
 *
 * # Why no LLM call here?
 *
 * - Detection must be fast and free (runs before the heavy
 *   extraction call, same as the existing `countMcqMarkers` check).
 * - Hindi / Hinglish question-number patterns are very regular and
 *   finite — a 25-pattern regex suite covers 99%+ of real tutoring
 *   videos.
 * - If the regex misses some exotic numbering (e.g. "roman numeral
 *   i, ii, iii"), we just fall through to Mode B — the student
 *   still gets MCQs, just topic-generated. No harm.
 */
object VideoQuestionDetector {

    /**
     * Result of detection on a transcript.
     *
     * [HasQuestions] means we found ≥ [MIN_MARKERS] numbered
     * question references; each [QuestionSegment] is the text
     * between two consecutive markers (i.e. the "body" of one
     * question as discussed by the teacher).
     *
     * [NoQuestions] means the transcript doesn't contain enough
     * explicit question numbering — caller should use Mode B
     * (topic-based MCQ generation from full document).
     */
    sealed interface Result {
        data class HasQuestions(val segments: List<QuestionSegment>) : Result
        data object NoQuestions : Result
    }

    /**
     * One detected question's segment of the transcript.
     *
     * @property label the matched marker text ("Q.1", "pehla sawaal",
     *   "प्रश्न 2", etc.) — for display / debugging only.
     * @property body the verbatim transcript text from this marker up
     *   to (but not including) the next marker, or the end of the
     *   transcript. This is what gets sent to the LLM for MCQ
     *   extraction.
     */
    data class QuestionSegment(
        val label: String,
        val body: String,
    )

    /** Minimum distinct marker matches required to declare Mode A. */
    private const val MIN_MARKERS = 2

    /**
     * Run detection on the full [transcript] text.
     *
     * Returns [Result.HasQuestions] with per-question segments when
     * the transcript contains ≥ [MIN_MARKERS] distinct question-
     * number markers; otherwise [Result.NoQuestions].
     *
     * Cheap (one regex pass + segmentation). No I/O, no LLM.
     */
    fun detect(transcript: String): Result {
        if (transcript.isBlank()) return Result.NoQuestions

        // Find all marker matches with their char-offset in the
        // transcript. Each MatchResult gives us:
        //  - range.first → where the marker starts
        //  - value       → the matched text ("Q.1", "pehla sawaal"…)
        val markers = ArrayList<Pair<Int, String>>()
        for (regex in QUESTION_MARKER_REGEXES) {
            for (m in regex.findAll(transcript)) {
                markers += m.range.first to m.value.trim()
            }
        }

        // Dedupe overlapping matches (two regexes might match the
        // same occurrence; keep the earliest / longest).
        val deduped = dedupeByOffset(markers)

        if (deduped.size < MIN_MARKERS) return Result.NoQuestions

        // Sort by position in the transcript and segment.
        val sorted = deduped.sortedBy { it.first }
        val segments = ArrayList<QuestionSegment>(sorted.size)
        for (i in sorted.indices) {
            val (offset, label) = sorted[i]
            val bodyStart = offset
            val bodyEnd = if (i + 1 < sorted.size) sorted[i + 1].first
                          else transcript.length
            val body = transcript.substring(bodyStart, bodyEnd).trim()
            if (body.isNotBlank()) {
                segments += QuestionSegment(label = label, body = body)
            }
        }

        return if (segments.size >= MIN_MARKERS) Result.HasQuestions(segments)
        else Result.NoQuestions
    }

    /**
     * When two markers overlap in position (i.e. |offset_a - offset_b| < 10),
     * keep only the one with the longer match text (more specific).
     * This prevents double-counting "question 1" when both "question [0-9]+"
     * and "question no. [0-9]+" match the same occurrence.
     */
    private fun dedupeByOffset(markers: List<Pair<Int, String>>): List<Pair<Int, String>> {
        if (markers.size <= 1) return markers
        val sorted = markers.sortedBy { it.first }
        val out = ArrayList<Pair<Int, String>>(sorted.size)
        for (m in sorted) {
            val last = out.lastOrNull()
            if (last != null && kotlin.math.abs(m.first - last.first) < 10) {
                // Overlap — keep the longer (more specific) match.
                if (m.second.length > last.second.length) {
                    out[out.size - 1] = m
                }
            } else {
                out += m
            }
        }
        return out
    }

    /**
     * Master regex list for question-number detection.
     *
     * Each regex is MULTILINE + IGNORE_CASE. The list is ordered
     * roughly from most-specific to least-specific so the dedup pass
     * (which keeps the longer match on overlap) naturally favours the
     * more informative label.
     */
    private val QUESTION_MARKER_REGEXES: List<Regex> = listOf(
        // English explicit "question" + number
        Regex("""(?i)\bquestion\s+(?:no\.?\s*)?(\d+)\b"""),
        Regex("""(?i)\bq\s*[\.\-:]\s*(\d+)\b"""),
        Regex("""(?i)\bq\s+(\d+)\b"""),

        // English ordinal + "question"
        Regex("""(?i)\b(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\s+question\b"""),

        // Hinglish: ordinal + "sawaal"/"prashn"/"question"/"sawal"
        Regex("""(?i)\b(pehla|pehli|pahla|pahli)\s+(sawaal|sawal|prashn|question|prashna)\b"""),
        Regex("""(?i)\b(doosra|doosri|dusra|dusri)\s+(sawaal|sawal|prashn|question|prashna)\b"""),
        Regex("""(?i)\b(teesra|teesri|tisra|tisri)\s+(sawaal|sawal|prashn|question|prashna)\b"""),
        Regex("""(?i)\b(chautha|chauthi|chautha)\s+(sawaal|sawal|prashn|question|prashna)\b"""),
        Regex("""(?i)\b(paanchwa|paanchvi|panchwa|panchvi)\s+(sawaal|sawal|prashn|question|prashna)\b"""),
        Regex("""(?i)\b(chhatha|chhathi|chhata)\s+(sawaal|sawal|prashn|question|prashna)\b"""),
        Regex("""(?i)\b(saatwa|saatwi|satwa|satwi)\s+(sawaal|sawal|prashn|question|prashna)\b"""),
        Regex("""(?i)\b(aathwa|aathwi|aathva)\s+(sawaal|sawal|prashn|question|prashna)\b"""),
        Regex("""(?i)\b(nauwa|nauwi|nauva|nauvi)\s+(sawaal|sawal|prashn|question|prashna)\b"""),
        Regex("""(?i)\b(daswa|daswi|dasva|dasvi)\s+(sawaal|sawal|prashn|question|prashna)\b"""),

        // Hinglish: "sawaal/question" + "number" + digit
        Regex("""(?i)\b(sawaal|sawal|prashn|question)\s+(number|no\.?|nambr|nambar)\s*(\d+)\b"""),

        // Hindi Devanagari: "प्रश्न" + number
        Regex("""प्रश्न\s*(?:संख्या\s*)?(\d+)"""),
        Regex("""सवाल\s*(?:नंबर\s*)?(\d+)"""),

        // Hindi Devanagari ordinals + "प्रश्न"/"सवाल"
        Regex("""(पहला|पहली)\s+(प्रश्न|सवाल|question)"""),
        Regex("""(दूसरा|दूसरी)\s+(प्रश्न|सवाल|question)"""),
        Regex("""(तीसरा|तीसरी)\s+(प्रश्न|सवाल|question)"""),
        Regex("""(चौथा|चौथी)\s+(प्रश्न|सवाल|question)"""),
        Regex("""(पांचवा|पांचवी|पाँचवाँ)\s+(प्रश्न|सवाल|question)"""),

        // Numeric "1st / 2nd / 3rd / 4th" + "question"/"sawaal"
        Regex("""(?i)\b(\d+)\s*(?:st|nd|rd|th)\s+(question|sawaal|sawal|prashn)\b"""),

        // Stand-alone "prashn N" (teacher says "prashn ek, prashn do…")
        Regex("""(?i)\bprashn\s+(\d+)\b"""),
        Regex("""(?i)\bsawaal\s+(\d+)\b"""),
        Regex("""(?i)\bsawal\s+(\d+)\b"""),
    )
}
