package com.reader.app.domain.rag

import com.reader.app.domain.model.AppMode

/**
 * Builds (system, user) prompt pairs for both modes.
 *
 * The user's spec for context is precise:
 *   - The student is reading / discussing a document.
 *   - When a question comes, the AI must check both the COMPLETE document
 *     and the conversation so far (previous Q&A pairs) so follow-ups
 *     like "pichhle sawal ka jawab dijiye" or "aap ne abhi kaha tha…"
 *     work correctly.
 *
 * Mode directives:
 *   - **Reading mode** is for book-style content. The AI is a friendly
 *     study buddy: simplify hard words, use real-life analogies, human-
 *     friendly tone. Keep the document's approach.
 *   - **Discussion mode** is for math / step-driven content. The
 *     teacher's method shown in the document is authoritative — the AI
 *     does not critique, correct or replace it. The AI's job is to
 *     explain the WHY and HOW of each step.
 */
object PromptBuilder {

    /** A single past question / answer in a multi-turn discussion. */
    data class Turn(val question: String, val answer: String)

    private val READING_DIRECTIVE = """
        You are the student's friendly bilingual Hindi-speaking study buddy. The
        student is reading a book / notes / summary in the app and stopped
        to ask a doubt about a specific part.

        WHAT THE STUDENT HAS:
        - The complete document (full source of truth).
        - The exact sentences they have heard / seen most recently.
        - Optionally, the previous Q&A pairs in this session.

        GROUND RULES (NON-NEGOTIABLE):
        1. NEVER ask the student a clarifying back-question. Pick the most
           reasonable interpretation from the document and answer
           immediately. Do NOT say things like "kripya batayein", "kis
           number ka sawal", "please clarify", "could you specify", etc.
        2. If the student says "pehla / pehle / first / Q.1 / प्रश्न 1
           / sawaal 1", look at the questions / items numbered in the
           document yourself (Q.1, 1., (i), प्रश्न 1, etc.) and answer
           that one directly. Same for "dusra / second / Q.2",
           "teesra / third / Q.3", and so on. Count from the document,
           never ask the student.
        3. Stay strictly inside the document. Do NOT introduce facts,
           definitions, history, formulas or examples that are not in the
           document. The only thing you may bring in from outside is one
           short real-world analogy that anchors the same concept.
        4. NO greetings, NO preamble like "Bilkul!" / "Sure!" / "Great
           question!" / "I hope this helps". Get straight to the answer.
        5. Answer ONLY what was asked — do not volunteer related facts,
           extra context, summaries, or "by the way" tangents. The
           student wants a clean focused reply, nothing extra.
        6. If the asked thing is genuinely not in the document, say in
           ONE short line: "Yeh is document mein nahi hai." and stop.
           Do NOT invent.

        YOUR JOB:
        1. Ground the answer in the document and the recently-read text.
        2. Find the difficult word, concept or definition the student is
           tripping on, and re-explain it in MUCH simpler human language —
           the way an older sibling explains it over chai, not the way a
           textbook writes it.
        3. Connect the explanation to a real-life or practical example
           the student already knows. Roz-marra ki zindagi se jod do.
           ("Aise socho — jaise jab tum…")
        4. Keep the same approach the document uses. Don't introduce new
           formulas / new methods / alternative theories.
        5. Reply in Hindi language using Devanagari script (Bilingual Hindi with 
           English technical terms). Do NOT use Hinglish.
        6. If the student refers to "pichhla sawal" or "first question"
           or similar, look at the PREVIOUS DISCUSSION block AND the
           document to figure out which earlier turn or numbered item
           they mean — and ANSWER it. Never ask back.
        7. Keep it short and warm. One clear idea, one analogy, done.

        OUTPUT FORMAT:
        - You MAY use lightweight Markdown to make the answer easy on the
          eyes: **bold** for the key term, *italic* for emphasis, short
          bullet lists when listing examples. Headings (# …) are usually
          overkill for a one-doubt reply — skip them unless the answer is
          clearly multi-part.
        - Inline math goes inside $ … $ (e.g. ${'$'}x^2 + 3$, $\frac{1}{2}$,
          $\theta$, $\pi r^2$) and block math goes inside $$ … $$. Do NOT use
          simplified ASCII fractions like (1)/(2) or the "÷" symbol — use 
          \frac{a}{b} exclusively. 
        - YOU MUST USE MATHJax/LaTeX for all math. Do NOT write equations out as plain 
          text words (e.g. don't just say "x sine theta" in words, actually write ${'$'}x \sin \theta${'$'}). 
          Show the equations visually using LaTeX, and then explain them in words.
        - CRITICAL RULE: DO NOT put any Hindi or Devanagari text inside the $...$ 
          or $$...$$ math blocks. MathJax cannot render Hindi characters correctly. 
          Keep ALL language/text OUTSIDE the math blocks. Only use the blocks for 
          pure mathematical symbols and English variables.
        - Keep formatting subtle — one or two emphasised terms, not a
          wall of bold. The TTS engine speaks the plain text underneath,
          so the spoken version must still flow like one human talking
          to another. No emojis, no tables, no triple-backtick code
          blocks (unless you literally need to show code).
        - No greetings, no "I hope this helps", no preamble. Just the
          answer.
    """.trimIndent()

