package com.reader.app.domain.mcq

import com.reader.app.data.local.entity.McqQuestionEntity
import com.reader.app.data.local.entity.McqQuizEntity
import com.reader.app.data.remote.LlmClientFactory
import com.reader.app.data.repository.LlmRepository
import com.reader.app.domain.model.ApiConfig
import com.reader.app.domain.text.LanguageDetect

/**
 * Converts a transcript into a list of MCQs ready to persist as
 * [McqQuizEntity] + [McqQuestionEntity] rows.
 *
 * The pipeline used to run two LLM round trips — eligibility then
 * extraction — but the eligibility classifier was the dominant
 * source of false negatives ("Transcript discusses percentage
 * concepts and examples but does not contain explicit MCQs"). It
 * sampled only the first 12 K chars and was too conservative
 * about what counts as "MCQ-style discussion".
 *
 * **Current pipeline:**
 *   1. **Local pre-screen** — a regex pass over the WHOLE transcript
 *      looking for explicit MCQ markers ("option A", "(B)", "पहला
 *      विकल्प", "doosra option", "kaun sa sahi", "the answer is",
 *      etc.). If we find ≥3 such markers, we SKIP the eligibility
 *      LLM entirely — the transcript clearly contains MCQs and
 *      asking the LLM to second-guess that wastes a round trip.
 *   2. **Lenient eligibility LLM** — only used when the local pre-
 *      screen is uncertain (1-2 markers, or zero markers in a long
 *      transcript). The classifier prompt is now skewed towards
 *      "yes" — "return false ONLY when the transcript clearly has
 *      no questions, no options and no answer reveals". The sample
 *      is a stitched start+middle+end of the transcript, not just
 *      the head, so MCQs in the second half of the video aren't
 *      missed.
 *   3. **Extraction** — the heavy prompt that pulls out every
 *      Q-it-can-see along with options, correct answer, source tag
 *      and confidence, in strict JSON. Now language-aware (the
 *      prompt receives the transcript language so option fallbacks
 *      stay in the right language) and **retries once** if the first
 *      attempt yields zero questions, with a stronger nudge.
 *
 * Post-processing then:
 *   - Pads/trims options to exactly 4 (per spec; AI-filled options
 *     are marked `source = "ai_filled"`).
 *   - Drops questions with confidence < [MIN_CONFIDENCE] (now 0.35;
 *     was 0.5 — small models routinely score legitimate verbatim
 *     extractions at 0.4-0.5 and were being filtered out).
 *   - Drops near-duplicate questions (normalised-text hash).
 *   - Fixes out-of-range `correctAnswer` indices (defaults to 0).
 */
object McqGenerator {

    /**
     * Drop questions below this LLM-self-reported confidence.
     *
     * Lowered: 0.5 → 0.35 → 0.25. The user's repeated complaint is
     * "very few MCQs detected even though the video has many"; the
     * dominant cause was over-aggressive filtering of legitimate
     * questions reported at 0.3-0.4 by small models. 0.25 still
     * keeps the obvious hallucinations out (the LLM uses < 0.2 for
     * "I made this up") but lets through everything the model is
     * even slightly confident about.
     */
    private const val MIN_CONFIDENCE: Double = 0.25

    /**
     * Transcripts longer than this hit the chunked-extraction path.
     * Below it, a single round-trip is plenty; above it, models
     * routinely "miss" questions in the back half of the prompt
     * (attention drops as input grows). Splitting the transcript
     * into overlapping windows gives every section the model's full
     * attention and recovers the long-tail of MCQs the single-pass
     * extractor was leaving behind.
     */
    private const val CHUNK_THRESHOLD_CHARS: Int = 8_000

    /** Each chunk's target size + overlap (chars). */
    private const val CHUNK_SIZE_CHARS: Int = 6_000
    private const val CHUNK_OVERLAP_CHARS: Int = 800

    private val resultAdapter by lazy {
        LlmClientFactory.moshiInstance().adapter(McqExtractionResultDto::class.java)
    }
    private val eligibilityAdapter by lazy {
        LlmClientFactory.moshiInstance().adapter(McqEligibilityDto::class.java)
    }

    /* ---------- prompts ---------- */

    private val ELIGIBILITY_DIRECTIVE = """
        You are a transcript classifier for an exam-prep app.

        TASK:
        Decide whether the transcript snippet below contains ANY MCQ-
        style content — i.e. one or more multiple-choice questions
        with options A/B/C/D (or "first option", "doosra option",
        "पहला विकल्प") AND any indication of the correct answer
        ("answer is B", "sahi option C hai", "doosra wala correct").

        BIAS TOWARDS "TRUE":
        - If the speaker reads even ONE question aloud followed by
          options and reveals an answer → return true.
        - If the speaker walks through a numbered question bank
          (Q1, Q2, …) where each item has options → return true.
        - Even if you can only confidently spot 1-2 such MCQs in the
          snippet, return true. The downstream extractor will pull
          out as many as the full transcript contains.
        - Numerical-options ("first option 25, second 50, third 75,
          fourth 100") count just as much as text options.
        - Hindi / Hinglish patterns count: "kaun sa sahi hai",
          "iska answer kya hoga", "option A, option B, option C,
          option D".

        Return false ONLY when the transcript is purely conceptual
        teaching with NO question-options-answer pattern anywhere.
        When in doubt, return true.

        RESPOND WITH JSON ONLY, NO PROSE, NO MARKDOWN FENCES:
        {
          "containsMcqs": true | false,
          "reason": "<one short sentence in Hinglish>"
        }
    """.trimIndent()

