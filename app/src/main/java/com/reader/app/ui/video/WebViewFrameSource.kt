package com.reader.app.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.reader.app.domain.model.ImageData
import com.reader.app.domain.youtube.VideoFrameSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * [VideoFrameSource] implementation that captures actual frames out
 * of the YouTube IFrame player.
 *
 * Two snapshot strategies are tried, primary first:
 *
 *   **(A) `PixelCopy.request(Window, ...)`** — captures the
 *   GPU-composited contents of the Activity's window through the
 *   inner WebView's bounds. This is the only path that captures the
 *   actual video pixels on hardware-accelerated devices, where the
 *   YouTube iframe's video surface is composited BEHIND the WebView's
 *   normal `draw(Canvas)` output. PixelCopy is API 26+ and the app's
 *   `minSdk = 26`, so it is always available; no version guard
 *   needed.
 *
 *   **(B) `WebView.draw(Canvas)`** — the historical fallback.
 *   Captures whatever the WebView is currently painting in software:
 *   any chrome / overlay / on-screen text the iframe renders ABOVE
 *   the video surface, but typically NOT the video pixels themselves
 *   on hardware-accelerated devices. Still useful when PixelCopy
 *   fails (no Activity / Window discoverable, view detached from
 *   window during capture, GPU surface-flag restrictions on certain
 *   ROMs).
 *
 * The flow per requested timestamp is:
 *   1. `pause()` the iframe player so it doesn't keep advancing while
 *      we settle.
 *   2. `seekTo(t)` to the requested position.
 *   3. Wait [SETTLE_DELAY_MS] for the iframe to actually render the
 *      target frame. The IFrame API does NOT expose a "frame ready"
 *      callback, so a fixed delay is the most reliable signal we
 *      have. 700 ms is enough on real devices in our testing; tune
 *      via [settleDelayMs] when needed.
 *   4. Walk the `YouTubePlayerView`'s child view tree to find the
 *      single inner `WebView` instance.
 *   5. Try strategy (A) PixelCopy first; on null/failure fall back to
 *      (B) `WebView.draw(Canvas)`.
 *   6. JPEG-encode at quality 70, base64 (no-wrap), and emit as an
 *      [ImageData] tagged with the original timestamp.
 *   7. After all timestamps are processed, restore the player to its
 *      original position so the student doesn't resume on the last
 *      sampled timestamp.
 *
 * **Failure modes that degrade gracefully:**
 *  - Inner WebView not yet attached / view detached → skip that
 *    timestamp (or, for the whole batch, return an empty list and
 *    let the caller fall through to text-only).
 *  - WebView width/height is 0 → skip.
 *  - PixelCopy returns non-SUCCESS → fall back to `WebView.draw`.
 *  - `bitmap.compress` fails → skip.
 *  - The whole capture run is cancelled by the parent coroutine →
 *    propagate the cancellation immediately.
 *
 * **Thread-safety:** every WebView / player / PixelCopy call runs on
 * `Dispatchers.Main`; only the JPEG-encode + base64 step runs on the
 * default dispatcher because it is CPU-bound and benefits from being
 * off the main thread. The PixelCopy completion callback fires on a
 * dedicated background [HandlerThread] (one per source instance,
 * lazily started), then re-enters `Dispatchers.Main` via the
 * `suspendCancellableCoroutine` resume.
 *
 * **Memory:** every bitmap is recycled the moment the JPEG bytes
 * (or a fallback decision) are extracted. JPEG quality 70 keeps each
 * ~1280×720 frame under ~150 KB encoded — five of them stay well
 * below Gemini's 1 MB per-image inline limit and OpenAI-compat's
 * 20 MB total request limit.
 */
