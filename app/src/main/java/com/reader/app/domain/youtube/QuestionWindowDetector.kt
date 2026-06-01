package com.reader.app.domain.youtube

import kotlin.math.max
import kotlin.math.min

/**
 * Heuristically locates the START and END timestamps of the
 * "self-contained question / example" the student is currently asking
 * a doubt about, by walking the transcript outward from the moment
 * they paused.
 *
 * The Discussion-mode prompt builder used to grab a fixed ±60 s window
 * around the pause. That works for short doubts but cuts off mid-step
 * for longer worked examples — the AI then sees only half the
 * derivation and has to guess the rest, which is exactly the
 * "answer hi galat de raha hai" failure mode the user reported.
 *
 * **What this fixes:** instead of a fixed clock window, we walk
 * cue-by-cue both ways from the paused position and stop at:
 *   1. **Topic-start markers** ("next question", "agla sawal", "doosra
 *      example", "(i)", "Q.2", "1.", etc.) when walking backward, and
 *      symmetric markers when walking forward — these explicitly
 *      delimit one problem from the next in a tutoring video.
 *   2. **Silence gaps** between consecutive cues longer than
 *      [SILENCE_GAP_SEC] — speakers pausing for several seconds
 *      between problems is a strong "we just finished one and are
 *      about to start the next" signal.
 *   3. **Hard caps** ([maxLookbackSec], [maxLookaheadSec]) so a
 *      transcript with no markers and no silence still produces a
 *      bounded window.
 *
 * Pure logic, no Android imports — easy to unit-test on the JVM.
 */
object QuestionWindowDetector {

    /** Default backward cap (seconds). Two minutes covers most worked
     *  examples that span more than the previous fixed-60 s window. */
    const val DEFAULT_MAX_LOOKBACK_SEC: Double = 120.0

    /** Default forward cap. Smaller than lookback because we don't
     *  want to grab the NEXT problem the teacher is about to start. */
    const val DEFAULT_MAX_LOOKAHEAD_SEC: Double = 30.0

    /** A gap between consecutive cues longer than this is treated as a
     *  topic boundary. Tuned for tutoring videos where teachers visibly
     *  pause between problems. */
    const val SILENCE_GAP_SEC: Double = 4.0

    /**
     * Compile-once regex for "topic-start" markers — the kinds of
     * phrases a teacher uses when introducing the next problem /
     * example / step. Hindi, Hinglish AND English are all covered
     * because real-world tutoring videos code-switch constantly.
     *
     * Word boundaries `\b` keep "next" from matching inside
     * "context" / "nextexpression".
     *
     * The patterns are deliberately conservative — false negatives
     * (missing a real boundary, falling through to the time cap)
     * degrade gracefully, while false positives (cutting the window
     * too aggressively) starve the AI of context. Better to err on
     * the side of "give the AI a bit too much" than "cut off the
     * doubt mid-step".
     */
    private val TOPIC_START_PATTERNS: List<Regex> = listOf(
        // English / Hinglish
        Regex("""\bnext (question|problem|example|sawaal|sawal)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(another|let'?s start|let'?s look|moving on)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bquestion\s*(no\.?|number)?\s*\d+\b""", RegexOption.IGNORE_CASE),
        Regex("""\bq\.?\s*\d+\b""", RegexOption.IGNORE_CASE),
        // Hindi / Hinglish — devanagari + romanised
        Regex("""\bagla\b""", RegexOption.IGNORE_CASE),
        Regex("""\bagle\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdoosra\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdusra\b""", RegexOption.IGNORE_CASE),
        Regex("""\bteesra\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpehla\b""", RegexOption.IGNORE_CASE),
        Regex("""\bab dekhte\b""", RegexOption.IGNORE_CASE),
        Regex("""\bab hum\b""", RegexOption.IGNORE_CASE),
        Regex("""\bchalo (ab|next|agla)\b""", RegexOption.IGNORE_CASE),
        Regex("""अगला|दूसरा|तीसरा|पहला|प्रश्न\s*\d+|सवाल\s*\d+"""),
        // Numbered question prefixes at the start of a cue: "Q.1 ",
        // "1. ", "(i) ", "(1) ". Anchor with cue-start (^) — these
        // markers in the MIDDLE of a sentence don't mean a new topic.
        Regex("""^\s*\(?\s*[ivxIVX]+\s*\)\s*"""),
        Regex("""^\s*\(?\s*\d+\s*[\)\.]"""),
        Regex("""^\s*Q\.?\s*\d+""", RegexOption.IGNORE_CASE),
    )