    private val EXTRACTION_DIRECTIVE = """
        You are an MCQ extractor for an exam-prep app. The transcript
        below comes from a teacher running a video class. Your single
        job is to extract EVERY question the teacher actually asks
        or works through, turn each one into a 4-option MCQ, and
        return the result as strict JSON.

        ╔════════════════════════════════════════════════════════════╗
        ║  RULE 0 — LANGUAGE (NON-NEGOTIABLE)                        ║
        ╚════════════════════════════════════════════════════════════╝
        The user prompt starts with "OUTPUT LANGUAGE: <lang>". Every
        question, option, and originalSnippet you emit MUST be in
        that language and that script. If the transcript is Hindi
        (Devanagari), write Devanagari. If it's Hinglish (Hindi in
        Roman script), match the code-switching ratio. DO NOT
        translate. Numbers stay as numbers regardless.

        ╔════════════════════════════════════════════════════════════╗
        ║  MATH RENDERING — USE LaTeX, NOT PLAIN TEXT                ║
        ╚════════════════════════════════════════════════════════════╝
        You MUST emit math expressions in LaTeX for MathJax.
        - INLINE math: wrap in `\\(` and `\\)`. DO NOT use single dollar signs.
        - DISPLAY math: wrap in `\\[` and `\\]`. DO NOT use double dollar signs.
        - Use proper LaTeX commands (`\frac`, `\sqrt`, `x^{2}`).
        - CRITICAL: MathJax cannot render Hindi characters correctly. Keep ALL Hindi text OUTSIDE of the math blocks. Only place numbers, variables, and math operators inside the math blocks.

        ╔════════════════════════════════════════════════════════════╗
        ║  CORE PRINCIPLE — COVERAGE AND CONCEPTUAL GENERATION       ║
        ╚════════════════════════════════════════════════════════════╝
        The user has explicitly asked for MAXIMUM COVERAGE. Based on the subject of the video, you must handle:

        SITUATION 1: MATH / PROBLEM-SOLVING SUBJECTS (Contains MCQs/Examples)
        - ALWAYS apply this logic for Math subjects.
        - If the teacher reads or discusses questions ("chaliye prashn Ek solve karte Hain", "pahla prashn"), YOU MUST EXTRACT THEM. EXACT MATCH.
        - Order or serial number doesn't matter, just extract the exact questions.
        - If the transcript shows the question + the correct answer
          but NO options, EXTRACT it and GENERATE 4 options yourself using AI 
          (`source = "ai_filled"`).
        - If only 1, 2, or 3 options are stated, FILL the rest.
        - Worked examples ("agar speed 60 km/h hai... distance kya hoga?")
          are QUESTIONS. Extract them, compute the answer, generate options.

        SITUATION 2: NON-MATH / PURE THEORY SUBJECTS (No explicit questions)
        - ALWAYS apply this logic for subjects other than Math (e.g. Science, History, GK, Theory).
        - If the video is purely theoretical teaching/concepts and does NOT
          discuss any specific practice questions or MCQs:
          YOU MUST GENERATE THE TOP 10-20 MOST IMPORTANT ACADEMIC/SUBJECT-MATTER MCQs based strictly on the educational concepts taught in the video.
        - STRICTLY IGNORE all promotional talk, course fees, inquiry numbers, teacher introductions, Telegram channel links, or personal stories. DO NOT generate ANY questions about these!
        - Identify the core subject/chapter of the video and generate questions ONLY about that core subject/chapter based ON THE FACTS PRESENTED IN THIS VIDEO.
        - Do NOT include external information not taught in the video.
        - Do NOT return an empty list.
        - Generate the question, the correct answer, and 3 plausible distractors.
        - Mark `source = "ai_filled"` for all of them.

        When in doubt about whether something is a question:
        INCLUDE IT OR GENERATE ONE ABOUT IT.

        ╔════════════════════════════════════════════════════════════╗
        ║  WHAT COUNTS AS A QUESTION                                 ║
        ╚════════════════════════════════════════════════════════════╝
        Treat each of these as a candidate question to extract:

        a. Explicit "Question N" / "Q.N" / "प्रश्न N" headers.
        b. Direct interrogatives:
              - English: "What is …?", "Which of the following …?",
                "How much …?", "Find the value of …"
              - Hinglish: "Iska answer kya hoga?", "Kya hoga jab …?",
                "Konsa sahi hai?", "Calculate karke batao", "Kitna
                hoga?", "Kya value aayegi?"
              - Hindi (Devanagari): "क्या होगा", "कौन सा सही है",
                "इसका उत्तर क्या होगा", "कितना होगा"
        c. Worked examples ("Example: speed = 60, time = 2, distance?"),
           even when phrased declaratively. The "?" is implicit.
        d. Practice problems / "ab tum khud try karo" exercises.
        e. Anything where the teacher pauses and asks the audience
           to think before continuing.

        Patterns that explicitly state options (extract verbatim
        when present):
          - "option A: 25, option B: 50, option C: 75, option D: 100"
          - "(A) 25 (B) 50 (C) 75 (D) 100"
          - "pehla option 25 hai, doosra 50, teesra 75, chautha 100"
          - "पहला विकल्प 25, दूसरा 50, तीसरा 75, चौथा 100"
          - "1) 25  2) 50  3) 75  4) 100"

        Answer-reveal patterns (these confirm correct answer):
          - "answer is B", "B is correct", "doosra option sahi hai",
            "iska sahi answer 75 hai", "option C correct hai",
            "सही उत्तर B है"

        ╔════════════════════════════════════════════════════════════╗
        ║  OUTPUT FORMAT — JSON ONLY, NO PROSE, NO MARKDOWN FENCES   ║
        ╚════════════════════════════════════════════════════════════╝
        {
          "questions": [
            {
              "question": "<question text in OUTPUT LANGUAGE>",
              "options": ["<A>", "<B>", "<C>", "<D>"],
              "correctAnswer": <0..3>,
              "source": "transcript" | "ai_filled",
              "confidence": <0.0..1.0>,
              "originalSnippet": "<short verbatim slice from transcript>"
            }
          ]
        }

        ╔════════════════════════════════════════════════════════════╗
        ║  EXTRACTION RULES (NON-NEGOTIABLE)                         ║
        ╚════════════════════════════════════════════════════════════╝
        1. Detect EVERY question the teacher discusses. If the video
           has 30 questions, return 30. Don't sample.
        2. PRESERVE the teacher's wording for the question when you
           can. Same language, same code-switching ratio, same
           technical terms. If the teacher only paraphrased, you may
           lightly clean up filler words but keep the question
           recognisable.
        3. PRESERVE option wording when the teacher actually states
           the option. Numerical options → keep as numbers. When
           you have to fill in (no option stated), invent a wording
           that matches the language register of the transcript.
        4. The options array is ALWAYS exactly four. Pad with
           plausible AI-generated distractors when the transcript
           is short on options. Mark `source = "ai_filled"` whenever
           ANY of the four was AI-filled. Use `"transcript"` only
           when all four came verbatim from the teacher.
        5. Identify the correct answer:
           a) From an explicit reveal ("answer is B", "doosra sahi
              hai", "सही उत्तर 75 है") → confidence 0.85-1.0.
           b) From the teacher's worked-example computation → take
              the computed value, place at any slot in the array,
              set `correctAnswer` to that index. Confidence 0.6-0.85.
           c) From the teacher's reasoning ("ye galat hai kyunki…")
              → eliminate the wrong, pick what's left. Confidence
              0.4-0.7.
        6. Set `confidence` honestly:
           - 1.0   teacher read the question, all 4 options, and
                   the answer cleanly.
           - 0.7-0.9  question is verbatim and answer is confirmed
                   but you filled 1-2 distractors.
           - 0.4-0.6  question is recognised, you computed the
                   answer from a worked example.
           - 0.25-0.35  question is implicit, options + answer fully
                   AI-generated. (Still keep these — the user wants
                   coverage. The app drops < 0.25 only.)
        7. `originalSnippet` is a SHORT (1-3 sentence) verbatim
           transcript slice the question came from, for student
           verification. If you fully synthesised the question from
           a worked example, quote the example.
        8. SKIP duplicates — if the teacher repeats the same
           question twice, return it once. (Light wording differences
           don't make it a different question.)
        9. The OUTER object has exactly one key, `questions`, whose
           value is an array. NO other keys at root. NO markdown
           fences.

        ╔════════════════════════════════════════════════════════════╗
        ║  EMPTY-ARRAY RULE                                          ║
        ╚════════════════════════════════════════════════════════════╝
        Returning `{"questions": []}` is the ABSOLUTE LAST RESORT.
        Before doing so, check:
          - Are there worked examples? Each example with a numeric
            answer is a question.
          - Are there "ab batao", "kya hoga", "kya answer aayega",
            "क्या होगा" prompts in the transcript?
          - Are there "Question N" / "Q.N" headers?
          - Could a teaching point be reframed as a question? (E.g.
            "Newton ka 2nd law force = ma hai" → "Newton ke 2nd
            law ke according force kis ke barabar hai?".)
        Only after exhausting all of the above, return [].
    """.trimIndent()

