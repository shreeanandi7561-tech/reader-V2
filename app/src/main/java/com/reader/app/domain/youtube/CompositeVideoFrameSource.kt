package com.reader.app.domain.youtube

import com.reader.app.domain.model.ImageData
import kotlinx.coroutines.CancellationException

/**
 * Tries [primary] first; for any timestamp where the primary returned
 * a "useless" frame (missing entirely, or mostly-black per the
 * heuristic in [isLikelyBlank]) it falls back to [fallback] for that
 * timestamp.
 *
 * Used to combine [com.reader.app.ui.video.WebViewFrameSource]
 * (high-res but unreliable on hardware-protected video surfaces)
 * with [StoryboardFrameSource] (low-res but always aligned). When
 * the WebView frame capture works, the AI gets crisp 720p screenshots
 * of exactly what the teacher had on screen; when it doesn't (because
 * the GPU stack composites the video behind the WebView), the AI
 * still gets 160×90 storyboard cells instead of black rectangles.
 *
 * # Why a base64-length heuristic for "is this frame useful"?
 *
 * The most reliable signal that a captured frame is useless is that
 * it is overwhelmingly one solid colour (usually black). After JPEG
 * compression that produces an extremely small file — a 1280×720
 * mostly-black ARGB bitmap encodes to ~3–5 KB at quality 70, where a
 * real teaching frame with text / diagrams / a face encodes to
 * 50–200 KB. Base64 inflates by ~33%, so:
 *
 *   - real frame:        50 KB JPEG → ~67 K base64 chars
 *   - mostly-black frame: 4 KB JPEG → ~5.4 K base64 chars
 *
 * [LIKELY_BLANK_BASE64_LEN_DEFAULT] = 8000 catches the latter while
 * leaving plenty of margin above any plausible real frame. Decoding
 * the JPEG to a bitmap and running an actual histogram would be more
 * accurate but costs a CPU-bound decode per frame; the byte-count
 * proxy is essentially free and has effectively zero false positives
 * in practice.
 *
 * Configurable via the constructor so future tuning (or a smaller-
 * resolution capture path) can override it without touching the
 * fallback logic.
 *
 * # Cancellation contract
 *
 * Both legs respect the calling coroutine's cancellation. A
 * cancellation thrown from EITHER source propagates immediately —
 * we never swallow it to attempt fallback, because cancellation
 * means the user moved on (closed the screen, asked another doubt)
 * and any further work is wasted.
 *
 * Other failures from `primary` degrade silently to "primary
 * returned no frames at all", which causes the fallback to handle
 * EVERY timestamp.
 */
class CompositeVideoFrameSource(
    private val primary: VideoFrameSource,
    private val fallback: VideoFrameSource,
    /**
     * Predicate: given an image returned by [primary], decide whether
     * it's so likely-blank that the timestamp should be re-resolved
     * via [fallback]. Default checks `image.base64.length`, see the
     * KDoc for the rationale and threshold.
     */
    private val isLikelyBlank: (ImageData) -> Boolean = { it.base64.length < LIKELY_BLANK_BASE64_LEN_DEFAULT }
) : VideoFrameSource {

    override suspend fun captureFrames(timestamps: List<Double>): List<ImageData> {
        if (timestamps.isEmpty()) return emptyList()

        // Step 1: attempt the primary source. Cancellation must
        // propagate; any other failure → "no primary frames" and we
        // fall through to the fallback for everything.
        val primaryResults: List<ImageData> = try {
            primary.captureFrames(timestamps)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }

        // Index primary results by their captionTimestampSec so we
        // can pair them up with the requested timestamps. Some sources
        // (NoOp / a stubbed capture) might not set the timestamp; we
        // tolerate that by also matching on the input order.
        val byTs: Map<Double, ImageData> = primaryResults
            .filter { it.captionTimestampSec != null }
            .associateBy { it.captionTimestampSec!! }

        // Decide which timestamps need the fallback.
        val needFallback = timestamps.filter { t ->
            val r = byTs[t]
            r == null || isLikelyBlank(r)
        }

        if (needFallback.isEmpty()) {
            // Primary handled everything cleanly — return its results
            // directly, preserving its ordering so downstream prompts
            // see frames in the order the source emitted them.
            return primaryResults
        }

        // Step 2: ask the fallback for the missing / blank slots.
        val fallbackResults: List<ImageData> = try {
            fallback.captureFrames(needFallback)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        val fallbackByTs: Map<Double, ImageData> = fallbackResults
            .filter { it.captionTimestampSec != null }
            .associateBy { it.captionTimestampSec!! }

        // Step 3: merge — for each requested timestamp, prefer the
        // primary result UNLESS it was missing or blank, in which
        // case use the fallback. Walk in original timestamp order so
        // the AI sees frames chronologically (matches what the prompt
        // builder expects).
        val out = ArrayList<ImageData>(timestamps.size)

        for (t in timestamps) {
            val p = byTs[t]
            val isBlank = p != null && isLikelyBlank(p)
            val pick = when {
                p != null && !isBlank        -> p
                fallbackByTs[t] != null      -> fallbackByTs[t]!!
                p != null /* even if blank */ -> p   // still better than nothing
                else                         -> null
            }
            if (pick != null) out += pick
        }
        return out
    }

    companion object {
        /**
         * Default threshold below which a captured frame is treated
         * as mostly-blank. See class KDoc for derivation. Roughly
         * 6 KB of decoded JPEG / 8 K base64 chars.
         */
        const val LIKELY_BLANK_BASE64_LEN_DEFAULT = 8000
    }
}
