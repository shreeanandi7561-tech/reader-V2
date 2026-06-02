package com.reader.app.domain.youtube

import kotlin.math.max
import kotlin.math.min

/**
 * Picks **N timestamps** inside a doubt window at which to capture
 * video frames, so the multimodal Gemini call gets a representative
 * visual sequence of the board / slides instead of a single still.
 *
 * Why this exists:
 *  - For very short doubts (e.g. one cue, ~5 s) a single frame at the
 *    paused moment is the right answer — more would be redundant.
 *  - For longer worked examples (multiple steps spread over ~60-120 s)
 *    a single frame can't show the full derivation. We need
 *    "intro + middle steps + final state" to let the AI mimic the
 *    teacher's exact written sequence.
 *  - Uniform clock-spacing is cheap but can land mid-utterance,
 *    capturing the teacher in motion (blurred frame) or a transient
 *    pen swirl. Aligning to **cue boundaries** when possible gives
 *    visually stable frames where the teacher has just finished
 *    writing something.
 *
 * Algorithm:
 *  1. Pick the target sample count N from the window length:
 *      - `< 8 s`         → 1 frame at the anchor / pause moment
 *      - `8 - 25 s`      → 2 frames (start, end)
 *      - `25 - 60 s`     → 3 frames (start, middle, end)
 *      - `60 - 120 s`    → 4 frames
 *      - `>= 120 s`      → 5 frames (capped — Gemini accepts more, but
 *                          beyond this the prompt becomes huge and
 *                          unique value diminishes for a single doubt)
 *  2. Compute N evenly-spaced "ideal" timestamps in `[startSec,
 *     endSec)`.
 *  3. For each ideal timestamp, snap to the nearest cue boundary
 *     (start OR end of the closest cue) within a small slack — this
 *     biases the snapshot to the moment the teacher just finished
 *     writing. Skip cues that are too far away (no useful boundary
 *     nearby) and keep the ideal timestamp.
 *  4. Always include the **anchor** (the moment the student paused)
 *     as one of the timestamps so the doubt-moment view is never
 *     missed, replacing whichever sampled timestamp is closest.
 *
 * Pure logic — no Android imports — easy to unit-test on the JVM.
 */
object FrameTimestampSampler {

    /** Hard cap on samples per doubt. See class kdoc, step 1. */
    const val MAX_FRAMES: Int = 5

    /** Slack (seconds) around an "ideal" timestamp inside which we
     *  may snap to a cue boundary. Beyond this, keep the ideal. */
    private const val SNAP_SLACK_SEC: Double = 1.5

    /**
     * @param cues          full transcript for the doc, sorted by startSec.
     *                      Used only for boundary-snapping; an empty
     *                      list disables snapping (caller still gets
     *                      evenly-spaced samples).
     * @param startSec      start of the doubt window (inclusive).
     * @param endSec        end of the doubt window (exclusive).
     * @param anchorSec     the paused-at moment — guaranteed to be one
     *                      of the returned timestamps (replacing the
     *                      closest sample, never appended outside the
     *                      target count).
     * @param maxFrames     hard upper bound on the returned list. Pass
     *                      a smaller value (e.g. 3) when running on a
     *                      tight token budget. Default [MAX_FRAMES].
     * @return ascending list of timestamps in `[startSec, endSec)`.
     *         Always at least 1 element (the anchor) when the inputs
     *         are sensible. Empty when `endSec <= startSec`.
     */
    fun sample(
        cues: List<TranscriptCue>,
        startSec: Double,
        endSec: Double,
        anchorSec: Double,
        maxFrames: Int = MAX_FRAMES,
    ): List<Double> {
        if (endSec <= startSec) return emptyList()
        val span = endSec - startSec
        val n = pickFrameCount(span).coerceAtMost(maxFrames).coerceAtLeast(1)

        // Step 2: evenly-spaced ideals. For n=1 this lands at midpoint.
        val ideals: DoubleArray = if (n == 1) {
            doubleArrayOf(midpoint(startSec, endSec))
        } else {
            DoubleArray(n) { i ->
                // i / (n-1) ∈ [0, 1] — produces start, …, end.
                startSec + span * (i.toDouble() / (n - 1).toDouble())
            }
        }

        // Step 3: snap each ideal to nearest cue boundary within slack.
        val snapped = DoubleArray(n) { i -> snapToCueBoundary(cues, ideals[i]) }

        // Step 4: replace whichever sample is closest to the anchor
        // with the anchor itself, so the doubt-moment is always one
        // of the returned timestamps. We keep the result sorted +
        // de-duplicated below.
        val anchorClamped = anchorSec.coerceIn(startSec, endSec)
        val closestIdx = (0 until n).minBy { kotlin.math.abs(snapped[it] - anchorClamped) }
        snapped[closestIdx] = anchorClamped

        return snapped.toList()
            .distinct()
            .sorted()
            // Final clamp: the anchor coercion or a snap could push
            // a timestamp outside the half-open window in edge cases.
            // Bring everything back inside [startSec, endSec).
            .map { it.coerceIn(startSec, max(startSec, endSec - 1e-3)) }
            .distinct()
    }