    /* ---------- public API ---------- */

    data class GenerationResult(
        val quiz: McqQuizEntity,
        val questions: List<McqQuestionEntity>,
    )

    /**
     * Eligibility pre-check.
     *
     * Hybrid strategy:
     *   - LOCAL regex pre-screen counts MCQ markers across the WHOLE
     *     transcript. ≥3 hits → containsMcqs = true with no LLM
     *     call (fast, free, and never wrong about positives).
     *   - Otherwise stitch a sample (start + middle + end) and ask
     *     the LLM with a bias-towards-true classifier prompt.
     *
     * Returns null when transcript is blank.
     */
    suspend fun checkEligibility(
        config: ApiConfig,
        transcript: String,
    ): Result<McqEligibilityDto> = runCatching {
        if (transcript.isBlank()) {
            return@runCatching McqEligibilityDto(
                containsMcqs = false,
                reason = "Transcript is empty — kuch likhne / record karne ke baad try karein.",
            )
        }

        // Local fast-path. The regex prescreen scans the WHOLE
        // transcript (cheap), so MCQs in the second half of a long
        // video can't be missed by a sampling window.
        val markerCount = countMcqMarkers(transcript)
        if (markerCount >= 3) {
            return@runCatching McqEligibilityDto(
                containsMcqs = true,
                reason = "Transcript mein $markerCount MCQ-markers mile — extract kar raha hoon.",
            )
        }

        // LLM eligibility — sample stitched from three slices so a
        // 60-min video doesn't hide its MCQ section past the
        // sampling window.
        val sample = stitchSample(transcript, eachLen = 4_000)
        val raw = LlmRepository().ask(
            config       = config,
            systemPrompt = ELIGIBILITY_DIRECTIVE,
            userPrompt   = "TRANSCRIPT (start + middle + end):\n$sample",
            jsonMode     = true,
        ).getOrThrow()
        val cleaned = stripFences(raw)
        val parsed = eligibilityAdapter.fromJson(cleaned)

        // Defensive override: if the LLM said false but we found
        // even ONE marker locally, lean true. Cheap insurance
        // against the false-negative the user reported.
        if (parsed != null && !parsed.containsMcqs && markerCount >= 1) {
            return@runCatching McqEligibilityDto(
                containsMcqs = true,
                reason = "LLM ne 'no' bola par transcript mein MCQ markers mile — extract try kar raha hoon.",
            )
        }
        parsed ?: McqEligibilityDto(
            containsMcqs = markerCount >= 1,
            reason = if (markerCount >= 1)
                "Classifier output parse nahi hua, par markers mile — extract try kar raha hoon."
            else
                "Classifier output parse nahi ho saka — please try again."
        )
    }