    /**
     * Result of one detection run.
     *
     * @property startSec inclusive start of the doubt window. Always
     *   `>= 0` and `<= pausedAtSec`.
     * @property endSec   exclusive end. Always `>= pausedAtSec` and
     *   bounded by the last cue's end (or [pausedAtSec] +
     *   `maxLookaheadSec`, whichever comes first).
     * @property anchorIndex index of the cue we treated as "the moment
     *   the student paused on". -1 when the transcript was empty.
     */
    data class Window(
        val startSec: Double,
        val endSec: Double,
        val anchorIndex: Int,
    )

    /**
     * Detect the question window.
     *
     * @param cues          all cues for the document, sorted by `startSec`.
     * @param pausedAtSec   playhead position when the student raised
     *                      the doubt.
     * @param maxLookbackSec  hard cap on backward span.
     * @param maxLookaheadSec hard cap on forward span.
     */
    fun detect(
        cues: List<TranscriptCue>,
        pausedAtSec: Double,
        maxLookbackSec: Double = DEFAULT_MAX_LOOKBACK_SEC,
        maxLookaheadSec: Double = DEFAULT_MAX_LOOKAHEAD_SEC,
    ): Window {
        if (cues.isEmpty()) {
            // No transcript to work with → degenerate window centred
            // on pausedAtSec, capped at zero on the left so we never
            // produce a negative start.
            val start = (pausedAtSec - maxLookbackSec).coerceAtLeast(0.0)
            val end = pausedAtSec + maxLookaheadSec
            return Window(startSec = start, endSec = end, anchorIndex = -1)
        }

        val anchor = anchorCueIndex(cues, pausedAtSec)

        // Walk backward from anchor, stopping at marker / silence /
        // lookback cap.
        var leftIdx = anchor
        run backward@{
            var i = anchor - 1
            while (i >= 0) {
                val cue = cues[i]
                // Silence gap — large gap between this cue's end and
                // the next one's start signals a topic boundary.
                val nextCue = cues[i + 1]
                if (nextCue.startSec - cue.endSec >= SILENCE_GAP_SEC) {
                    break
                }
                // Time cap.
                if (pausedAtSec - cue.startSec > maxLookbackSec) {
                    break
                }
                // Marker — this cue STARTS a (the previous) topic, so
                // include it as the new left edge then stop.
                if (matchesTopicStart(cue.text)) {
                    leftIdx = i
                    break
                }
                leftIdx = i
                i--
            }
        }

        // Walk forward similarly. The forward walk stops at a marker
        // BEFORE including that cue — the next-question cue is a hint
        // that we've moved past the doubt, not part of the doubt
        // itself.
        var rightIdx = anchor
        run forward@{
            var i = anchor + 1
            while (i < cues.size) {
                val cue = cues[i]
                val prevCue = cues[i - 1]
                if (cue.startSec - prevCue.endSec >= SILENCE_GAP_SEC) {
                    break
                }
                if (cue.startSec - pausedAtSec > maxLookaheadSec) {
                    break
                }
                if (matchesTopicStart(cue.text)) {
                    break
                }
                rightIdx = i
                i++
            }
        }

        val startSec = cues[leftIdx].startSec.coerceAtMost(pausedAtSec).coerceAtLeast(0.0)
        val endSec = max(cues[rightIdx].endSec, pausedAtSec)
        return Window(startSec = startSec, endSec = endSec, anchorIndex = anchor)
    }

    /**
     * Index of the cue whose interval `[startSec, endSec)` contains
     * [pausedAtSec]. If the pause falls in a gap between two cues, the
     * cue immediately BEFORE the pause is returned (so we anchor on
     * "what the teacher just finished saying" rather than "what they
     * are about to say"). If the pause is before every cue, returns 0.
     */
    private fun anchorCueIndex(cues: List<TranscriptCue>, pausedAtSec: Double): Int {
        if (pausedAtSec <= cues.first().startSec) return 0
        // Linear scan — transcripts in this app rarely exceed a few
        // hundred cues, and we're already on a coroutine off the main
        // thread by the time we get here.
        for (i in cues.indices.reversed()) {
            if (cues[i].startSec <= pausedAtSec) return i
        }
        return 0
    }

    private fun matchesTopicStart(text: String): Boolean {
        if (text.isBlank()) return false
        for (re in TOPIC_START_PATTERNS) {
            if (re.containsMatchIn(text)) return true
        }
        return false
    }

    /** Truncate or pad to `[lo, hi]`. Helper for callers. */
    fun clampWindow(start: Double, end: Double, lo: Double = 0.0, hi: Double = Double.MAX_VALUE): Pair<Double, Double> =
        max(lo, start) to min(hi, end)
}