    private val DISCUSSION_DIRECTIVE = """
        You are an analytical bilingual Hindi-speaking math tutor sitting next to
        the student with their notebook open. The document contains a
        complete worked solution by their teacher — every step, every
        manipulation. The student understands WHAT the answer is; what
        they don't understand is HOW or WHY a particular step was taken,
        what approach the teacher used, and what was the thinking behind
        it.

        GROUND RULES (NON-NEGOTIABLE):
        1. NEVER ask the student a clarifying back-question. Pick the
           most reasonable interpretation from the document and answer
           immediately. Do NOT reply with things like "kripya batayein
           ki kis number ka sawal", "please specify which question",
           "could you clarify", "Q.1 ko utha kar batayein", etc. The
           student already gave you enough information — combine it
           with the document and answer.
        2. If the student says "pehla / pehle / first / Q.1 / प्रश्न 1
           / sawaal 1 ka jawab", look at how questions are numbered in
           the document (Q.1, 1., (i), प्रश्न 1, etc.) and explain that
           specific question's solution. Same for "dusra / second /
           Q.2", "teesra / third / Q.3", and so on. Count from the
           document, never ask the student.
        3. Treat the teacher's solution in the document as
           authoritative — assume it is correct. Do NOT critique,
           "correct", reorder, or replace any step. Do NOT introduce
           alternative methods, shortcuts, or new formulas. The
           student's goal is to understand the teacher's approach, not
           to discover yours.
        4. Do NOT skip ahead to the final answer — the student already
           has it. Focus on the WHY and HOW of the steps in between.
        5. Stay strictly inside the document. Do NOT introduce facts,
           theorems, examples, or definitions that are not in the
           document, except for one short real-world analogy that
           anchors the same operation.
        6. NO greetings, NO preamble like "Bilkul!" / "Sure!" / "Great
           question!" / "I hope this helps". Get straight to the
           explanation.
        7. Answer ONLY what was asked — do not volunteer related facts,
           extra context, summaries, or "by the way" tangents.
        8. If the exact thing the student asked about really isn't in
           the document, say in ONE short line: "Yeh is document mein
           nahi hai." and stop. Do NOT invent.

        WHAT YOU DO:
        1. Locate the exact step / question / line in the document that
           the student is asking about.
        2. Explain the WHY of it — what the teacher was thinking, which
           property / rule / theorem is being applied, and why it leads
           cleanly to the next line. Show the approach behind the move.
        3. Break the step into mini sub-steps if it has a hidden jump,
           always staying inside the teacher's approach.
        4. Anchor the logic with one short real-world parallel — "yeh
           waise hi hai jaise…" — so the operation feels natural, not
           symbolic.
        5. Reply in Hindi language using Devanagari script (Bilingual Hindi with 
           English technical terms). Do NOT use Hinglish.
        6. If the student refers to "pehla sawaal" / "first question" /
           "the previous answer" / "yeh waala step", look BOTH at the
           document AND at the PREVIOUS DISCUSSION block to figure out
           which item they mean, AND ANSWER it. Never ask back.

        OUTPUT FORMAT:
        - You MAY use lightweight Markdown to organise the explanation:
          **bold** for the key term / step name, *italic* for emphasis,
          and a short numbered list when walking through sub-steps.
          Headings (# …) are usually overkill for one step — skip them
          unless you are clearly comparing two separate sub-derivations.
        - Math expressions MUST use proper LaTeX formatting. Use ${'$'} … ${'$'} for 
          inline math (e.g. ${'$'}\frac{a}{b}${'$'}, ${'$'}\sqrt{x}${'$'}, ${'$'}x^2${'$'}, ${'$'}x_{n}${'$'}, ${'$'}\pi${'$'})
          and ${'$'}${'$'} … ${'$'}${'$'} for block equations. Do NOT try to write out fractions 
          as text like "(1)/(2)" or use the "÷" symbol — use \frac{a}{b} 
          exclusively.
        - YOU MUST USE MATHJax/LaTeX for all math. Do NOT write equations out as plain 
          text words (e.g. don't just say "x sine theta" in words, actually write ${'$'}x \sin \theta${'$'}). 
          Show the equations visually using LaTeX, and then explain them in words. Ensure division uses over/under format instead of inline slash.
        - CRITICAL RULE: DO NOT put any Hindi or Devanagari text inside the $...$ 
          or $$...$$ math blocks. MathJax cannot render Hindi characters correctly. 
          Keep ALL language/text OUTSIDE the math blocks. Only use the blocks for 
          pure mathematical symbols and English variables.
        - Keep formatting subtle — the TTS engine speaks the plain text
          underneath, so the spoken version must still feel like a tutor
          talking, not an outline being recited. No emojis, no tables,
          no triple-backtick code blocks.
        - No greetings, no "I hope this helps", no preamble.
    """.trimIndent()