    /**
     * Extract MCQs from the transcript and produce a [McqQuizEntity]
     * (not yet persisted; pass to [com.reader.app.data.repository.McqRepository]).
     *
     * **Two modes:**
     *
     *   - **Mode A (video-questions):** When [questionSegments] is non-empty,
     *     the transcript contains explicitly-numbered questions discussed
     *     by the teacher. The extraction prompt is given ONLY those
     *     segments — the MCQs come from the teacher's actual questions,
     *     not random topic-based generation. Eligibility is skipped
     *     (the detector already confirmed the questions exist).
     *
     *   - **Mode B (topic-based):** When [questionSegments] is empty (or
     *     null), full-transcript extraction using the existing heavy
     *     prompt. If [previousQuestionTexts] is non-empty, the prompt
     *     includes a "DO NOT re-emit these" exclusion list so
     *     regenerate gives a fresh set. When the LLM genuinely can't
     *     find anything new, the error message says so.
     *
     * **Long-transcript handling (added):** transcripts above
     * [CHUNK_THRESHOLD_CHARS] are split into overlapping chunks of
     * [CHUNK_SIZE_CHARS] with [CHUNK_OVERLAP_CHARS] overlap, the
     * extractor is run on each chunk independently, results are
     * merged and deduped. Models routinely "miss" the back half of
     * a 30-min lecture when handed it as one prompt — attention
     * drops as input grows. Chunking restores per-section
     * attention and recovers the long-tail of MCQs the single-pass
     * extractor was leaving behind. Overlap protects against a
     * question landing on a chunk boundary.
     *
     * Throws on persistent LLM error or unparseable JSON across
     * every chunk + the retry. Retries the whole pipeline once if
     * the merged result is empty.
     *
     * @param previousQuestionTexts normalised question texts from
     *   ALL previous quizzes for this document. The prompt tells the
     *   LLM to exclude these. Pass empty list on first generation.
     * @param questionSegments when non-empty, activates Mode A: each
     *   segment is one teacher-discussed question's transcript body.
     *   Pass null / empty to use Mode B.
     */
    suspend fun generate(
        config: ApiConfig,
        documentId: Long,
        documentTitle: String,
        transcript: String,
        previousQuestionTexts: List<String> = emptyList(),
        questionSegments: List<VideoQuestionDetector.QuestionSegment> = emptyList(),
    ): Result<GenerationResult> = runCatching {
        require(transcript.isNotBlank()) { "transcript is empty" }
        val lang = LanguageDetect.detect(transcript)

        // ---- Mode A: video-questions path ----
        if (questionSegments.isNotEmpty()) {
            return@runCatching generateFromVideoQuestions(
                config = config,
                documentId = documentId,
                documentTitle = documentTitle,
                segments = questionSegments,
                lang = lang,
                previousQuestionTexts = previousQuestionTexts,
            )
        }

        // ---- Mode B: topic-based path (existing + exclusion) ----

        // Decide chunked vs single-pass. Note that we do NOT chunk
        // the eligibility check — only this final extraction — so
        // the cheap classifier still sees the stitched start +
        // middle + end sample.
        val chunks: List<String> =
            if (transcript.length <= CHUNK_THRESHOLD_CHARS) listOf(transcript)
            else splitWithOverlap(transcript, CHUNK_SIZE_CHARS, CHUNK_OVERLAP_CHARS)

        // Build the exclusion clause for the prompt when regenerating.
        val exclusionClause = buildExclusionClause(previousQuestionTexts)

        // First pass: extract from each chunk (or the single whole
        // transcript). Failures within a single chunk are swallowed
        // — the user wants coverage, so partial extraction across
        // 5 of 6 chunks is much better than throwing the whole
        // generation on one chunk's bad JSON.
        val firstPass: List<Cleaned> = chunks.flatMap { chunk ->
            runCatching {
                extractOnce(config, chunk, lang, retryNudge = false, exclusionClause = exclusionClause)
            }.getOrDefault(emptyList())
        }
        var merged = mergeAndDedupe(firstPass, previousQuestionTexts)

        // Retry the whole pipeline once with a "look harder" nudge
        // if the first pass came back empty across all chunks. We
        // re-run on each chunk so the retry inherits chunked
        // attention; on a single-chunk transcript this is just one
        // extra LLM call.
        if (merged.isEmpty()) {
            val retryPass: List<Cleaned> = chunks.flatMap { chunk ->
                runCatching {
                    extractOnce(config, chunk, lang, retryNudge = true, exclusionClause = exclusionClause)
                }.getOrDefault(emptyList())
            }
            merged = mergeAndDedupe(retryPass, previousQuestionTexts)
        }

        if (merged.isEmpty()) {
            if (previousQuestionTexts.isNotEmpty()) {
                error(
                    "Iss document se jo bhi important questions ban sakte the, " +
                        "woh sab pehle ke sets mein aa chuke hain. Naye unique " +
                        "questions nahi mil rahe — document mein utna content " +
                        "cover ho chuka hai!"
                )
            } else {
                error(
                    "Transcript se koi confident question nahi mila. " +
                        "Agar video mein clearly questions / examples the, " +
                        "transcript shayad incomplete hai — full video re-import " +
                        "karke try karein."
                )
            }
        }

        // Time budget: 60s per question, with a small buffer for
        // the first one (it always feels longer to read).
        val timeLimitSec = (merged.size.coerceAtLeast(1)) * 60

        val quiz = McqQuizEntity(
            documentId           = documentId,
            title                = documentTitle,
            questionCount        = merged.size,
            timeLimitSeconds     = timeLimitSec,
            markPerCorrect       = 1.0,
            negativeMarkPerWrong = 0.33,
        )
        val questionEntities = merged.mapIndexed { i, q ->
            McqQuestionEntity(
                quizId          = 0L,
                orderIndex      = i,
                question        = q.question,
                optionA         = q.options[0],
                optionB         = q.options[1],
                optionC         = q.options[2],
                optionD         = q.options[3],
                correctIndex    = q.correctIndex,
                confidence      = q.confidence,
                source          = q.source,
                originalSnippet = q.originalSnippet,
            )
        }
        GenerationResult(quiz = quiz, questions = questionEntities)
    }

