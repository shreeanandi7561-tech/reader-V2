package com.reader.app.domain.youtube

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.reader.app.domain.model.ImageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Resolves YouTube preview-thumbnail "frames" at any timestamp inside
 * a video, using the public storyboard tile-sheets that YouTube
 * already serves for its own seek-bar preview UI.
 *
 * Used by [StoryboardFrameSource] as the storyboard half of the
 * multimodal frame pipeline. When the WebView frame capture path
 * returns mostly-black bitmaps (hardware-protected video surface),
 * the [com.reader.app.domain.youtube.CompositeVideoFrameSource]
 * routes the affected timestamps through this client instead so the
 * AI still gets visual context.
 *
 * # Caching
 *
 * Sheets are fetched at most once per `(videoId, level, sheetIndex)`
 * triple: a single JPEG sheet packs `cols × rows` cells (typically
 * 25), so one fetch covers ~125 s of video at the highest level.
 * Cropping is cheap — we keep the decoded [Bitmap] of the sheet in
 * memory under an LRU and just call `Bitmap.createBitmap` on each
 * call.
 *
 * The LRU is bounded by sheet count, not bytes, because every sheet at
 * a given level is the same size (e.g. 5×5 grid of 160×90 cells →
 * 800×450 px JPEG → ~80 KB decoded). [DEFAULT_LRU_CAPACITY] sheets fit
 * in well under 1 MB, and a single Discussion session almost never
 * needs more than 2–3 sheets across all doubts.
 *
 * # Concurrency
 *
 * `frameAt` is safe to call from any coroutine. A per-URL [Mutex]
 * ensures we don't accidentally double-fetch the same sheet when
 * several frames in one doubt land on the same sheet (which is the
 * common case — the FrameTimestampSampler picks 3–5 nearby
 * timestamps, all of which usually fall in the same 125 s sheet at
 * level 2). Network I/O runs on `Dispatchers.IO`; bitmap decode +
 * crop + base64 runs on `Dispatchers.Default` (CPU-bound).
 */