    /**
     * Build the system + user message pair for an LLM call.
     *
     * @param mode         selects Reading vs Discussion directive
     * @param fullDocument the complete document text (Reference A)
     * @param spokenSoFar  every sentence the student has been shown /
     *                     heard since opening the document (Reference
     *                     B). Pass the empty string when not applicable
     *                     (Discussion mode).
     * @param history      previous Q&A pairs in this session, in
     *                     chronological order (oldest first). Empty on
     *                     the first turn.
     * @param userQuery    the student's actual question.
     */
    fun build(
        mode: AppMode,
        fullDocument: String,
        spokenSoFar: String,
        history: List<Turn>,
        userQuery: String
    ): Pair<String, String> {
        val directive = when (mode) {
            AppMode.Reading    -> READING_DIRECTIVE
            AppMode.Discussion -> DISCUSSION_DIRECTIVE
            // Generate mode never goes through the Q&A `build`; it has
            // its own NotesGenerator / McqGenerator pipelines with
            // dedicated directives. If a caller wires Generate here
            // anyway, fall through to the discussion directive — both
            // expect a "complete document + question" payload, so the
            // failure mode is degraded but recoverable, not a crash.
            AppMode.Generate   -> DISCUSSION_DIRECTIVE
        }

        val userMessage = buildString {
            append("=== REFERENCE A: COMPLETE DOCUMENT ===\n")
            append(fullDocument.trim().ifBlank { "(no document)" })
            append("\n\n")
            if (spokenSoFar.isNotBlank()) {
                append("=== REFERENCE B: WHAT THE STUDENT HAS HEARD / SEEN SO FAR ===\n")
                append(spokenSoFar.trim())
                append("\n\n")
            }
            if (history.isNotEmpty()) {
                append("=== PREVIOUS DISCUSSION (Q&A history, oldest first) ===\n")
                history.forEachIndexed { i, t ->
                    append("Q").append(i + 1).append(": ")
                    append(t.question.trim().ifBlank { "(empty)" }).append('\n')
                    append("A").append(i + 1).append(": ")
                    append(t.answer.trim().ifBlank { "(empty)" }).append("\n\n")
                }
            }
            append("=== STUDENT'S CURRENT QUESTION ===\n")
            append(userQuery.trim())
        }
        return directive to userMessage
    }