    /**
     * Mode A: the transcript contains explicitly-numbered questions
     * discussed by the teacher. We send ONLY the question segments
     * to the extraction prompt (one per chunk if they're long, else
     * all concatenated). The MCQ-generation prompt is slightly
     * different: it emphasises "these are the actual questions from
     * the video — extract them verbatim, don't invent new ones."
     */
    private suspend fun generateFromVideoQuestions(
        config: ApiConfig,
        documentId: Long,
        documentTitle: String,
        segments: List<VideoQuestionDetector.QuestionSegment>,
        lang: LanguageDetect.Lang,
        previousQuestionTexts: List<String>,
    ): GenerationResult {
        // Concatenate all segments with clear separators. Each
        // segment's label (e.g. "Q.1", "pehla sawaal") is preserved
        // as a header so the LLM sees the teacher's numbering.
        val combined = segments.joinToString(separator = "\n\n---\n\n") { seg ->
            "【${seg.label}】\n${seg.body}"
        }

        val exclusionClause = buildExclusionClause(previousQuestionTexts)

        // If combined text is short enough, single pass; else chunk.
        val chunks = if (combined.length <= CHUNK_THRESHOLD_CHARS) listOf(combined)
                     else splitWithOverlap(combined, CHUNK_SIZE_CHARS, CHUNK_OVERLAP_CHARS)

        val firstPass: List<Cleaned> = chunks.flatMap { chunk ->
            runCatching {
                extractVideoQuestions(config, chunk, lang, retryNudge = false, exclusionClause = exclusionClause)
            }.getOrDefault(emptyList())
        }
        var merged = mergeAndDedupe(firstPass, previousQuestionTexts)

        if (merged.isEmpty()) {
            val retryPass: List<Cleaned> = chunks.flatMap { chunk ->
                runCatching {
                    extractVideoQuestions(config, chunk, lang, retryNudge = true, exclusionClause = exclusionClause)
                }.getOrDefault(emptyList())
            }
            merged = mergeAndDedupe(retryPass, previousQuestionTexts)
        }

        if (merged.isEmpty()) {
            if (previousQuestionTexts.isNotEmpty()) {
                error(
                    "Video mein jo questions discuss hue the, woh sab pehle " +
                        "ke sets mein extract ho chuke hain. Naye unique questions " +
                        "nahi bache!"
                )
            } else {
                error(
                    "Detected question segments se koi MCQ extract nahi ho paaya. " +
                        "Transcript mein question markers mile par options/answers " +
                        "clearly nahi the — please verify video content."
                )
            }
        }

        val timeLimitSec = (merged.size.coerceAtLeast(1)) * 60
        val quiz = McqQuizEntity(
            documentId           = documentId,
            title                = documentTitle,
            questionCount        = merged.size,
            timeLimitSeconds     = timeLimitSec,
            markPerCorrect       = 1.0,
            negativeMarkPerWrong = 0.33,
        )
        val questionEntities = merged.mapIndexed { i, q ->
            McqQuestionEntity(
                quizId          = 0L,
                orderIndex      = i,
                question        = q.question,
                optionA         = q.options[0],
                optionB         = q.options[1],
                optionC         = q.options[2],
                optionD         = q.options[3],
                correctIndex    = q.correctIndex,
                confidence      = q.confidence,
                source          = q.source,
                originalSnippet = q.originalSnippet,
            )
        }
        return GenerationResult(quiz = quiz, questions = questionEntities)
    }

    /** LLM round-trip for Mode A segments (slightly different system prompt). */
    private suspend fun extractVideoQuestions(
        config: ApiConfig,
        segmentText: String,
        lang: LanguageDetect.Lang,
        retryNudge: Boolean,
        exclusionClause: String,
    ): List<Cleaned> {
        val nudge = if (retryNudge) {
            "\n\nIMPORTANT: Your previous attempt returned ZERO questions. " +
                "The segments below DO contain questions the teacher discussed — " +
                "read carefully and extract every one. Returning [] again is NOT acceptable.\n"
        } else ""
        val raw = LlmRepository().askStreamingFull(
            config       = config,
            systemPrompt = VIDEO_QUESTIONS_EXTRACTION_DIRECTIVE,
            userPrompt   = buildString {
                append("OUTPUT LANGUAGE: ").append(lang.directive).append('\n')
                append(nudge)
                if (exclusionClause.isNotBlank()) {
                    append('\n').append(exclusionClause).append('\n')
                }
                append("\nTRANSCRIPT SEGMENTS (each labelled with teacher's question number):\n")
                append(segmentText)
            },
            jsonMode     = true,
            maxTokens    = 8_192,
            temperature  = 0.2,
        ).getOrThrow()
        val cleaned = stripFences(raw)
        val parsed = parseLenient(cleaned)
            ?: error("LLM ne valid JSON nahi diya — phir try karein. " +
                "(Pehle 200 chars: '${cleaned.take(200).replace('\n', ' ')}')")
        return postProcess(parsed.questions)
    }

