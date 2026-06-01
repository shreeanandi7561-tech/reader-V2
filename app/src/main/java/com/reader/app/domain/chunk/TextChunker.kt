package com.reader.app.domain.chunk

/**
 * Document-to-paragraph chunker.
 *
 * The unit of TTS playback is a paragraph-sized chunk (~50–80 words),
 * NOT a single sentence. The student found single sentences too choppy
 * — "bahut chhote chhote lines pick kar raha hai". Now each chunk is
 * a flowing block of prose that ends on a real sentence boundary so
 * the last spoken word is always a natural close (है / थी / था / हूँ /
 * सकता / chahiye / `।` / `.` / `?` / `!`).
 *
 * Two-stage pipeline:
 *
 *   1. **Sentence split** — same composite regex as before. Hindi prose
 *      often omits `।`, so we also break after well-known terminal
 *      verbs (है / हैं / था / थी / हूँ / गया / etc.) when followed by
 *      whitespace + a likely sentence-start character. Each piece in
 *      this list ALREADY ends on a natural close.
 *
 *   2. **Paragraph greedy-pack** — sentences are then glued together
 *      until the running word count enters the [TARGET_MIN_WORDS,
 *      TARGET_MAX_WORDS] band. The chunk is emitted at the FIRST
 *      sentence boundary that lands at or above the minimum word
 *      target. Because the boundary is a real sentence end, the
 *      last word of every chunk is always a clean close.
 *
 *   3. **Hard cap** — if a single sentence is longer than
 *      [HARD_MAX_CHARS] (rare — e.g. a wall-of-text paragraph with no
 *      `।`), it is force-split on the nearest whitespace.
 */
object TextChunker {

    /**
     * Per the user spec: each chunk should be a paragraph-sized block
     * of roughly 50–70 words. We allow a small overshoot (up to 80) so
     * we can always stop on a real sentence boundary instead of cutting
     * mid-thought.
     */
    private const val TARGET_MIN_WORDS = 50
    private const val TARGET_MAX_WORDS = 80

    /** Safety net for runaway sentences with no internal boundary. */
    private const val HARD_MAX_CHARS = 1200

    /** Any run of whitespace (including newlines, tabs, NBSP). */
    private val ANY_WHITESPACE = Regex("\\s+")

    /**
     * Hindi terminal verbs that very reliably end a sentence in everyday
     * prose. Used as a fallback boundary when the writer omitted `।`.
     * The user explicitly listed है / थी / हूँ / था in the latest spec
     * — those are all in here.
     */
    private val HINDI_TERMINAL_VERBS = listOf(
        "है", "हैं", "हूँ", "हूं", "हो",
        "था", "थे", "थी", "थीं",
        "गया", "गई", "गए", "गयी", "गये",
        "किया", "की", "किये", "कीं",
        "करें", "करेंगे", "करूँगा", "करूंगा", "करूंगी",
        "होगा", "होगी", "होंगे",
        "चाहिए", "दिया", "दी", "दे",
        "सकता", "सकती", "सकते"
    )

    /**
     * Composite boundary regex with three alternatives, each of which keeps
     * the boundary character with the preceding sentence via a positive
     * lookbehind:
     *
     *   - After Devanagari purnaviram `।` (with or without trailing space).
     *   - After ASCII `.!?` followed by a space and a likely sentence-start.
     *     "Likely sentence-start" = a Latin capital letter or any Devanagari
     *     character (covers Hindi sentences). This avoids splitting on
     *     decimals like "3.14" or abbreviations like "Dr. Sharma".
     *   - After a Hindi terminal verb followed by whitespace and a likely
     *     sentence-start. We deliberately do NOT use `\b` here — Java's
     *     default `\w` is ASCII-only, so `\b(?=ह...)` never matches inside
     *     pure Hindi text. The `\s+(?=Devanagari/Latin-cap)` lookahead
     *     prevents false splits on words that merely begin with the same
     *     letters as a verb (e.g. `हैरत`).
     */
    private val SENTENCE_BOUNDARY: Regex = run {
        val verbs = HINDI_TERMINAL_VERBS.joinToString("|")
        Regex(
            "(?<=।)\\s*" +
                "|(?<=[.!?])\\s+(?=[A-Z\\u0900-\\u097F])" +
                "|(?<=(?:$verbs))\\s+(?=[A-Z\\u0900-\\u097F])"
        )
    }

    /**
     * Returns paragraph-sized chunks of [raw], each ending at a real
     * sentence boundary so the last spoken word is always a natural
     * close (है / थी / था / `।` / `.` etc.).
     */
    fun chunk(raw: String): List<String> {
        val flat = flatten(raw)
        if (flat.isEmpty()) return emptyList()

        // Stage 1: split into sentences. Each piece already ends on a
        // natural close (lookbehind keeps the boundary char with the
        // preceding sentence).
        val sentences = flat.split(SENTENCE_BOUNDARY)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { if (it.length > HARD_MAX_CHARS) forceSplit(it) else listOf(it) }
        if (sentences.isEmpty()) return emptyList()

        // Stage 2: greedy-pack sentences into paragraph chunks of
        // ~TARGET_MIN..TARGET_MAX words, always emitting on a sentence
        // boundary.
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var bufWords = 0

        for (sentence in sentences) {
            val sWords = wordCount(sentence)

            if (buf.isEmpty()) {
                buf.append(sentence)
                bufWords = sWords
                continue
            }

            // We are currently sitting on a real sentence boundary
            // (because everything in `buf` came from `sentences`,
            // which split on those boundaries). The decision is just
            // whether to flush now or absorb the next sentence too.
            val absorbedWords = bufWords + sWords
            val canAbsorb = absorbedWords <= TARGET_MAX_WORDS
            val needsMore = bufWords < TARGET_MIN_WORDS

            if (canAbsorb || needsMore) {
                buf.append(' ').append(sentence)
                bufWords = absorbedWords
            } else {
                out += buf.toString()
                buf.setLength(0)
                buf.append(sentence)
                bufWords = sWords
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    /**
     * Strip ALL line breaks and collapse every whitespace run into a single
     * space. The result is the same document a screen reader would present
     * if you removed every layout-only newline — i.e. one long sentence
     * stream.
     */
    fun flatten(raw: String): String =
        raw.replace(ANY_WHITESPACE, " ").trim()

    private fun wordCount(s: String): Int {
        if (s.isBlank()) return 0
        return s.trim().split(ANY_WHITESPACE).size
    }

    /**
     * Last-resort splitter for a single "sentence" longer than
     * [HARD_MAX_CHARS] (e.g. a wall of text with no `।`). Cuts on the
     * nearest whitespace so we never break a word.
     */
    private fun forceSplit(text: String): List<String> {
        val parts = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val end = (i + HARD_MAX_CHARS).coerceAtMost(text.length)
            val cut = if (end >= text.length) end
            else text.lastIndexOf(' ', end).takeIf { it > i + HARD_MAX_CHARS / 2 } ?: end
            val piece = text.substring(i, cut).trim()
            if (piece.isNotEmpty()) parts += piece
            i = cut
        }
        return parts
    }
}