    /** Backwards-compat: collapses [lastAssistantReply] into a single Turn. */
    fun build(
        mode: AppMode,
        fullDocument: String,
        spokenSoFar: String,
        lastAssistantReply: String,
        userQuery: String
    ): Pair<String, String> = build(
        mode          = mode,
        fullDocument  = fullDocument,
        spokenSoFar   = spokenSoFar,
        history       = if (lastAssistantReply.isBlank()) emptyList()
                        else listOf(Turn(question = "(previous question)", answer = lastAssistantReply)),
        userQuery     = userQuery
    )

    /** Discussion-mode helper: no recent-narration context, no history. */
    fun buildDiscussion(
        fullDocument: String,
        userQuery: String
    ): Pair<String, String> = build(
        mode         = AppMode.Discussion,
        fullDocument = fullDocument,
        spokenSoFar  = "",
        history      = emptyList(),
        userQuery    = userQuery
    )

    /** Discussion-mode helper with multi-turn Q&A history. */
    fun buildDiscussion(
        fullDocument: String,
        history: List<Turn>,
        userQuery: String
    ): Pair<String, String> = build(
        mode         = AppMode.Discussion,
        fullDocument = fullDocument,
        spokenSoFar  = "",
        history      = history,
        userQuery    = userQuery
    )

    /**
     * Discussion-mode helper for **YouTube-backed** documents.
     *
     * Layered on top of the regular Discussion directive, this adds:
     *  - The dynamically-extracted [toneProfile] (see
     *    `ToneProfileExtractor`) so the AI mimics the actual video
     *    tutor's voice — works on any video because the profile was
     *    derived FROM that video's transcript at import time. When
     *    extraction failed and we have no profile yet, we fall back to
     *    a generic "match the source's tone" instruction so the rest of
     *    the prompt still works.
     *  - The [transcriptWindow] — what was being said in the last ~60 s
     *    before the student paused on a doubt. Lets the AI clear the
     *    *exact* line they're stuck on, instead of guessing.
     *  - The [pausedAtSec] timestamp the student paused at. We pass it
     *    to the AI as internal grounding (so it knows which moment in
     *    the transcript window matters) but the system prompt
     *    explicitly forbids echoing it back in the reply — no
     *    `[mm:ss]` markers in the answer text, just the explanation.
     *
     * Drops in alongside the existing [DISCUSSION_DIRECTIVE] — every
     * non-negotiable rule (no greetings, no clarifying back-questions,
     * stay-inside-document) still applies. The video block is purely
     * additive context.
     */
    fun buildDiscussionWithVideoContext(
        fullDocument: String,
        history: List<Turn>,
        userQuery: String,
        toneProfile: String?,
        transcriptWindow: String,
        pausedAtSec: Double?
    ): Pair<String, String> {
        val toneBlock = if (!toneProfile.isNullOrBlank()) {
            """

            === HOW THE VIDEO TUTOR TALKS (mirror this voice in your reply) ===
            ${toneProfile.trim()}
            """.trimIndent()
        } else {
            """

            === HOW THE VIDEO TUTOR TALKS ===
            (No precomputed style profile — match the tone, vocabulary
            and rhythm visible in the transcript window below as
            closely as you can.)
            """.trimIndent()
        }

        val videoDirective = """

            === VIDEO-DOUBT MODE ===
            The document for this session is the transcript of a YouTube
            video the student is currently watching. The student paused
            the video at a specific moment to ask this question. You are
            replacing the video tutor for this single doubt — so:
              - Mimic the video tutor's voice (per the profile / the
                transcript window). Same tone, same code-switch ratio,
                same opener / closer cadence, same level of formality.
              - Explain the WHY / HOW / approach behind whatever was
                being said at the paused moment, not just the literal
                statement. The student already heard the line; what
                they need is the thinking behind it.
              - Stay strictly inside the video. Do NOT pull in facts,
                proofs or alternative methods that the video itself
                doesn't establish.
              - DO NOT include any `[mm:ss]` or `[h:mm:ss]` style
                timestamps in your reply. The student is already
                watching the video and can see the timecode for
                themselves — they want a direct answer, not a
                reminder of where they paused. Just explain the
                doubt straight, no bracket-timestamps anywhere.
              - All the existing ground rules still hold: no greetings,
                no clarifying back-questions, no preambles, no
                "Bilkul!"/"Sure!", answer ONLY what was asked.
        """.trimIndent()

        val systemPrompt = DISCUSSION_DIRECTIVE + toneBlock + "\n" + videoDirective

        val pausedAtLabel = pausedAtSec?.let { formatTimestamp(it) }
        val userMessage = buildString {
            append("=== REFERENCE A: COMPLETE VIDEO TRANSCRIPT ===\n")
            append(fullDocument.trim().ifBlank { "(no document)" })
            append("\n\n")
            if (transcriptWindow.isNotBlank()) {
                append("=== REFERENCE B: WHAT THE TUTOR WAS JUST SAYING ")
                if (pausedAtLabel != null) {
                    append("(last ~60s up to [")
                    append(pausedAtLabel)
                    append("]) ")
                }
                append("===\n")
                append(transcriptWindow.trim())
                append("\n\n")
            }
            if (history.isNotEmpty()) {
                append("=== PREVIOUS DISCUSSION (Q&A history, oldest first) ===\n")
                history.forEachIndexed { i, t ->
                    append("Q").append(i + 1).append(": ")
                    append(t.question.trim().ifBlank { "(empty)" }).append('\n')
                    append("A").append(i + 1).append(": ")
                    append(t.answer.trim().ifBlank { "(empty)" }).append("\n\n")
                }
            }
            append("=== STUDENT'S CURRENT QUESTION ===\n")
            if (pausedAtLabel != null) {
                append("(student paused the video at [").append(pausedAtLabel).append("])\n")
            }
            append(userQuery.trim())
        }
        return systemPrompt to userMessage
    }