    /**
     * Build a prompt clause listing previously-generated questions
     * that must NOT be repeated. Returns empty string when there's
     * nothing to exclude.
     */
    private fun buildExclusionClause(previousTexts: List<String>): String {
        if (previousTexts.isEmpty()) return ""
        // Cap at 50 to keep the prompt sane; if the user has
        // regenerated 10 times they'll hit the "exhausted" check
        // long before we exceed this.
        val shown = previousTexts.take(50)
        return buildString {
            append("╔════════════════════════════════════════════════════════════╗\n")
            append("║  DO NOT REPEAT THESE QUESTIONS (already generated)         ║\n")
            append("╚════════════════════════════════════════════════════════════╝\n")
            append("The following questions were already generated in previous sets.\n")
            append("Do NOT emit any question that is semantically the same as any\n")
            append("of these, even if reworded. Find DIFFERENT questions from the\n")
            append("transcript instead.\n\n")
            shown.forEachIndexed { i, q ->
                append("  ").append(i + 1).append(". ").append(q.take(200)).append('\n')
            }
        }
    }

    /**
     * Dedicated extraction directive for Mode A (video-questions).
     *
     * Key differences from the general [EXTRACTION_DIRECTIVE]:
     *  - Instructs the LLM that the input is PRE-SEGMENTED by question
     *    number and each segment contains ONE teacher-discussed question.
     *  - The LLM must NOT invent new questions beyond what the segments
     *    contain — it only formalises each segment into MCQ format.
     *  - Still pads to 4 options, still requires JSON-only output.
     */
    private val VIDEO_QUESTIONS_EXTRACTION_DIRECTIVE = """
        You are an MCQ extractor for an exam-prep app. The input below
        contains PRE-SEGMENTED transcript chunks, each one representing
        a SINGLE question that a teacher explicitly discussed in a video
        class (the segment header like 【Q.1】 or 【pehla sawaal】 shows
        the teacher's own numbering).

        YOUR JOB:
        - For EACH segment, extract exactly ONE 4-option MCQ.
        - The question text should capture what the teacher asked /
          discussed in that segment.
        - If the teacher read out options, use them verbatim. If they
          only discussed the question and revealed the answer without
          listing options, generate 3 plausible wrong distractors and
          mark source = "ai_filled".
        - Identify the correct answer from the teacher's explanation
          or reveal in the segment.
        - Do NOT invent questions that aren't in the segments.
        - Do NOT merge two segments into one question.
        - Do NOT skip any segment — every segment = one MCQ.

        ╔════════════════════════════════════════════════════════════╗
        ║  RULE 0 — LANGUAGE (NON-NEGOTIABLE)                        ║
        ╚════════════════════════════════════════════════════════════╝
        The user prompt starts with "OUTPUT LANGUAGE: <lang>". Every
        question, option, and originalSnippet you emit MUST be in
        that language and that script.

        ╔════════════════════════════════════════════════════════════╗
        ║  MATH RENDERING — USE LaTeX, NOT PLAIN TEXT                ║
        ╚════════════════════════════════════════════════════════════╝
        You MUST emit math expressions in LaTeX for MathJax.
        - INLINE math: wrap in `\\(` and `\\)`. DO NOT use single dollar signs.
        - DISPLAY math: wrap in `\\[` and `\\]`. DO NOT use double dollar signs.
        - Use proper LaTeX commands (`\frac`, `\sqrt`, `x^{2}`).
        - CRITICAL: MathJax cannot render Hindi characters correctly. Keep ALL Hindi text OUTSIDE of the math blocks. Only place numbers, variables, and math operators inside the math blocks.

        ╔════════════════════════════════════════════════════════════╗
        ║  OUTPUT FORMAT — JSON ONLY, NO PROSE, NO MARKDOWN FENCES   ║
        ╚════════════════════════════════════════════════════════════╝
        {
          "questions": [
            {
              "question": "<question text in OUTPUT LANGUAGE>",
              "options": ["<A>", "<B>", "<C>", "<D>"],
              "correctAnswer": <0..3>,
              "source": "transcript" | "ai_filled",
              "confidence": <0.0..1.0>,
              "originalSnippet": "<short verbatim slice from segment>"
            }
          ]
        }

        EXTRACTION RULES:
        1. One question per segment. N segments → N questions.
        2. Options array is ALWAYS exactly four. Pad with distractors.
        3. Confidence 0.8-1.0 when answer is explicitly revealed in
           segment. 0.5-0.7 when computed from the discussion.
        4. originalSnippet = short verbatim slice showing the question
           and answer reveal from the transcript.
        5. PRESERVE the teacher's wording. Same language register.
        6. SKIP duplicates within this extraction only (not across sets).
        7. Do NOT add any questions that don't correspond to a segment.
    """.trimIndent()

    /**
     * Split [text] into pieces of approximately [chunkSize] chars
     * with [overlap] chars of overlap between consecutive pieces.
     *
     * Each cut prefers a sentence boundary within ~200 chars of the
     * target so we don't slice mid-word. The overlap means a
     * question that begins near a boundary still appears intact in
     * one of the chunks (the dedupe pass collapses the duplicate
     * extraction).
     */
    private fun splitWithOverlap(text: String, chunkSize: Int, overlap: Int): List<String> {
        if (text.length <= chunkSize) return listOf(text)
        require(overlap < chunkSize) { "overlap >= chunkSize" }
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val targetEnd = (start + chunkSize).coerceAtMost(text.length)
            val end = if (targetEnd >= text.length) {
                text.length
            } else {
                // Look for a sentence break within the last 200
                // chars of the target. Devanagari "।", "!", "?",
                // ".", and newline all count.
                val searchFrom = (targetEnd - 200).coerceAtLeast(start + 1)
                var cut = -1
                for (i in targetEnd - 1 downTo searchFrom) {
                    val c = text[i]
                    if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '।') {
                        cut = i + 1
                        break
                    }
                }
                if (cut > 0) cut else targetEnd
            }
            out += text.substring(start, end)
            if (end >= text.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return out
    }