class YouTubeStoryboardClient(
    private val client: OkHttpClient = defaultHttpClient(),
    private val lruCapacity: Int     = DEFAULT_LRU_CAPACITY,
    /** JPEG quality used for the cropped-cell output (40–95 sane range). */
    private val outputJpegQuality: Int = DEFAULT_JPEG_QUALITY
) {

    // -- Sheet cache (decoded Bitmap, keyed by sheet URL) --
    // LinkedHashMap with access-order=true gives LRU semantics for
    // free; manual capacity check on `put` keeps the cache bounded.
    private val sheetCache = object : LinkedHashMap<String, Bitmap>(
        lruCapacity * 2, 0.75f, /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>?): Boolean {
            val drop = size > lruCapacity
            if (drop && eldest != null) {
                // Recycle the evicted bitmap so its native memory is
                // returned to the heap immediately. The Android Bitmap
                // pool would do this on GC anyway but we'd rather not
                // wait for a stop-the-world.
                runCatching { eldest.value.recycle() }
            }
            return drop
        }
    }

    /**
     * Per-URL serialisation lock — when several concurrent calls land
     * on the same sheet we want exactly one fetch + decode, not N. The
     * lock map itself is guarded by a synchronized block on
     * [fetchLocks].
     */
    private val fetchLocks = HashMap<String, Mutex>()

    /**
     * Resolve a storyboard frame at [timeSec] using the [spec]
     * captured at video import time, returning an [ImageData] ready
     * for the multimodal LLM payload (raw base64 JPEG, no
     * `data:image/...;base64,` prefix — that's the wire shape Gemini
     * wants).
     *
     * Returns `null` on any failure (no time-aligned level in the
     * spec, sheet fetch failure, decode failure). The caller treats
     * `null` as "skip this timestamp" — a partial result is still
     * useful to the AI.
     *
     * @param spec    the storyboard descriptor for the active video
     * @param timeSec target video timestamp in seconds (>= 0)
     */
    suspend fun frameAt(spec: StoryboardSpec, timeSec: Double): ImageData? {
        val location = spec.frameAt(timeSec) ?: return null
        val sheet = loadSheet(location.sheetUrl) ?: return null
        return cropToImageData(sheet, location, timeSec)
    }

    /**
     * Fetch + decode the storyboard sheet at [sheetUrl], with
     * caching + per-URL deduplication of in-flight requests. Returns
     * `null` on any network / decode failure.
     */
    private suspend fun loadSheet(sheetUrl: String): Bitmap? {
        // Fast path: already cached. LinkedHashMap.get bumps LRU order.
        synchronized(sheetCache) {
            sheetCache[sheetUrl]?.let { if (!it.isRecycled) return it }
        }
        // Slow path: serialise concurrent callers on the same URL so
        // we never double-fetch + double-decode.
        val lock = synchronized(fetchLocks) {
            fetchLocks.getOrPut(sheetUrl) { Mutex() }
        }
        val bitmap = lock.withLock {
            // Re-check after acquiring the lock — another waiter may
            // have populated the cache while we were queuing.
            synchronized(sheetCache) {
                sheetCache[sheetUrl]?.let { if (!it.isRecycled) return@withLock it }
            }
            val fresh = withContext(Dispatchers.IO) { fetchAndDecodeSheet(sheetUrl) }
            if (fresh != null) {
                synchronized(sheetCache) {
                    sheetCache[sheetUrl] = fresh
                }
            }
            fresh
        }
        // Drop the per-URL lock after use so we don't accumulate
        // them indefinitely. Worst case two concurrent callers race
        // on a fresh Mutex — both still serialise correctly, just
        // using two locks instead of one. Cheap.
        synchronized(fetchLocks) { fetchLocks.remove(sheetUrl) }
        return bitmap
    }

    /** Synchronous network + decode pipeline. Run on `Dispatchers.IO`. */
    private fun fetchAndDecodeSheet(sheetUrl: String): Bitmap? {
        val request = Request.Builder()
            .url(sheetUrl)
            .header("Accept", "image/jpeg,image/*,*/*;q=0.8")
            .header("Accept-Language", "en-US")
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val bytes = resp.body?.bytes() ?: return@runCatching null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    /**
     * Crop the cell out of [sheet] per [location], JPEG-compress, and
     * base64-encode into an [ImageData] that the LLM repository can
     * pack directly into a Gemini `inline_data` part.
     *
     * The per-cell crop never fails for valid input but we still
     * bound it inside `runCatching` so that an unexpected malformed
     * sheet (e.g. wrong dimensions because YouTube changed the level
     * mid-video) doesn't crash the doubt flow.
     */
    private suspend fun cropToImageData(
        sheet: Bitmap,
        location: StoryboardSpec.FrameLocation,
        timeSec: Double
    ): ImageData? = withContext(Dispatchers.Default) {
        runCatching {
            // Defensive clamp: if the actual fetched sheet's pixel
            // dimensions don't match our expectations (cell-size might
            // round in the JPEG), shrink the crop so we never index
            // out of bounds.
            val maxX = sheet.width
            val maxY = sheet.height
            val cropX = location.xPx.coerceIn(0, (maxX - 1).coerceAtLeast(0))
            val cropY = location.yPx.coerceIn(0, (maxY - 1).coerceAtLeast(0))
            val cropW = location.widthPx.coerceAtMost(maxX - cropX).coerceAtLeast(1)
            val cropH = location.heightPx.coerceAtMost(maxY - cropY).coerceAtLeast(1)

            val cropped = Bitmap.createBitmap(sheet, cropX, cropY, cropW, cropH)
            val baos = ByteArrayOutputStream(cropW * cropH / 4)
            cropped.compress(Bitmap.CompressFormat.JPEG, outputJpegQuality, baos)
            // Only recycle if a NEW bitmap was actually allocated —
            // `createBitmap` returns the original Bitmap as-is when
            // the requested region matches its full size, and we
            // must NOT recycle the cached sheet.
            if (cropped !== sheet) cropped.recycle()
            ImageData(
                mimeType = "image/jpeg",
                base64   = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP),
                captionTimestampSec = timeSec
            )
        }.getOrNull()
    }

    /** Drop all cached sheets. Useful when a video is changed. */
    fun clearCache() {
        synchronized(sheetCache) {
            for ((_, b) in sheetCache) runCatching { b.recycle() }
            sheetCache.clear()
        }
    }

    companion object {
        /** Sheets to keep around in RAM. ~80 KB each at L2 → < 1 MB total. */
        const val DEFAULT_LRU_CAPACITY = 12

        /**
         * 70 strikes a balance — storyboard JPEGs are already lossy at
         * source, re-encoding at >85 doesn't add fidelity but bloats
         * the multimodal payload (every extra KB is 1.4 KB of base64
         * over the wire). 70 keeps a 160×90 cell under ~3 KB.
         */
        const val DEFAULT_JPEG_QUALITY = 70

        /** Tighter timeouts than the document/transcript client — sheets are < 200 KB. */
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