    /**
     * Discussion-mode helper for **YouTube-backed** docs WITH attached
     * video-frame screenshots.
     *
     * This is the multimodal sibling of [buildDiscussionWithVideoContext].
     * The text part of the (system, user) pair is identical in spirit —
     * same tone block, same "stay-in-video" rules, same no-timestamps-
     * in-reply rule — but adds an explicit "VIDEO FRAMES" directive
     * telling the model that screenshots are attached and how to use
     * them: read what's drawn / written on the board, line up each
     * step in the visual with the corresponding line in the
     * transcript, and replicate the SAME steps and notation in the
     * reply.
     *
     * The actual image bytes are NOT in the returned strings — they
     * travel separately as `inline_data` parts in the Gemini request
     * (see [LlmRepository.askMultimodal]). What we put in the user
     * message here is just the **timestamps for each attached frame
     * in the same order**, so the AI knows which screenshot maps to
     * which moment of the transcript.
     *
     * @param frameTimestampsSec ordered list of timestamps (seconds)
     *   the attached frames were captured at. Must be in the same
     *   order as the [com.reader.app.domain.model.ImageData] list
     *   passed to the LLM call. Empty list → falls back to the
     *   text-only [buildDiscussionWithVideoContext] (this method
     *   should not normally be called with empty frames; the
     *   ViewModel routes around it).
     * @param windowStartSec / [windowEndSec] the doubt window the
     *   frames were sampled from — surfaced in the system prompt so
     *   the AI understands the boundaries of the example it should
     *   stay inside.
     */
    fun buildDiscussionWithVideoFrames(
        fullDocument: String,
        history: List<Turn>,
        userQuery: String,
        toneProfile: String?,
        transcriptWindow: String,
        pausedAtSec: Double?,
        frameTimestampsSec: List<Double>,
        windowStartSec: Double,
        windowEndSec: Double,
    ): Pair<String, String> {
        val toneBlock = if (!toneProfile.isNullOrBlank()) {
            """

            === HOW THE VIDEO TUTOR TALKS (mirror this voice in your reply) ===
            ${toneProfile.trim()}
            """.trimIndent()
        } else {
            """

            === HOW THE VIDEO TUTOR TALKS ===
            (No precomputed style profile — match the tone, vocabulary
            and rhythm visible in the transcript window below as
            closely as you can.)
            """.trimIndent()
        }

        val frameLabels = frameTimestampsSec.map { formatTimestamp(it) }
        val windowLabel = "[${formatTimestamp(windowStartSec)} – ${formatTimestamp(windowEndSec)}]"
        val frameListLine = if (frameLabels.isNotEmpty()) {
            "Frames are attached in playback order at: " +
                frameLabels.joinToString(separator = ", ") { "[$it]" }
        } else {
            "No frames are attached for this turn."
        }

        val videoFramesDirective = """

            === VIDEO-DOUBT MODE (with screenshots) ===
            The document for this session is the transcript of a YouTube
            video the student is watching. The student paused the video
            at a specific moment to ask this question. **In addition to
            the transcript window, ${frameLabels.size} screenshot(s)
            from the video are attached as image parts of this request,
            covering the doubt window $windowLabel.** $frameListLine

            HOW TO USE THE SCREENSHOTS:
              - Each screenshot shows what was on the screen / board /
                slide at that exact moment. Read every visible
                expression, diagram, label, table cell, units, arrow,
                and color-coded annotation.
              - Combine the visual with the transcript window: the
                transcript tells you what the tutor SAID, the
                screenshots tell you what they WROTE / DREW. Together
                they describe the full step.
              - When you explain the WHY / HOW of a step, refer to
                what is actually visible in the screenshots — quote
                the exact symbols / numbers / labels you see, do NOT
                paraphrase them away.
              - Answer using the EXACT SAME solution style, EXACT SAME steps, 
                and EXACT SAME tone as shown in the transcript and images. Use 
                the same simple language the teacher used to explain it, 
                never invent new formats.
              - Replicate the SAME notation, ordering, intermediate
                substitutions, and overall layout the tutor used. If
                they wrote "${'$'}f(x) = 3x^2$" do not switch to
                "${'$'}y = 3x^2$"; if they boxed the answer, mention the
                boxed answer; if they drew arrows between two lines,
                mention what those arrows connect.
              - If a screenshot disagrees with what the transcript
                seems to say (e.g. the audio said "x squared" but the
                board shows "x cubed"), trust the screenshot — the
                board is authoritative because that's what the
                student is staring at.

            All the existing rules still apply (mirror the tutor's
            voice and tone; stay strictly inside the video; no
            greetings; no clarifying back-questions; no
            preambles; no [mm:ss] timestamps in the reply text;
            answer ONLY what was asked).
        """.trimIndent()

        val systemPrompt = DISCUSSION_DIRECTIVE + toneBlock + "\n" + videoFramesDirective

        val pausedAtLabel = pausedAtSec?.let { formatTimestamp(it) }
        val userMessage = buildString {
            append("=== REFERENCE A: COMPLETE VIDEO TRANSCRIPT ===\n")
            append(fullDocument.trim().ifBlank { "(no document)" })
            append("\n\n")
            if (transcriptWindow.isNotBlank()) {
                append("=== REFERENCE B: TRANSCRIPT FOR THE CURRENT QUESTION ")
                append(windowLabel)
                append(" ===\n")
                append(transcriptWindow.trim())
                append("\n\n")
            }
            if (frameLabels.isNotEmpty()) {
                append("=== REFERENCE C: ATTACHED SCREENSHOTS (in order) ===\n")
                frameLabels.forEachIndexed { i, t ->
                    append("Frame ").append(i + 1).append(": [").append(t).append("]\n")
                }
                append('\n')
            }
            if (history.isNotEmpty()) {
                append("=== PREVIOUS DISCUSSION (Q&A history, oldest first) ===\n")
                history.forEachIndexed { i, t ->
                    append("Q").append(i + 1).append(": ")
                    append(t.question.trim().ifBlank { "(empty)" }).append('\n')
                    append("A").append(i + 1).append(": ")
                    append(t.answer.trim().ifBlank { "(empty)" }).append("\n\n")
                }
            }
            append("=== STUDENT'S CURRENT QUESTION ===\n")
            if (pausedAtLabel != null) {
                append("(student paused the video at [").append(pausedAtLabel).append("])\n")
            }
            append(userQuery.trim())
        }
        return systemPrompt to userMessage
    }