    /**
     * Merge per-chunk extraction results, drop low-confidence
     * questions, dedupe by normalised question text. Order in the
     * output reflects the order of first appearance — stable
     * enough for the user's review, and the user explicitly said
     * sequence doesn't matter.
     *
     * @param previousTexts normalised question texts from prior
     *   quizzes for the same document — any new question matching
     *   one of these is dropped (the "regenerate gives fresh set"
     *   contract). Empty list on first generation.
     */
    private fun mergeAndDedupe(
        parts: List<Cleaned>,
        previousTexts: List<String> = emptyList(),
    ): List<Cleaned> {
        if (parts.isEmpty()) return emptyList()
        val prevKeys = previousTexts.mapTo(HashSet(previousTexts.size)) { normalise(it) }
        val seen = HashSet<String>()
        val out = ArrayList<Cleaned>(parts.size)
        for (q in parts) {
            if (q.confidence < MIN_CONFIDENCE) continue
            val key = normalise(q.question)
            if (key.isBlank() || !seen.add(key)) continue
            // Skip if this question was already generated in a previous set.
            if (key in prevKeys) continue
            out += q
        }
        return out
    }

    /** One LLM round-trip + parse + post-process. */
    private suspend fun extractOnce(
        config: ApiConfig,
        transcript: String,
        lang: LanguageDetect.Lang,
        retryNudge: Boolean,
        exclusionClause: String = "",
    ): List<Cleaned> {
        val nudge = if (retryNudge) {
            "\n\nIMPORTANT: Your previous attempt returned ZERO questions. " +
                "If the transcript has them, extract them. If it is pure theory, " +
                "GENERATE 10-20 conceptual MCQs based on the facts in the text. " +
                "Returning [] again is NOT acceptable.\n"
        } else ""
        val raw = LlmRepository().askStreamingFull(
            config       = config,
            systemPrompt = EXTRACTION_DIRECTIVE,
            userPrompt   = buildString {
                append("OUTPUT LANGUAGE: ").append(lang.directive).append('\n')
                append(nudge)
                if (exclusionClause.isNotBlank()) {
                    append('\n').append(exclusionClause).append('\n')
                }
                append("\nTRANSCRIPT:\n").append(transcript)
            },
            jsonMode     = true,
            maxTokens    = 8_192,
            // Medium-low temperature (0.4): predictable extraction when
            // present verbatim, but creative enough to generate distractors
            // and conceptual questions when the video is pure theory.
            temperature  = 0.4,
        ).getOrThrow()

        val cleaned = stripFences(raw)
        val parsed = parseLenient(cleaned)
            ?: error("LLM ne valid JSON nahi diya — phir try karein. " +
                "(Pehle 200 chars: '${cleaned.take(200).replace('\n', ' ')}')")

        return postProcess(parsed.questions)
    }

    /**
     * Try to coerce whatever the LLM returned into an
     * [McqExtractionResultDto]. Strategy:
     *  1. Parse as the canonical `{"questions": [...]}` envelope.
     *  2. If that fails, parse as a bare `[...]` array of questions.
     *  3. If THAT fails, return null and let the caller surface the
     *     "valid JSON nahi diya" error with a sample of the raw output.
     */
    private fun parseLenient(s: String): McqExtractionResultDto? {
        runCatching { resultAdapter.fromJson(s) }.getOrNull()?.let { return it }
        runCatching {
            val type = com.squareup.moshi.Types.newParameterizedType(
                List::class.java, McqExtractedQuestionDto::class.java
            )
            val arrayAdapter = LlmClientFactory.moshiInstance()
                .adapter<List<McqExtractedQuestionDto>>(type)
            arrayAdapter.fromJson(s)?.let { McqExtractionResultDto(questions = it) }
        }.getOrNull()?.let { return it }
        return null
    }

    /* ---------- transcript sampling + marker scan ---------- */

    /**
     * Counts plausible MCQ markers anywhere in the transcript.
     * Cheap regex pass. Used as both:
     *   (a) the eligibility fast-path (≥3 markers ⇒ skip LLM and
     *       declare eligible),
     *   (b) the eligibility LLM-override sentinel (LLM says false
     *       but markers exist ⇒ override to true).
     *
     * Patterns are intentionally generous — we tolerate occasional
     * false positives (a teacher saying "option" once outside an
     * MCQ context) because the eligibility result only gates whether
     * the heavy extractor runs, not whether the user sees results.
     * The extractor itself is selective.
     */
    private fun countMcqMarkers(text: String): Int {
        val lower = text.lowercase()
        var hits = 0
        for (re in MCQ_MARKER_REGEXES) {
            // Cap each pattern's contribution at 5 so a single
            // repetitive pattern can't dominate the count.
            val m = re.findAll(lower).take(5).count()
            hits += m
        }
        return hits
    }

    private val MCQ_MARKER_REGEXES: List<Regex> = listOf(
        // English / Hinglish option markers
        Regex("""\boption\s+[abcd]\b"""),
        Regex("""\boption\s*[-:]?\s*[1-4]\b"""),
        Regex("""\(\s*[abcd]\s*\)""", RegexOption.IGNORE_CASE),
        Regex("""\b[1-4]\s*[\)\.]\s+\S"""),                       // "1) text" / "1. text"
        // Hinglish ordinal options
        Regex("""\b(pehla|pehli|first)\s+option\b"""),
        Regex("""\b(doosra|doosri|dusra|second)\s+option\b"""),
        Regex("""\b(teesra|teesri|tisra|third)\s+option\b"""),
        Regex("""\b(chautha|chouthi|chautha|fourth)\s+option\b"""),
        // Hinglish answer reveals
        Regex("""\b(sahi|correct|right)\s+(answer|option)\b"""),
        Regex("""\banswer\s+(is|hai)\b"""),
        Regex("""\bcorrect\s+(option|answer)\s+(is|hai)\b"""),
        // Hindi (Devanagari) markers — case insensitive doesn't
        // matter, Devanagari has no case.
        Regex("""विकल्प\s*[1-4]"""),
        Regex("""(पहला|दूसरा|तीसरा|चौथा)\s+विकल्प"""),
        Regex("""सही\s+(उत्तर|विकल्प|जवाब)"""),
        Regex("""(कौन|कौनसा|कौन-सा)\s+सही"""),
        // Question-bank markers
        Regex("""\bquestion\s+(no\.?\s*)?[0-9]+\b"""),
        Regex("""\bq\s*\.?\s*[0-9]+\b"""),
        Regex("""प्रश्न\s+[0-9]+"""),
    )

