package com.reader.app.ui.video

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
// Compose 1.7+ moved LocalLifecycleOwner from `compose.ui.platform` to
// `androidx.lifecycle.compose`. The old import still resolves via type
// alias under Compose 1.9 but is deprecated; this is the canonical
// location.
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
/**
 * YouTube video player wrapped with a fully custom Compose chrome.
 *
 * The player itself is YouTube's official IFrame API (delivered via
 * `pierfrancescosoffritti/android-youtube-player`'s WebView wrapper).
 * That's the only legal way to play a YouTube video inside an app —
 * stream-rip approaches (ExoPlayer + extracted CDN URL) violate
 * YouTube ToS and break frequently. Going through the iframe means
 * playback NEVER mysteriously stops working.
 *
 * What we DO take ownership of is everything OUTSIDE the actual video
 * frame:
 *  - YouTube's own UI is suppressed via `IFramePlayerOptions.controls(0)`,
 *    `rel(0)` (no related videos on pause), `ivLoadPolicy(3)` (no
 *    overlay annotations), `ccLoadPolicy(0)` (no captions overlay —
 *    we have our own transcript path).
 *  - Tapping the video toggles a custom chrome layer with: ⏪10 / play /
 *    pause / ⏩10 / scrubbable seekbar / mm:ss display / speed cycle /
 *    fullscreen toggle. The chrome auto-hides after 3.5s of no
 *    interaction (mirrors YouTube's own UX).
 *  - The whole thing is wrapped in a 16:9 box that the parent screen
 *    can pin to the top of the chat. Fullscreen is handled by the
 *    parent (this composable just calls [onFullscreenToggle] and
 *    stretches via the modifier the parent passes in).
 *
 * Lifecycle: the underlying [YouTubePlayerView] is created once, bound
 * to the host Activity's lifecycle (so the iframe pauses on background
 * and resumes properly), and released on [DisposableEffect] dispose so
 * the WebView doesn't leak.
 *
 * **Why we cue (instead of load).** `cueVideo` shows the thumbnail and
 * waits for the user's tap to play, no auto-play, no buffering on
 * arrival. Better for data and for "I just opened the screen and
 * suddenly there's audio" surprise. The student presses our big play
 * button when ready.
 */