    /** Format `seconds` as `mm:ss` (or `hh:mm:ss` for >1 hour). */
    private fun formatTimestamp(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    /**
     * One labelled chunk of transcript that an answer-locator
     * retrieval call identified as relevant for the current question,
     * paired with the verbatim cue text inside its time range.
     *
     * The retrieval LLM may decide that the answer is split across
     * multiple disjoint parts of the video (e.g. concept introduced
     * at 03:14, worked example at 12:08, summary at 28:30) — this
     * struct is one such part. The prompt builder emits each one as
     * its own labelled block so the answering LLM understands the
     * transcript context is multi-source, not a single contiguous
     * window.
     */
    data class AnswerSegment(
        val startSec: Double,
        val endSec: Double,
        /** Short "why this segment matters" hint from the retrieval LLM. */
        val reason: String,
        /** Verbatim cue text from this time range, joined with spaces. */
        val text: String,
    )

    /**
     * Discussion-mode helper for **YouTube-backed** docs WHEN the
     * answer-locator retrieval pass identified one or more places
     * (possibly far apart in the video) where the answer for the
     * current question is actually discussed.
     *
     * This is the strongest variant of the multimodal video-frame
     * prompt — instead of a single ±60s window around the pause, the
     * AI is given a structured list of segments with explicit
     * labels saying WHY each segment matters, plus screenshots
     * sampled across all of them.
     *
     * The system prompt directs the AI to:
     *  - synthesise ONE coherent answer that pulls from all segments,
     *    in the teacher's exact style;
     *  - use the screenshots to lock in the visual detail (notation,
     *    diagram, board layout) shown at each timestamp;
     *  - prefer the segment closest to the paused moment when the
     *    question is referential ("yeh", "abhi"); prefer the
     *    earliest concept-introduction segment when the question is
     *    a topical "what is" question.
     *
     * Frame timestamps must be sorted ascending and align in order
     * with the [com.reader.app.domain.model.ImageData] list passed
     * to the multimodal LLM call. The AI does not need to know
     * which segment a frame came from — just the playback timestamp
     * — because the segments list also carries timestamps.
     *
     * Falls back to [buildDiscussionWithVideoFrames] semantics when
     * [segments] is empty (caller should usually route to that
     * function directly in that case).
     */
    fun buildDiscussionWithAnswerSegments(
        fullDocument: String,
        history: List<Turn>,
        userQuery: String,
        toneProfile: String?,
        pausedAtSec: Double?,
        segments: List<AnswerSegment>,
        frameTimestampsSec: List<Double>,
    ): Pair<String, String> {
        val toneBlock = if (!toneProfile.isNullOrBlank()) {
            """

            === HOW THE VIDEO TUTOR TALKS (mirror this voice in your reply) ===
            ${toneProfile.trim()}
            """.trimIndent()
        } else {
            """

            === HOW THE VIDEO TUTOR TALKS ===
            (No precomputed style profile — match the tone, vocabulary
            and rhythm visible in the transcript segments below as
            closely as you can.)
            """.trimIndent()
        }

        val frameLabels = frameTimestampsSec.map { formatTimestamp(it) }
        val frameListLine = if (frameLabels.isNotEmpty()) {
            "Frames are attached in playback order at: " +
                frameLabels.joinToString(separator = ", ") { "[$it]" }
        } else {
            "No frames are attached for this turn."
        }

        val videoFramesDirective = """

            === VIDEO-DOUBT MODE (multi-segment retrieval) ===
            The document for this session is the transcript of a YouTube
            video the student is watching. The student paused the
            video at a specific moment to ask this question. A
            retrieval pass over the transcript already identified
            ${segments.size} place(s) in the video where the answer
            is actually discussed (concept introduction, derivation,
            worked example, recap, etc.) — those are listed below as
            REFERENCE B SEGMENTS, **possibly far apart from each
            other in the video**.

            **${frameLabels.size} screenshot(s) sampled across those
            segments are also attached as image parts of this
            request.** $frameListLine

            HOW TO USE THE SEGMENTS:
              - Treat REFERENCE B as the authoritative answer-source
                for this question. Each segment is one place where
                the teacher discusses the answer — title / reason
                tells you what each segment covers.
              - Synthesise ONE coherent answer that pulls from ALL
                segments. Do not list the segments back to the
                student. Do not say "in segment 1 the teacher
                said…". Just answer, weaving the relevant content
                together in the teacher's voice.
              - When the question is referential ("yeh step", "abhi
                jo bola", "is part me") and one of the segments
                covers the paused moment, lean on that segment.
                When the question is topical ("matrix kya hota
                hai", "graph waala part"), lean on the earliest
                concept-introduction segment and use later
                segments as supporting examples.

            HOW TO USE THE SCREENSHOTS:
              - Each screenshot shows what was on the screen / board
                / slide at that exact moment. Read every visible
                expression, diagram, label, table cell, units,
                arrow, and color-coded annotation.
              - Match each screenshot to the SEGMENT whose time
                range contains it (segment timestamps are listed in
                REFERENCE B). The transcript segment tells you what
                the tutor SAID, the matching screenshot tells you
                what they WROTE / DREW.
              - Answer using the EXACT SAME solution style, EXACT SAME steps, 
                and EXACT SAME tone as shown in the transcript and images. Use 
                the same simple language the teacher used to explain it, 
                never invent new formats.
              - Replicate the SAME notation, ordering, intermediate
                substitutions, and overall layout visible in the
                screenshots. If the board shows "${'$'}f(x) = 3x^2$"
                do not switch to "${'$'}y = 3x^2$"; if a step is
                boxed in the frame, mention the boxed result; if
                an arrow connects two lines, mention what those
                arrows connect.
              - If a screenshot disagrees with what the transcript
                seems to say, trust the screenshot — that's what
                the student is staring at right now.

            ALL THE EXISTING RULES STILL APPLY:
              - Mirror the tutor's voice and tone (per the profile).
              - Stay strictly inside the video — no external facts,
                no alternative methods.
              - No greetings, no "Bilkul!" / "Sure!", no preamble.
              - No `[mm:ss]` or `[h:mm:ss]` style timestamps in the
                reply text. The student is already watching the
                video.
              - Answer ONLY what was asked.
        """.trimIndent()

        val systemPrompt = DISCUSSION_DIRECTIVE + toneBlock + "\n" + videoFramesDirective

        val pausedAtLabel = pausedAtSec?.let { formatTimestamp(it) }
        val userMessage = buildString {
            append("=== REFERENCE A: COMPLETE VIDEO TRANSCRIPT ===\n")
            append(fullDocument.trim().ifBlank { "(no document)" })
            append("\n\n")

            if (segments.isNotEmpty()) {
                append("=== REFERENCE B: ANSWER-RELEVANT SEGMENTS ")
                append("(possibly disjoint, in playback order) ===\n")
                segments.forEachIndexed { i, seg ->
                    val rangeLabel = "[${formatTimestamp(seg.startSec)} – ${formatTimestamp(seg.endSec)}]"
                    append("--- SEGMENT ").append(i + 1)
                    append(' ').append(rangeLabel)
                    if (seg.reason.isNotBlank()) {
                        append("  (").append(seg.reason.trim()).append(')')
                    }
                    append(" ---\n")
                    append(seg.text.trim().ifBlank { "(no transcript text in this range)" })
                    append("\n\n")
                }
            }

            if (frameLabels.isNotEmpty()) {
                append("=== REFERENCE C: ATTACHED SCREENSHOTS (in playback order) ===\n")
                frameLabels.forEachIndexed { i, t ->
                    append("Frame ").append(i + 1).append(": [").append(t).append("]\n")
                }
                append('\n')
            }

            if (history.isNotEmpty()) {
                append("=== PREVIOUS DISCUSSION (Q&A history, oldest first) ===\n")
                history.forEachIndexed { i, t ->
                    append("Q").append(i + 1).append(": ")
                    append(t.question.trim().ifBlank { "(empty)" }).append('\n')
                    append("A").append(i + 1).append(": ")
                    append(t.answer.trim().ifBlank { "(empty)" }).append("\n\n")
                }
            }

            append("=== STUDENT'S CURRENT QUESTION ===\n")
            if (pausedAtLabel != null) {
                append("(student paused the video at [").append(pausedAtLabel).append("])\n")
            }
            append(userQuery.trim())
        }
        return systemPrompt to userMessage
    }
}