    /**
     * Sample exactly **5 frames around the paused moment**, used
     * when the answer to the student's doubt sits AT the pause
     * itself (no earlier / later concept-introduction segment was
     * needed).
     *
     * Layout (in playback order):
     *   - 2 frames BEFORE the pause (cue-boundary-snapped from
     *     transcript)
     *   - 1 frame AT the pause (exact moment the student stopped)
     *   - 2 frames AFTER the pause (cue-boundary-snapped from
     *     transcript)
     *
     * The "before" / "after" candidates are picked from the cue
     * boundaries closest to the pause: typically the cue START of
     * the cue spanning the pause, the cue START of the cue right
     * before that, etc. This gives the AI 5 stable still-frames
     * showing the teacher's screen evolution centred on exactly
     * the moment the student got stuck.
     *
     * Falls back gracefully:
     *  - When there aren't enough cue boundaries within
     *    [PAUSE_LOOKBEHIND_SEC] before the pause, the missing
     *    "before" slots are filled with proportional offsets
     *    (pause - 8s, pause - 4s).
     *  - Same on the "after" side using [PAUSE_LOOKAHEAD_SEC].
     *  - When [docTotalSec] is provided (> 0), all returned
     *    timestamps are clamped to `[0, docTotalSec)` so we never
     *    seek past the end of the video.
     *
     * @param cues          full transcript, sorted by startSec.
     * @param pausedAtSec   the moment the student paused.
     * @param docTotalSec   total video duration in seconds. Pass
     *                      0 (the default) when unknown — clamping
     *                      then only enforces non-negative.
     * @return up to 5 ascending timestamps centred on [pausedAtSec].
     *         Always non-empty (at minimum returns just the
     *         [pausedAtSec] itself).
     */
    fun sampleAroundPause(
        cues: List<TranscriptCue>,
        pausedAtSec: Double,
        docTotalSec: Double = 0.0,
    ): List<Double> {
        val pause = pausedAtSec.coerceAtLeast(0.0)
        val upperBound = if (docTotalSec > 0.0) docTotalSec else Double.MAX_VALUE

        // Step 1: collect cue-boundary candidates BEFORE the pause,
        // newest-first (i.e. closest to pause first).
        val before = cueBoundariesBefore(cues, pause)

        // Step 2: collect cue-boundary candidates AFTER the pause,
        // earliest-first (closest to pause first).
        val after = cueBoundariesAfter(cues, pause)

        // Step 3: pick up to 2 "before" slots — prefer real cue
        // boundaries; fall back to fixed-offset moments.
        val twoBefore = pickTwoSpread(
            candidates = before,
            anchor     = pause,
            preferred  = doubleArrayOf(pause - 4.0, pause - 8.0),
            lookSec    = PAUSE_LOOKBEHIND_SEC,
        )

        // Step 4: pick up to 2 "after" slots, mirror logic.
        val twoAfter = pickTwoSpread(
            candidates = after,
            anchor     = pause,
            preferred  = doubleArrayOf(pause + 4.0, pause + 8.0),
            lookSec    = PAUSE_LOOKAHEAD_SEC,
        )

        // Step 5: combine, clamp, dedupe, sort.
        val candidates = mutableListOf<Double>()
        candidates.add(pause) // Put anchor first so it's always preserved
        candidates.addAll(twoBefore)
        candidates.addAll(twoAfter)

        // Deduplicate using a minimum gap (2.0s) so we don't capture the same frame/scene twice
        val minGap = 2.0
        val out = ArrayList<Double>()
        for (ts in candidates.map { it.coerceIn(0.0, upperBound) }) {
            if (out.none { kotlin.math.abs(it - ts) < minGap }) {
                out.add(ts)
            }
        }

        // Step 6: Guarantee EXACTLY 5 frames. The user's prompt strongly expects
        // exactly 5 images to be sent (2 before, 1 at, 2 after equivalent) and 
        // will break if deduplication reduces this count.
        var offsetMultiplier = 1.0
        while (out.size < 5) {
            val candidatePos = (pause + (offsetMultiplier * 3.0)).coerceIn(0.0, upperBound)
            if (out.none { kotlin.math.abs(it - candidatePos) < minGap }) {
                out.add(candidatePos)
            }
            if (out.size >= 5) break

            val candidateNeg = (pause - (offsetMultiplier * 3.0)).coerceIn(0.0, upperBound)
            if (out.none { kotlin.math.abs(it - candidateNeg) < minGap }) {
                out.add(candidateNeg)
            }
            
            offsetMultiplier += 1.0
            
            // Safety break just in case bounds make it impossible
            if (offsetMultiplier > 50.0) {
                var fallbackOffset = 0.5
                while (out.size < 5 && fallbackOffset < 10.0) {
                    val cExtPos = (pause + fallbackOffset).coerceIn(0.0, upperBound)
                    if (!out.contains(cExtPos)) out.add(cExtPos)
                    if (out.size >= 5) break
                    val cExtNeg = (pause - fallbackOffset).coerceIn(0.0, upperBound)
                    if (!out.contains(cExtNeg)) out.add(cExtNeg)
                    fallbackOffset += 0.5
                }
                break
            }
        }

        return out.sorted().take(5) // Ensure strictly 5
    }

