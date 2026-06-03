package com.reader.app.ui.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * State holder that bridges the YouTube IFrame player (which lives
 * inside a [YouTubeVideoArea] composable) to the rest of the screen
 * (custom chrome, ViewModel timestamp reads, etc.).
 *
 * The player calls into the handle from its `AbstractYouTubePlayerListener`
 * callbacks — `onReady` binds the [YouTubePlayer] reference, and the
 * per-second / state-change callbacks update the observable fields here.
 *
 * Compose / VM read these fields via the usual `remember` + state-snapshot
 * machinery. The handle is `Stable`, never re-allocated, so reading any
 * single field doesn't recompose unrelated subtrees.
 *
 * Why not a SharedFlow / event bus here? The screen already owns the
 * handle. Both the chrome (play/pause buttons) and the VM (ask() reading
 * the paused-at timestamp) reach in directly. An event bus would just
 * add ceremony.
 */
@Stable
class VideoPlaybackHandle internal constructor(context: android.content.Context? = null) {

    private val prefs = context?.getSharedPreferences("video_playback_prefs", android.content.Context.MODE_PRIVATE)

    /** Bound on the player's `onReady`; null until the iframe finishes loading. */
    var player: YouTubePlayer? by mutableStateOf(null)
        internal set

    /**
     * The [YouTubePlayerView] hosting [player]. Bound by [YouTubeVideoArea]'s
     * `DisposableEffect` and cleared on dispose.
     *
     * Exposed so off-screen consumers (notably the multimodal doubt
     * pipeline's [com.reader.app.ui.video.WebViewFrameSource]) can
     * snapshot the inner WebView at any timestamp without having to
     * reach back into the screen's view tree. We keep the reference
     * `internal set` — only the player composable mutates it, the rest
     * of the world reads it.
     *
     * Null when the player view hasn't been composed yet (e.g. text
     * docs that never render the player) or has been disposed (e.g.
     * the user backed out and re-entered the screen). Frame-capture
     * code should treat null as "frames unavailable, fall back to
     * text-only".
     */
    var playerView: YouTubePlayerView? by mutableStateOf(null)
        internal set

    /** Live current playhead position in seconds. Updated ~once per second by the iframe. */
    var currentSec: Double by mutableStateOf(0.0)
        internal set

    /** Total video duration in seconds. Updated once after [PlayerConstants.PlayerState.PLAYING]. */
    var durationSec: Double by mutableStateOf(0.0)
        internal set

    var playerState: PlayerConstants.PlayerState by mutableStateOf(PlayerConstants.PlayerState.UNKNOWN)
        internal set

    /** Cycled by the chrome's speed button. Read by the player binder. */
    var playbackRate: PlayerConstants.PlaybackRate by mutableStateOf(PlayerConstants.PlaybackRate.RATE_1)
        internal set

    private var _preferredQualityState = mutableStateOf(prefs?.getString("preferred_quality", "Auto") ?: "Auto")

    /** Preferred video quality string. Managed by quality popup. */
    var preferredQuality: String
        get() = _preferredQualityState.value
        set(value) {
            _preferredQualityState.value = value
            prefs?.edit()?.putString("preferred_quality", value)?.apply()
        }

    /**
     * Latest error reported by the IFrame player, or `null` when the
     * player is healthy. Cleared on every successful `onReady`.
     *
     * The `[YouTubeVideoArea]` chrome reads this and paints an
     * "embed unavailable" overlay (with an "Open in YouTube" fallback
     * intent) whenever it's non-null. We surface even the
     * not-found / invalid-param errors here, not just
     * `VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER`, so the student always
     * gets a clear escape hatch instead of being stuck staring at
     * YouTube's small "Video unavailable" page inside the iframe.
     */
    var lastError: PlayerConstants.PlayerError? by mutableStateOf(null)
        internal set

    val isPlaying: Boolean
        get() = playerState == PlayerConstants.PlayerState.PLAYING

    val isReady: Boolean
        get() = player != null

    val hasError: Boolean
        get() = lastError != null && lastError != PlayerConstants.PlayerError.UNKNOWN

    /* -------- Imperative API used by the chrome buttons -------- */

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    /** Seek by [deltaSec] seconds (negative for rewind). Clamped to [0, duration]. */
    fun seekBy(deltaSec: Double) {
        val target = (currentSec + deltaSec).coerceIn(0.0, durationSec.coerceAtLeast(0.0))
        player?.seekTo(target.toFloat())
    }

    /** Absolute seek. Clamped to [0, duration]. */
    fun seekTo(sec: Double) {
        val target = sec.coerceIn(0.0, durationSec.coerceAtLeast(0.0))
        player?.seekTo(target.toFloat())
    }

    /**
     * Cycle through the standard playback-rate presets that YouTube's
     * IFrame API exposes:  1× → 1.5× → 2× → 1×.
     *
     * The `PlayerConstants.PlaybackRate` enum also defines `RATE_0_25`
     * and `RATE_0_5`, but slow-motion is essentially useless for a
     * tutoring session — students who want to slow down a teacher's
     * explanation can already pause and re-seek by 10s. We deliberately
     * skip those values so cycling stays predictable (every tap moves
     * forward through "normal → faster" speeds, never accidentally
     * lands on slow-motion).
     */
    fun cyclePlaybackRate() {
        val next = when (playbackRate) {
            PlayerConstants.PlaybackRate.RATE_1   -> PlayerConstants.PlaybackRate.RATE_1_5
            PlayerConstants.PlaybackRate.RATE_1_5 -> PlayerConstants.PlaybackRate.RATE_2
            else                                  -> PlayerConstants.PlaybackRate.RATE_1
        }
        playbackRate = next
        player?.setPlaybackRate(next)
    }
}

@Composable
fun rememberVideoPlaybackHandle(context: android.content.Context = androidx.compose.ui.platform.LocalContext.current): VideoPlaybackHandle {
    val appContext = context.applicationContext
    return remember(appContext) { VideoPlaybackHandle(appContext) }
}

/** Pretty `mm:ss` (or `hh:mm:ss` for >1 hour). Used by the chrome time display. */
internal fun formatVideoTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

/**
 * Human label for the speed-cycle button. Covers every value the
 * library can hand back (including the slow-motion ones we don't
 * cycle into ourselves — the iframe could still report e.g.
 * `RATE_0_5` if some upstream playback mechanism set it, and we want
 * to render that correctly rather than show a wrong "1×").
 */
internal fun playbackRateLabel(rate: PlayerConstants.PlaybackRate): String = when (rate) {
    PlayerConstants.PlaybackRate.RATE_0_25 -> "0.25×"
    PlayerConstants.PlaybackRate.RATE_0_5  -> "0.5×"
    PlayerConstants.PlaybackRate.RATE_1    -> "1×"
    PlayerConstants.PlaybackRate.RATE_1_5  -> "1.5×"
    PlayerConstants.PlaybackRate.RATE_2    -> "2×"
    else                                   -> "1×"
}