    /**
     * Stitch a representative sample from the start, middle and end
     * of the transcript. Each slice is [eachLen] chars, with `…`
     * separators between them. For short transcripts (< 3·eachLen)
     * this just returns the whole thing.
     */
    private fun stitchSample(text: String, eachLen: Int): String {
        if (text.length <= 3 * eachLen) return text
        val start = text.substring(0, eachLen)
        val midFrom = (text.length - eachLen) / 2
        val mid = text.substring(midFrom, midFrom + eachLen)
        val end = text.substring(text.length - eachLen)
        return buildString {
            append(start)
            append("\n\n…[middle]…\n\n")
            append(mid)
            append("\n\n…[end]…\n\n")
            append(end)
        }
    }

    /* ---------- post-processing ---------- */

    /** Internal cleaned-question — already 4 options, valid index. */
    private data class Cleaned(
        val question: String,
        val options: List<String>,
        val correctIndex: Int,
        val source: String,
        val confidence: Double,
        val originalSnippet: String?,
    )

    private fun postProcess(raw: List<McqExtractedQuestionDto>): List<Cleaned> {
        val seen = HashSet<String>()
        val out = ArrayList<Cleaned>(raw.size)
        for (q in raw) {
            val cleaned = cleanOne(q) ?: continue
            if (cleaned.confidence < MIN_CONFIDENCE) continue
            val key = normalise(cleaned.question)
            if (key.isBlank() || !seen.add(key)) continue
            out += cleaned
        }
        return out
    }

    private fun cleanOne(q: McqExtractedQuestionDto): Cleaned? {
        val question = q.question?.trim().orEmpty()
        if (question.isBlank()) return null

        // Pad / trim options to exactly 4. We never *reject* a question
        // for short-options because the spec explicitly says "AI should
        // intelligently generate missing options" — the LLM was already
        // told to do so; this is a safety net for when it didn't.
        var rawOpts = (q.options ?: emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (rawOpts.isEmpty()) return null
        var aiPaddedHere = false
        while (rawOpts.size < 4) {
            // Generic decoy — flagged via source = ai_filled. Placeholder
            // text is deliberately neutral so it doesn't accidentally
            // become the most-correct answer.
            rawOpts = rawOpts + "Option ${('A' + rawOpts.size)}"
            aiPaddedHere = true
        }
        if (rawOpts.size > 4) rawOpts = rawOpts.take(4)

        val correctIndex = (q.correctAnswer ?: 0).coerceIn(0, 3)
        // If LLM tagged source explicitly, trust it; otherwise infer
        // from whether we had to pad.
        val source = q.source
            ?.trim()
            ?.lowercase()
            ?.takeIf { it == "transcript" || it == "ai_filled" }
            ?: if (aiPaddedHere) "ai_filled" else "transcript"
        val confidence = (q.confidence ?: 0.85).coerceIn(0.0, 1.0)
        return Cleaned(
            question        = question,
            options         = rawOpts,
            correctIndex    = correctIndex,
            source          = source,
            confidence      = confidence,
            originalSnippet = q.originalSnippet?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Lower-cased, alpha-numeric-only question text. Drops trivial
     * formatting differences so "What is photosynthesis?" and "what is
     * photosynthesis" collapse to the same dedup key.
     */
    private fun normalise(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) if (c.isLetterOrDigit()) sb.append(c)
        return sb.toString()
    }

    /**
     * Aggressive JSON extraction: strip markdown fences, then locate
     * the first balanced `{...}` block (or `[...]` block) in the
     * remaining text and return that. Tolerates LLMs that prepend a
     * "Here is the JSON:" preamble or append a "I hope this helps"
     * suffix despite explicit "no prose" instructions.
     *
     * String-literal handling means a `}` inside a JSON string value
     * doesn't accidentally close the outer object — important for
     * questions like `"What does '}' mean?"` that include literal
     * braces in the text.
     */
    private fun stripFences(raw: String): String {
        var s = raw.trim()
        // ```json … ``` or ``` … ```
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            if (firstNewline > 0) s = s.substring(firstNewline + 1)
            val closing = s.lastIndexOf("```")
            if (closing > 0) s = s.substring(0, closing)
            s = s.trim()
        }
        // Find the first opening brace OR bracket that begins a
        // balanced block; return that block.
        val firstObj = s.indexOf('{')
        val firstArr = s.indexOf('[')
        val first = when {
            firstObj < 0 && firstArr < 0 -> -1
            firstObj < 0 -> firstArr
            firstArr < 0 -> firstObj
            else -> minOf(firstObj, firstArr)
        }
        if (first < 0) return s.trim()
        val open = s[first]
        val close = if (open == '{') '}' else ']'
        val end = findBalancedClose(s, first, open, close)
        val block = if (end < 0) s.substring(first).trim() else s.substring(first, end + 1)
        return block
    }

    /**
     * Walk forward from [openIdx] tracking brace depth, treating
     * string literals as opaque. Returns the index of the matching
     * close, or -1 if unbalanced.
     */
    private fun findBalancedClose(s: String, openIdx: Int, open: Char, close: Char): Int {
        var depth = 0
        var inStr = false
        var i = openIdx
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when (c) {
                    '\\' -> { i += 2; continue }   // skip escaped char
                    '"'  -> inStr = false
                }
                i++
                continue
            }
            when (c) {
                '"'  -> inStr = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