    /** Window length → sample count. See class kdoc step 1. */
    private fun pickFrameCount(spanSec: Double): Int = when {
        spanSec < 8.0   -> 1
        spanSec < 25.0  -> 2
        spanSec < 60.0  -> 3
        spanSec < 120.0 -> 4
        else            -> 5
    }

    /** How far back from the pause we'll look for cue boundaries
     *  in the [sampleAroundPause] mode. */
    private const val PAUSE_LOOKBEHIND_SEC: Double = 25.0

    /** How far forward from the pause we'll look for cue boundaries
     *  in the [sampleAroundPause] mode. */
    private const val PAUSE_LOOKAHEAD_SEC: Double = 25.0

    /**
     * From [candidates] (sorted by distance-from-anchor — closest
     * first), pick up to TWO timestamps that are reasonably spread
     * apart so the AI doesn't see two near-identical frames. When
     * fewer than 2 cue-boundaries fall within [lookSec] of the
     * anchor, fill the remaining slot(s) using [preferred] offsets.
     *
     * Returns a list of 0-2 timestamps in any order — the caller
     * sorts after combining the before / pause / after sets.
     */
    private fun pickTwoSpread(
        candidates: List<Double>,
        anchor: Double,
        preferred: DoubleArray,
        lookSec: Double,
    ): List<Double> {
        val out = ArrayList<Double>(2)
        // First slot: closest cue boundary to the anchor (if within
        // look-window). Otherwise the first preferred fallback.
        val first = candidates.firstOrNull { kotlin.math.abs(it - anchor) <= lookSec }
            ?: preferred.getOrNull(0)?.takeIf { it >= 0.0 }
            ?: return out
        out += first

        // Second slot: a candidate that's at least 3 s away from the
        // first pick to keep the visual diversity. Otherwise the
        // second preferred fallback (which is by construction
        // 4 s further out, so already diverse).
        val second = candidates
            .firstOrNull {
                kotlin.math.abs(it - anchor) <= lookSec &&
                    kotlin.math.abs(it - first) >= 3.0
            }
            ?: preferred.getOrNull(1)?.takeIf { it >= 0.0 && kotlin.math.abs(it - first) >= 3.0 }
        if (second != null) out += second
        return out
    }

    /**
     * All cue-boundary timestamps strictly BEFORE [pause], sorted by
     * distance-from-pause (closest first). Boundaries are cue start
     * AND cue end — both represent visually-stable moments where
     * the teacher just finished writing / about to start writing.
     */
    private fun cueBoundariesBefore(cues: List<TranscriptCue>, pause: Double): List<Double> {
        if (cues.isEmpty()) return emptyList()
        val out = ArrayList<Double>(cues.size * 2)
        for (cue in cues) {
            if (cue.startSec >= pause) break  // cues are time-sorted
            if (cue.startSec < pause) out += cue.startSec
            if (cue.endSec   < pause) out += cue.endSec
        }
        return out.distinct().sortedByDescending { it }
    }

    /**
     * All cue-boundary timestamps strictly AFTER [pause], sorted by
     * distance-from-pause (closest first).
     */
    private fun cueBoundariesAfter(cues: List<TranscriptCue>, pause: Double): List<Double> {
        if (cues.isEmpty()) return emptyList()
        val out = ArrayList<Double>(cues.size * 2)
        for (cue in cues) {
            if (cue.endSec <= pause) continue
            if (cue.startSec > pause) out += cue.startSec
            if (cue.endSec   > pause) out += cue.endSec
        }
        return out.distinct().sorted()
    }

    private fun midpoint(a: Double, b: Double): Double = (a + b) * 0.5

    /**
     * Find the cue boundary (start of next cue OR end of current cue)
     * nearest to [target] and within [SNAP_SLACK_SEC]. Returns [target]
     * unchanged when no boundary is close enough — this is the
     * "ideal samples are good enough" path used for cue-less docs.
     */
    private fun snapToCueBoundary(cues: List<TranscriptCue>, target: Double): Double {
        if (cues.isEmpty()) return target
        var bestDelta = SNAP_SLACK_SEC
        var bestTs: Double = target
        for (cue in cues) {
            // Two candidates per cue: its start and its end.
            val candidates = doubleArrayOf(cue.startSec, cue.endSec)
            for (cand in candidates) {
                val d = kotlin.math.abs(cand - target)
                if (d < bestDelta) {
                    bestDelta = d
                    bestTs = cand
                }
            }
            // Cues are time-sorted — once the cue's start is more than
            // SNAP_SLACK_SEC past the target, every later cue is too
            // far to help, so we can early-exit.
            if (cue.startSec - target > SNAP_SLACK_SEC) break
        }
        return bestTs
    }

    /** Clamp helper. Symmetric to QuestionWindowDetector.clampWindow. */
    fun clamp(value: Double, lo: Double, hi: Double): Double = max(lo, min(hi, value))
}