@Composable
fun YouTubeVideoArea(
    videoId: String,
    handle: VideoPlaybackHandle,
    isFullscreen: Boolean,
    onFullscreenToggle: () -> Unit,
    onCloseFullscreen: () -> Unit = onFullscreenToggle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // The view is created once per [videoId] — switching documents in
    // the same composition would be unusual but we want to be safe.
    val playerView = remember(videoId) {
        YouTubePlayerView(context).apply {
            // We do the initialisation manually below so we can pass our
            // own IFramePlayerOptions (no YouTube chrome, no related
            // videos, etc.).
            enableAutomaticInitialization = false
        }
    }

    // Initialise the iframe player + bind callbacks → handle.
    DisposableEffect(playerView, videoId) {
        // youtube-player 13.0.0 changed the Builder signature — it now
        // takes a Context as the sole constructor argument (used
        // internally for locale-aware iframe setup). The 12.1.0 no-arg
        // form no longer exists.
        val options = IFramePlayerOptions.Builder(context)
            .controls(0)         // hide YouTube's own play/pause/seek bar
            .rel(0)              // no related-videos panel on pause
            .ivLoadPolicy(3)     // no info-card / annotation overlays
            .ccLoadPolicy(0)     // no captions overlay — we own that path
            .fullscreen(0)       // never let the iframe drive fullscreen;
            //                     the Compose parent owns it
            .build()

        val listener = object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                handle.player = youTubePlayer
                // Successful load: clear any stale error from a previous
                // doc / retry. The error overlay reads handle.lastError.
                handle.lastError = null
                // cueVideo() loads the thumbnail without auto-playing —
                // matches our "no surprise audio" UX.
                youTubePlayer.cueVideo(videoId, 0f)
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                handle.currentSec = second.toDouble()
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                handle.durationSec = duration.toDouble()
            }

            override fun onStateChange(
                youTubePlayer: YouTubePlayer,
                state: PlayerConstants.PlayerState
            ) {
                handle.playerState = state
            }

            override fun onPlaybackRateChange(
                youTubePlayer: YouTubePlayer,
                playbackRate: PlayerConstants.PlaybackRate
            ) {
                handle.playbackRate = playbackRate
            }

            /**
             * IFrame player error.
             *
             * Most-common ones we see in the wild:
             *  - `VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER` (code 150 / 101) —
             *    the video owner has explicitly disabled embedding
             *    (very common on VEVO / music label uploads). YouTube
             *    will display its own "Video unavailable. Watch on
             *    YouTube." page inside the iframe; our overlay covers
             *    that with a friendlier message + "Open in YouTube"
             *    intent so the student isn't stuck.
             *  - `VIDEO_NOT_FOUND` (100) — ID was correct at import but
             *    the video has since been taken down or made private.
             *  - `INVALID_PARAMETER_IN_REQUEST` (2), `HTML_5_PLAYER` (5) —
             *    rare; surfacing them with the same fallback is fine.
             *
             * We DON'T try to differentiate; from the student's POV the
             * outcome is the same ("can't play here"). The overlay's
             * Open-in-YouTube button is the universal escape hatch.
             */
            override fun onError(
                youTubePlayer: YouTubePlayer,
                error: PlayerConstants.PlayerError
            ) {
                handle.lastError = error
            }
        }

        playerView.initialize(listener, options)
        lifecycleOwner.lifecycle.addObserver(playerView)
        // Expose the playerView on the handle so off-screen consumers
        // (notably the multimodal doubt pipeline's WebViewFrameSource)
        // can snapshot its inner WebView at any timestamp. Cleared on
        // dispose so we don't leave a dangling reference to a released
        // view if the screen is torn down.
        handle.playerView = playerView
        onDispose {
            handle.playerView = null
            lifecycleOwner.lifecycle.removeObserver(playerView)
            playerView.release()
        }
    }

    /* -------- Chrome auto-hide -------- */

    var chromeVisible by remember { mutableStateOf(true) }
    // Each time the user taps / drags the seekbar we bump this counter,
    // which restarts the auto-hide timer below.
    var interactionTick by remember { mutableStateOf(0) }
    LaunchedEffect(interactionTick, chromeVisible, handle.isPlaying) {
        if (!chromeVisible) return@LaunchedEffect
        // Only auto-hide while playing. When paused / buffering the
        // student is probably interacting with the chrome and finding
        // it hidden mid-tap is annoying.
        if (!handle.isPlaying) return@LaunchedEffect
        delay(CHROME_AUTO_HIDE_MS)
        chromeVisible = false
    }

    fun bumpInteraction() {
        chromeVisible = true
        interactionTick++
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            // Tap on the video toggles chrome — but only when we're
            // not currently showing the error overlay (the error
            // overlay has its own buttons; passthrough taps would
            // confuse the gesture model).
            .pointerInput(handle.hasError) {
                if (handle.hasError) return@pointerInput
                detectTapGestures(onTap = {
                    chromeVisible = !chromeVisible
                    if (chromeVisible) interactionTick++
                })
            }
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val screenAspectRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 16f / 9f
            val videoAspectRatio = 16f / 9f
            val videoModifier = if (screenAspectRatio > videoAspectRatio) {
                Modifier.fillMaxHeight().aspectRatio(videoAspectRatio)
            } else {
                Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
            }
            
            Box(modifier = videoModifier) {
                AndroidView(
                    factory = { playerView },
                    modifier = Modifier.fillMaxSize()
                )

                if (handle.hasError) {
                    // Embed-blocked / not-found / generic player error: cover
                    // YouTube's own tiny error page with a friendly Hindi
                    // message + an explicit "Open in YouTube" intent so the
                    // student is never stuck. Chrome (play/pause/seek) is
                    // suppressed in this branch — the student can't do
                    // anything useful with a player that's reporting an error.
                    VideoErrorOverlay(
                        videoId = videoId,
                        onOpenInYouTube = {
                            // Generic ACTION_VIEW on the canonical watch URL.
                            // Android's intent dispatcher will route this to
                            // the YouTube app if installed (and the user has
                            // chosen it as the default for youtube.com URLs);
                            // otherwise the system chooser comes up with the
                            // installed browsers as fallback. Going through the
                            // https URL is more robust than the
                            // `vnd.youtube:` scheme, which only opens the
                            // YouTube app and silently no-ops if it isn't
                            // installed.
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/watch?v=$videoId")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(intent) }
                                .onFailure { _: Throwable ->
                                    // No YouTube app AND no browser — extremely
                                    // rare on a real device. Nothing useful we
                                    // can do beyond leaving the overlay up.
                                }
                        }
                    )
                } else {
                    // Top + bottom gradient scrim so the chrome is readable
                    // over any video frame. Only painted while the chrome is
                    // visible to keep the playing-state experience clean.
                    AnimatedVisibility(
                        visible = chromeVisible,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            ChromeScrim(modifier = Modifier.fillMaxSize())
                            ChromeOverlay(
                                handle = handle,
                                isFullscreen = isFullscreen,
                                onFullscreenToggle = {
                                    bumpInteraction()
                                    onFullscreenToggle()
                                },
                                onCloseFullscreen = onCloseFullscreen,
                                onAnyInteraction = { bumpInteraction() },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // Big centre play button when paused / cued — gives the
                    // student a clear "tap to start" affordance even when the
                    // chrome is currently hidden.
                    if (!handle.isPlaying && handle.isReady) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable {
                                    scope.launch {
                                        handle.play()
                                        bumpInteraction()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ----------------------------- Chrome ----------------------------- */

@Composable
private fun ChromeScrim(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        // Top scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)
                    )
                )
        )
        // Bottom scrim
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(96.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                    )
                )
        )
    }
}

@Composable
private fun ChromeOverlay(
    handle: VideoPlaybackHandle,
    isFullscreen: Boolean,
    onFullscreenToggle: () -> Unit,
    onCloseFullscreen: () -> Unit,
    onAnyInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var qualityMenuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {

        /* Top-right cluster: quality + speed + fullscreen */
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Quality selector button.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable {
                        qualityMenuExpanded = true
                        onAnyInteraction()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = handle.preferredQuality,
                    color = Color.White,
                    fontSize = 13.sp
                )
                DropdownMenu(
                    expanded = qualityMenuExpanded,
                    onDismissRequest = { qualityMenuExpanded = false }
                ) {
                    listOf("Auto", "360p", "480p", "720p", "1080p").forEach { q ->
                        DropdownMenuItem(
                            text = { Text(q) },
                            onClick = {
                                handle.preferredQuality = q
                                qualityMenuExpanded = false
                                onAnyInteraction()
                            }
                        )
                    }
                }
            }

            // Speed cycle button.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable {
                        handle.cyclePlaybackRate()
                        onAnyInteraction()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = playbackRateLabel(handle.playbackRate),
                    color = Color.White,
                    fontSize = 13.sp
                )
            }

            // Fullscreen toggle.
            ChromeIconButton(
                imageVector = if (isFullscreen) Icons.Default.FullscreenExit
                              else Icons.Default.Fullscreen,
                contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                onClick = {
                    onFullscreenToggle()
                }
            )

            // Explicit Close-X for fullscreen mode (separate from the
            // toggle button, keeps the gesture obvious for new users).
            if (isFullscreen) {
                ChromeIconButton(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close fullscreen",
                    onClick = { onCloseFullscreen() }
                )
            }
        }

        /* Bottom cluster: skip-back, play/pause, skip-forward, time, seekbar */
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Time + scrubbable seekbar.
            val current = handle.currentSec
            val total = handle.durationSec.coerceAtLeast(0.0)
            val progressFraction =
                if (total > 0.0) (current / total).toFloat().coerceIn(0f, 1f)
                else 0f

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatVideoTime(current),
                    color = Color.White,
                    fontSize = 12.sp
                )
                Slider(
                    value = progressFraction,
                    onValueChange = { f ->
                        if (total > 0.0) {
                            scope.launch {
                                handle.seekTo(f.toDouble() * total)
                                onAnyInteraction()
                            }
                        }
                    },
                    enabled = handle.isReady && total > 0.0,
                    colors = SliderDefaults.colors(
                        thumbColor       = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatVideoTime(total),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(2.dp))

            // Transport buttons row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                ChromeIconButton(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = "Back 10 seconds",
                    onClick = {
                        handle.seekBy(-10.0)
                        onAnyInteraction()
                    }
                )
                Spacer(Modifier.size(20.dp))

                // Play / pause — bigger circle so it's the obvious primary.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable {
                            if (handle.isPlaying) handle.pause() else handle.play()
                            onAnyInteraction()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (handle.isPlaying) Icons.Default.Pause
                                      else Icons.Default.PlayArrow,
                        contentDescription = if (handle.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.size(20.dp))

                ChromeIconButton(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = "Forward 10 seconds",
                    onClick = {
                        handle.seekBy(10.0)
                        onAnyInteraction()
                    }
                )
            }
        }
    }
}

