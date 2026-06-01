package com.reader.app.domain.youtube

import com.reader.app.data.repository.LlmRepository
import com.reader.app.domain.model.ApiConfig

/**
 * Derives a "teaching-style profile" from a YouTube video's transcript
 * via a single LLM call.
 *
 * The profile is a 1–2 paragraph snapshot of HOW the video's narrator
 * talks — tone, vocabulary, sentence rhythm, opener / closer phrases,
 * Hindi-English code-switch ratio, characteristic filler words, energy
 * level, the kinds of analogies they reach for. The Discussion-mode
 * prompt builder feeds this back to the LLM at every Q&A so the AI's
 * answer mimics the actual video tutor's voice — works for any video,
 * Hindi or otherwise, because the profile is derived live FROM that
 * video's transcript instead of being hard-coded into the app.
 *
 * Why a separate one-shot call (instead of just feeding the LLM more
 * transcript per Q&A):
 *
 *  - **Cost & latency.** A 30-min video transcript is 25–40 KB. Pasting
 *    that AND ±60 s of cue context into every doubt-answer round wastes
 *    tokens on stuff the LLM can summarise once and reuse. One extra
 *    call at import time costs maybe 800 tokens; saves thousands per
 *    Q&A.
 *  - **Stability.** A pre-computed profile gives consistent style
 *    across answers in the same session. Otherwise the LLM might pick
 *    up different cues each turn and drift.
 *  - **Graceful degradation.** If extraction fails (network down, key
 *    revoked, model returns junk), we cache `null` and the prompt
 *    builder falls back to a generic "match the source's tone"
 *    instruction. The student still gets answers; the app just doesn't
 *    sound exactly like the tutor.
 *
 * The output is plain markdown-free Hindi/English text, ~120–200 words,
 * structured around the dimensions a downstream system prompt can
 * reference verbatim.
 */
class ToneProfileExtractor(
    private val llm: LlmRepository
) {

    /**
     * Run one LLM call to extract a tone profile from the [transcript].
     *
     * Returns `null` on any failure (empty transcript, API error,
     * blank response). The caller (UploadViewModel) treats `null` as
     * "no profile yet" and persists nothing — the prompt builder will
     * fall back to a generic style instruction.
     *
     * @param transcript the FULL flat transcript text from
     *   [YouTubeTranscriptFetcher]. We sample three representative
     *   windows from it (start / middle / end) before sending so the
     *   LLM sees how the teacher's style evolves through the video,
     *   without us having to ship the entire 30-min transcript over
     *   the wire.
     * @param cfg the user's BYOK config for the Discussion mode (so we
     *   reuse their model + key — no extra setup).
     */
    suspend fun extract(transcript: String, cfg: ApiConfig): String? {
        val cleaned = transcript.trim()
        if (cleaned.length < MIN_TRANSCRIPT_CHARS) return null
        val sampled = sampleRepresentatively(cleaned)
        val (system, user) = buildPrompt(sampled)
        val resp = llm.ask(cfg, system, user).getOrNull()?.trim().orEmpty()
        return resp.takeIf { it.length >= MIN_PROFILE_CHARS }
    }

    /**
     * Pick three windows (~[SAMPLE_WINDOW_CHARS] each) from start, middle
     * and end of the transcript. For shorter transcripts (e.g. a 3-min
     * video) we just send the whole thing — sampling adds no value.
     */
    private fun sampleRepresentatively(transcript: String): String {
        if (transcript.length <= SAMPLE_BUDGET_CHARS) return transcript
        val n = transcript.length
        val w = SAMPLE_WINDOW_CHARS
        val startWin  = transcript.substring(0, w)
        val midStart  = (n / 2 - w / 2).coerceAtLeast(0)
        val midWin    = transcript.substring(midStart, (midStart + w).coerceAtMost(n))
        val endStart  = (n - w).coerceAtLeast(0)
        val endWin    = transcript.substring(endStart, n)
        return buildString {
            append("[VIDEO OPENING]\n").append(startWin.trim()).append("\n\n")
            append("[VIDEO MIDDLE]\n").append(midWin.trim()).append("\n\n")
            append("[VIDEO ENDING]\n").append(endWin.trim())
        }
    }

    /**
     * Build the system + user pair for the tone-extraction call.
     *
     * The output schema is intentionally a flat prose paragraph — NOT
     * a JSON or bullet-list. The Discussion prompt builder pastes this
     * verbatim into its own system prompt, and free prose blends in
     * cleanly with the rest of the directive whereas a structured
     * format would jar.
     */
    private fun buildPrompt(sampledTranscript: String): Pair<String, String> {
        val system = """
            You are a meta-prompt engineer. The user will paste samples
            from a Hindi / Hinglish YouTube tutor's transcript. Your job
            is to write a SHORT teaching-style profile (1–2 paragraphs,
            120–200 words total, plain prose, no markdown, no bullet
            points, no headings) that captures HOW this tutor talks —
            so a different AI tutor can mimic the same voice when
            answering doubts about this video.

            COVER (woven into prose, not as a checklist):
            - Tone & energy (calm / peppy / matter-of-fact / dramatic).
            - Vocabulary register (simple roz-marra / textbook /
              technical / literary).
            - Hindi vs English balance — pure Hindi, mostly Hinglish,
              technical-English-with-Hindi-bridges, etc. Estimate the
              ratio.
            - Characteristic opener phrases ("toh dosto…", "dekho…",
              "chaliye shuru karte hain…") and closer phrases
              ("samajh gaye?", "is liye yeh hota hai", "aage badhte
              hain") that the tutor leans on.
            - Sentence rhythm (short and punchy / flowing and long /
              question-then-answer cadence).
            - Filler / glue words ("matlab", "yaani", "basically",
              "right?", "haan to") and how often.
            - Examples / analogy style (math-with-real-world parallels,
              cricket metaphors, food metaphors, none, etc.).
            - Formality (does the tutor address the student as "tum",
              "aap", "you", "guys", first-name, "dosto", etc.).

            DO NOT:
            - Summarise the video's content. We don't care WHAT the
              tutor is teaching — only HOW.
            - Critique the tutor.
            - Add disclaimers or "this is a sample" prefaces.
            - Use markdown, bullets, headings, JSON or quotes around the
              whole output.
            - Add greetings or sign-offs.

            Output ONLY the prose profile.
        """.trimIndent()

        val user = """
            Yeh us video ke transcript ke 3 representative samples hain
            (opening, middle, ending). In samples ke aadhar par teacher
            ki teaching-style profile likho.

            ===== TRANSCRIPT SAMPLES =====
            $sampledTranscript
            ===== END =====
        """.trimIndent()

        return system to user
    }

    companion object {
        /**
         * Below this we don't bother — short transcripts (e.g. a 30-sec
         * shorts video) don't have enough material to extract a stable
         * style profile from. The Discussion prompt builder's generic
         * fallback handles these fine.
         */
        private const val MIN_TRANSCRIPT_CHARS = 600

        /** Three windows × this size = total characters sent to the LLM. */
        private const val SAMPLE_WINDOW_CHARS = 700

        /** Cutoff at which we just send the entire transcript. */
        private const val SAMPLE_BUDGET_CHARS = SAMPLE_WINDOW_CHARS * 3

        /** Sanity check on the model's response — reject obvious junk. */
        private const val MIN_PROFILE_CHARS = 80
    }
}