class WebViewFrameSource(
    private val handle: VideoPlaybackHandle,
    private val playerView: YouTubePlayerView,
    private val settleDelayMs: Long = SETTLE_DELAY_MS,
    private val jpegQuality: Int = 70,
) : VideoFrameSource {

    /**
     * Background thread for the PixelCopy callback. Lazily started so
     * non-vision use of the source (or sources that never end up
     * doing a capture) don't pay the thread cost.
     *
     * Quit on the explicit [release] hook OR — defensively — when
     * captureFrames discovers the view has been detached and is no
     * longer usable.
     */
    private var pixelCopyHandlerThread: HandlerThread? = null
    private val pixelCopyHandler: Handler
        get() {
            val existing = pixelCopyHandlerThread
            if (existing != null && existing.isAlive) return Handler(existing.looper)
            val t = HandlerThread("WebViewFrameSource-PixelCopy").also { it.start() }
            pixelCopyHandlerThread = t
            return Handler(t.looper)
        }

    override suspend fun captureFrames(timestamps: List<Double>): List<ImageData> {
        if (timestamps.isEmpty()) return emptyList()
        val originalSec = handle.currentSec
        val results = mutableListOf<ImageData>()
        try {
            // Pause once up front — every per-frame seek otherwise
            // races against the player's natural advance.
            withContext(Dispatchers.Main) { handle.pause() }
            for (t in timestamps) {
                // Per-frame failures (WebView returned blank, JPEG
                // encode failed, etc.) skip that timestamp but keep
                // going — partial frame sets are still useful to the
                // AI. CancellationException must propagate so the
                // outer scope can stop us cleanly when the user
                // navigates away mid-capture.
                val img = try {
                    captureOne(t)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                } ?: continue
                results += img
            }
        } finally {
            // Always restore — even on cancellation — so the user
            // doesn't resume on the last sampled timestamp.
            runCatching {
                withContext(Dispatchers.Main) { handle.seekTo(originalSec) }
            }
            // Best-effort cleanup of the PixelCopy thread. Safe to
            // call even if it was never started; the lazy getter
            // doesn't allocate one until first use.
            quitPixelCopyThread()
        }
        return results
    }

    private suspend fun captureOne(targetSec: Double): ImageData? {
        // Seek + settle on the main thread.
        withContext(Dispatchers.Main) {
            handle.seekTo(targetSec)
        }
        delay(settleDelayMs)
        // Snapshot — try PixelCopy first (captures the actual video
        // surface on hardware-accelerated devices), fall back to
        // WebView.draw (captures DOM / chrome only). Both must run on
        // the main thread because both touch live View / Window
        // references.
        val bitmap: Bitmap = withContext(Dispatchers.Main) {
            snapshotViaPixelCopy() ?: snapshotViaWebViewDraw()
        } ?: return null
        // JPEG-encode off the main thread.
        return withContext(Dispatchers.Default) {
            val baos = ByteArrayOutputStream(64 * 1024)
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
            // Recycle ASAP — five 1280x720 ARGB_8888 bitmaps would be
            // ~18 MB resident otherwise.
            bitmap.recycle()
            if (!ok) return@withContext null
            val bytes = baos.toByteArray()
            ImageData(
                mimeType = "image/jpeg",
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                captionTimestampSec = targetSec,
            )
        }
    }

    /**
     * Strategy A — `PixelCopy.request(Window, srcRect, dst, ...)`.
     *
     * Captures the GPU-composited window contents inside the inner
     * WebView's bounds. This is the only path that picks up the
     * YouTube video surface on hardware-accelerated devices, where
     * the iframe renders the video to a Surface that
     * `WebView.draw(Canvas)` cannot read.
     *
     * Returns null when PixelCopy isn't usable for this view tree —
     * caller falls back to [snapshotViaWebViewDraw]. Reasons for
     * null:
     *   - WebView not yet attached / not laid out → no width/height.
     *   - Couldn't unwrap the View's Context to an Activity /
     *     Window (rare; happens when the view lives inside a
     *     non-Activity host like a widget service).
     *   - PixelCopy fired SUCCESS-not-now (PixelCopy.ERROR_*) — common
     *     reasons: SecureSurface flag from DRM, Surface destroyed
     *     mid-copy, source out of bounds.
     */
    private suspend fun snapshotViaPixelCopy(): Bitmap? {
        val webView = findInnerWebView(playerView) ?: return null
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return null
        val window = findActivityWindow(webView) ?: return null
        // Sanity: bail out if the view is not attached to a window —
        // PixelCopy on a detached view crashes on some OEM ROMs even
        // though the public docs say it would just return ERROR.
        if (!webView.isAttachedToWindow) return null

        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val srcRect = Rect(
            location[0],
            location[1],
            location[0] + w,
            location[1] + h
        )
        // Allocate the destination bitmap only if the rect is
        // non-empty and inside the window — the framework will
        // otherwise reject it.
        if (srcRect.isEmpty) return null

        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }

        return suspendCancellableCoroutine { cont ->
            // If the coroutine is cancelled before we even queue the
            // request, recycle and bail out without scheduling.
            // We MUST NOT call cont.resume() here — the continuation
            // is already in the cancelled state and resume() would
            // throw IllegalStateException. Returning lets the
            // structured-cancellation machinery propagate the
            // CancellationException to the caller.
            if (!cont.isActive) {
                bitmap.recycle()
                return@suspendCancellableCoroutine
            }
            val handler = pixelCopyHandler
            try {
                PixelCopy.request(window, srcRect, bitmap, { result ->
                    if (cont.isActive) {
                        if (result == PixelCopy.SUCCESS) {
                            cont.resume(bitmap)
                        } else {
                            // The framework's docs list error codes
                            // ERROR_DESTINATION_INVALID,
                            // ERROR_SOURCE_NO_DATA,
                            // ERROR_SOURCE_INVALID,
                            // ERROR_TIMEOUT, ERROR_UNKNOWN. All of
                            // them mean "PixelCopy can't help here";
                            // recycle and let the WebView.draw
                            // fallback try its luck.
                            bitmap.recycle()
                            cont.resume(null)
                        }
                    } else {
                        // Coroutine cancelled while PixelCopy was in
                        // flight. Free the bitmap to avoid leaking
                        // ~3.5 MB per cancelled frame.
                        bitmap.recycle()
                    }
                }, handler)
            } catch (_: Throwable) {
                // PixelCopy can throw IllegalArgumentException on
                // certain malformed source rects, or on some OEMs
                // when the window is in an unusual state. Treat the
                // same as a soft failure → null → fallback.
                bitmap.recycle()
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Strategy B — `WebView.draw(Canvas)`.
     *
     * Captures whatever the WebView is currently painting in
     * software. On hardware-accelerated devices this typically yields
     * just the DOM / chrome layer (any HUD overlays, captions
     * rendered into the iframe DOM, custom error pages) without the
     * underlying video surface. Still useful as a "better than
     * nothing" fallback when PixelCopy declines.
     */
    private fun snapshotViaWebViewDraw(): Bitmap? {
        val webView = findInnerWebView(playerView) ?: return null
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            webView.draw(canvas)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Recursively walk a view tree to find the first descendant
     * [WebView]. Returns `null` if the tree contains no WebView (e.g.
     * the player is still initialising).
     *
     * The library's [YouTubePlayerView] always wraps exactly one
     * WebView inside its view hierarchy — finding "the first" is
     * therefore equivalent to "the only".
     */
    private fun findInnerWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findInnerWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * Walk the View → Context → ContextWrapper → ... chain to find
     * the hosting [Activity]'s [Window]. Returns null when the View
     * isn't hosted in an Activity (e.g. a Service-hosted overlay,
     * which Reader doesn't use today).
     */
    private fun findActivityWindow(view: View): Window? {
        var ctx: Context? = view.context
        // Bound the iteration — pathological wrappers shouldn't loop
        // forever even if a malformed Context graph somehow has a
        // cycle.
        var hops = 0
        while (ctx is ContextWrapper && hops < 16) {
            if (ctx is Activity) return ctx.window
            ctx = ctx.baseContext
            hops++
        }
        return null
    }

    private fun quitPixelCopyThread() {
        val t = pixelCopyHandlerThread ?: return
        runCatching { t.quitSafely() }
        pixelCopyHandlerThread = null
    }

    companion object {
        /**
         * How long to wait after `seekTo` before snapshotting. The
         * IFrame player does NOT emit a "frame ready" callback, so we
         * approximate. 700 ms is comfortable on most networks; bump
         * it if your test device is slow or on a poor connection.
         */
        const val SETTLE_DELAY_MS: Long = 700
    }
}