@Composable
private fun ChromeIconButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Default 16:9 wrapper for the inline (non-fullscreen) case. The
 * Discussion screen uses this as a sticky top header above the chat;
 * fullscreen mode replaces the whole screen with a fillMaxSize variant.
 */
@Composable
fun YouTubeVideoArea16x9(
    videoId: String,
    handle: VideoPlaybackHandle,
    isFullscreen: Boolean,
    onFullscreenToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    YouTubeVideoArea(
        videoId = videoId,
        handle = handle,
        isFullscreen = isFullscreen,
        onFullscreenToggle = onFullscreenToggle,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    )
}

/**
 * Painted on top of the iframe whenever YouTube reports an embed-side
 * error (most commonly "Video unavailable in embedded player" / 150 /
 * 152). Replaces YouTube's own tiny "Watch on YouTube" page with a
 * cleaner Hindi message + a single button that fires an
 * `Intent.ACTION_VIEW` against `vnd.youtube:VIDEO_ID` (preferred — opens
 * the YouTube app directly) and falls back to a browser intent for
 * the same `https://www.youtube.com/watch?v=…` URL.
 *
 * The error is ALMOST always YouTube-side embed restriction (a lot of
 * music / VEVO content disables embedding). We deliberately don't try
 * to differentiate error codes — from the student's POV the outcome is
 * identical and the universal escape hatch is "watch on YouTube." The
 * chat below still works exactly as before, with the AI grounded on
 * the cached transcript window — so the student can still raise
 * doubts even if they end up watching the actual video in the YouTube
 * app side-by-side.
 */
@Composable
private fun VideoErrorOverlay(
    videoId: String,
    onOpenInYouTube: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Yeh video iss app ke andar nahi chal payi.",
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Iss video ke owner ne embedded play disable kar diya hai. " +
                    "YouTube mein jaake direct dekh sakte hain — neeche chat aur " +
                    "transcript se doubt phir bhi pooch sakte hain.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onOpenInYouTube() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "YouTube mein kholiye",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "ID: $videoId",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp
            )
        }
    }
}

private const val CHROME_AUTO_HIDE_MS = 3500L
