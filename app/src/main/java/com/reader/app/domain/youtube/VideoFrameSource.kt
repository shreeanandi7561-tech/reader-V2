package com.reader.app.domain.youtube

import com.reader.app.domain.model.ImageData

/**
 * Provider-agnostic abstraction over "give me a screenshot of the
 * video at timestamp T".
 *
 * Different document types want different implementations:
 *  - YouTube docs: snapshot the inner WebView of the IFrame player
 *    (see `ui/video/WebViewFrameSource.kt`).
 *  - Future: stored MP4s could use [android.media.MediaMetadataRetriever].
 *  - Tests / non-video docs: the [NoOp] singleton below returns an
 *    empty list, which causes the multimodal call site to fall back
 *    to its existing text-only path.
 *
 * Why a coroutine-suspending interface?
 *  - WebView snapshot involves a `seekTo()` + a settle-delay between
 *    each frame, which is asynchronous by nature.
 *  - The Discussion ViewModel calls this from inside a viewModelScope
 *    coroutine, so suspend integrates naturally — no extra threading
 *    plumbing.
 *
 * Implementations MUST be safe to invoke from any dispatcher; concrete
 * implementations switch to `Dispatchers.Main` internally where they
 * touch UI (the WebView).
 */
interface VideoFrameSource {

    /**
     * Capture one [ImageData] per requested timestamp, in the same
     * order the timestamps were given.
     *
     * Implementations MAY return fewer images than requested when
     * individual captures fail (e.g. the player wasn't ready, the
     * WebView didn't paint in time, the video itself is unplayable).
     * The [ImageData.captionTimestampSec] on each returned entry tells
     * the caller which input timestamp it corresponds to.
     *
     * Returning an empty list signals "frames are not available right
     * now" — the caller should treat this as a clean fallback to the
     * text-only prompt path, NOT as an error worth surfacing to the
     * user.
     *
     * Implementations MUST NOT throw on a per-frame failure — only on
     * a total / setup failure (e.g. the underlying view was disposed).
     * Callers wrap the whole call in `runCatching` anyway, so even a
     * thrown exception falls through to text-only — but per-frame
     * resilience is preferred so a partial set still reaches the AI.
     */
    suspend fun captureFrames(timestamps: List<Double>): List<ImageData>

    /**
     * No-op source used when:
     *  - the screen has no video player wired up (text docs), or
     *  - the screen is still composing and the real source hasn't
     *    been registered yet, or
     *  - tests want a stub.
     *
     * Always returns the empty list, which is the clean
     * "fall back to text-only" signal the caller already handles.
     */
    object NoOp : VideoFrameSource {
        override suspend fun captureFrames(timestamps: List<Double>): List<ImageData> = emptyList()
    }
}
