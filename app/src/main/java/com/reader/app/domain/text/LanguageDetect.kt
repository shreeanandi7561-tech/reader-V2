package com.reader.app.domain.text

/**
 * Tiny script/language sniffer used by the Generate section to keep
 * Notes (and the MCQ extractor's option fallbacks) in the SAME
 * language and SAME script as the source transcript.
 *
 * Why this exists: the LLM is perfectly capable of preserving the
 * source language, but only if you tell it the source language at
 * the *top* of the prompt. Our previous prompts buried the rule
 * inside a 6-paragraph block and the model regularly defaulted to
 * English for Hindi videos. Auto-detecting and stating the answer
 * up-front fixes that without us having to make the user pick it.
 *
 * Detector is intentionally simple — Unicode block counting on a
 * sample, no model. Three buckets:
 *   - **Hindi (Devanagari)** — substantial Devanagari content. The
 *     LLM must keep using Devanagari script.
 *   - **Hinglish** — predominantly Latin script but the source is
 *     spoken Hindi (we can't tell from script alone, so we mark
 *     Hinglish whenever Latin dominates and the transcript is short
 *     of English-only signals; in practice the LLM treats this as
 *     "preserve the code-switching ratio you see in the transcript",
 *     which is exactly what we want).
 *   - **English** — pure Latin and no Devanagari. The detector falls
 *     through to this when the transcript looks like clean English.
 *
 * The output strings are passed VERBATIM into the LLM prompt as the
 * canonical "OUTPUT LANGUAGE" line — they're prompt copy, not labels
 * for UI, so they're in the form the model understands best.
 */
object LanguageDetect {

    /** Distinct language directives used in prompts. */
    enum class Lang(val directive: String) {
        Hindi("Hindi (Devanagari script — हिंदी)"),
        Hinglish("Hinglish (Hindi spoken in Roman script — same code-switching ratio as the transcript)"),
        English("English");
    }

    /**
     * Sample the first ~4 KB of [text] (enough for a confident
     * detection without scanning the whole document) and return the
     * most likely language bucket.
     *
     * Heuristic:
     * 1. Count Devanagari letters and Latin letters in the sample.
     * 2. If Devanagari is non-trivial (≥20 chars or ≥10% of letters)
     *    → Hindi.
     * 3. Otherwise: Latin only. Distinguish English from Hinglish
     *    using a tiny stopword vote — every transcript-script we've
     *    seen in this app starts the speaker introducing the topic,
     *    so a few Hindi function-words ("hai", "ki", "ka", "mein",
     *    "ye", "kya", "toh", "aur", "se") in the sample is a
     *    reliable Hinglish signal.
     * 4. Else → English.
     */
    fun detect(text: String): Lang {
        if (text.isBlank()) return Lang.English
        val sample = text.take(4096)

        var devanagari = 0
        var latin = 0
        for (c in sample) {
            // Devanagari block: U+0900..U+097F (covers all standard
            // Hindi consonants/vowels/marks). Vedic / extended blocks
            // are rare in conversational transcripts and not worth
            // counting.
            if (c.code in 0x0900..0x097F) devanagari++
            else if (c in 'A'..'Z' || c in 'a'..'z') latin++
        }

        val totalLetters = devanagari + latin
        if (totalLetters == 0) return Lang.English   // numbers / symbols only

        val devanagariSignificant = devanagari >= 20 || devanagari * 10 >= totalLetters
        if (devanagariSignificant) return Lang.Hindi

        // Roman-only path — distinguish Hinglish from English with
        // a stopword vote. Lowercase the sample once, then count
        // word-bounded matches. 3+ Hindi function-word hits in a 4K
        // sample is a strong Hinglish signal; English transcripts
        // we've seen score 0-1.
        val lower = sample.lowercase()
        var hinglishHits = 0
        for (word in HINGLISH_STOPWORDS) {
            // Word-boundary scan; stopwords are short so a manual
            // loop beats compiling 25 regexes once per call.
            var idx = 0
            while (true) {
                val pos = lower.indexOf(word, idx)
                if (pos < 0) break
                val before = if (pos == 0) ' ' else lower[pos - 1]
                val after = if (pos + word.length >= lower.length) ' ' else lower[pos + word.length]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) {
                    hinglishHits++
                    if (hinglishHits >= 3) return Lang.Hinglish
                }
                idx = pos + word.length
            }
        }
        return Lang.English
    }

    /**
     * Hindi function-words that almost never appear in a pure-
     * English transcript. Kept short so the scan stays cheap and
     * the false-positive rate on English text remains near-zero.
     */
    private val HINGLISH_STOPWORDS = listOf(
        " hai ", " hain ", " ki ", " ka ", " ke ", " ko ", " mein ",
        " main ", " ye ", " yeh ", " woh ", " wo ", " kya ", " kaun ",
        " toh ", " aur ", " se ", " bhi ", " nahi ", " nahin ",
        " kar ", " karna ", " karte ", " hota ", " hoga ", " raha ",
        " sahi ", " galat ", " option ",
    )
}
