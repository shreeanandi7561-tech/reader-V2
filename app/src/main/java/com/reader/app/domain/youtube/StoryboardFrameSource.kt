package com.reader.app.domain.youtube

import com.reader.app.domain.model.ImageData
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * [VideoFrameSource] backed by YouTube's public storyboard tile-sheets.
 *
 * Used as the FALLBACK leg of [CompositeVideoFrameSource]: when the
 * WebView frame capture path returns mostly-black bitmaps (the video
 * surface is hardware-protected and not blitted into the WebView's
 * `Canvas`), this source resolves the same timestamps to public
 * `i.ytimg.com/sb/...` cells instead — low-res but reliably aligned
 * to any video moment.
 *
 * Implementation is a thin orchestrator over [YouTubeStoryboardClient]:
 * for each requested timestamp, fetch + crop the corresponding cell
 * in parallel via `coroutineScope { async { ... } }`. Per-frame
 * failures are dropped (return `null`); the caller already handles
 * partial sets.
 *
 * Thread-safety: [YouTubeStoryboardClient] owns its own dispatch /
 * cache concurrency. This class does no extra synchronisation.
 *
 * Cancellation: `coroutineScope` propagates cancellation cleanly to
 * every in-flight `async` so the user can navigate away mid-fetch
 * without leaking HTTP connections.
 */
class StoryboardFrameSource(
    private val spec: StoryboardSpec,
    private val client: YouTubeStoryboardClient
) : VideoFrameSource {

    override suspend fun captureFrames(timestamps: List<Double>): List<ImageData> {
        if (timestamps.isEmpty()) return emptyList()
        // Parallel fetch — all requested cells almost always land on
        // the same sheet at level 2 (5×5 grid, 5s/cell ⇒ ~125s of
        // video per sheet), so the client's per-URL lock + LRU
        // ensure we issue exactly ONE network call regardless of how
        // many timestamps we asked for.
        return coroutineScope {
            timestamps.map { t ->
                async { client.frameAt(spec, t) }
            }.mapNotNull { it.await() }
        }
    }
}
