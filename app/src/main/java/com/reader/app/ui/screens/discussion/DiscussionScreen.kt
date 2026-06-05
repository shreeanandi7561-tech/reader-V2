package com.reader.app.ui.screens.discussion

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.reader.app.domain.text.RichTextRenderer
import com.reader.app.ui.components.JumpToSpokenPill
import com.reader.app.ui.components.SpokenAutoScrollState
import com.reader.app.ui.components.SpokenWordTracker
import com.reader.app.ui.components.ThinkingPill
import com.reader.app.ui.components.rememberSpokenAutoScroll
import com.reader.app.ui.components.spokenAutoScrollGestures
import com.reader.app.ui.components.spokenAutoScrollViewport
import com.reader.app.ui.video.VideoPlaybackHandle
import com.reader.app.ui.video.WebViewFrameSource
import com.reader.app.ui.video.YouTubeVideoArea
import com.reader.app.ui.video.rememberVideoPlaybackHandle

/**
 * Discussion (Mode 2) — chat-style UI for math step doubts.
 *
 * Layout (top → bottom) for **plain text** documents (paste / file
 * pick): unchanged from before.
 *  1. Slim top bar (back, new chat, replay, stop).
 *  2. Chat history (LazyColumn).
 *  3. Bottom composer (mic + TextField + send).
 *
 * **YouTube documents** add an industrial-feel video player above the
 * chat:
 *  1. Slim top bar (same).
 *  2. **Sticky 16:9 [YouTubeVideoArea]** with fully custom Compose
 *     chrome on top of YouTube's IFrame API (no native player UI, no
 *     branding, no "more videos" overlay). Controls: ⏪10 / play /
 *     pause / ⏩10, scrubbable seekbar, speed cycle, fullscreen toggle.
 *  3. Chat history.
 *  4. Composer.
 *
 * The same video player is shared between inline-16:9 and fullscreen
 * via `movableContentOf` — Compose moves the iframe view between the
 * two positions WITHOUT re-creating it, so playback never restarts
 * when the student fullscreens.
 *
 * **Doubt-asking flow with a video:**
 *  - Mic / send tap → `videoHandle.pause()` then VM is told.
 *  - VM reads the most recent `videoCurrentSec` (pushed by the player's
 *    `onCurrentSecond` callback into VM state), bundles a 60 s
 *    transcript window + tone profile + timestamp into the prompt
 *    (via `PromptBuilder.buildDiscussionWithVideoContext`).
 *  - AI streams the answer; TTS speaks. Per spec, the video is NOT
 *    auto-resumed — the student decides.
 *
 * **Rendering:** Assistant messages run through [RichTextRenderer]
 * which produces both a markdown / LaTeX-styled [AnnotatedString] for
 * display AND a stripped plain string for TTS. Char indices match
 * 1:1, so [DiscussionViewModel.UiState.nowSpokenRange] (a range into
 * the spoken text) is overlaid as an extra highlight span on top of
 * the rendered AnnotatedString — that's how per-word highlight stays
 * accurate even when the AI uses **bold** / *italic* / `$x^2$` etc.
 */
@Composable
fun DiscussionScreen(
    documentId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val vm: DiscussionViewModel = viewModel(
        factory = DiscussionViewModel.factory(documentId, context.applicationContext)
    )
    val state by vm.state.collectAsStateWithLifecycle()

    var hasMic by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    LaunchedEffect(Unit) {
        vm.ensureTtsReady()
        hasMic = vm.stt.hasPermission()
        if (!hasMic) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /* -------- Video player wiring (only relevant for YouTube docs) -------- */

    val videoHandle = rememberVideoPlaybackHandle()
    var isFullscreen by remember { mutableStateOf(false) }
    val videoId = state.youtubeVideoId

    val activity = context as? androidx.activity.ComponentActivity
    LaunchedEffect(isFullscreen) {
        if (activity != null) {
            val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            if (isFullscreen) {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
                windowInsetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Push the player's playhead into the VM ~once per second. This is
    // what `ask()` reads when building the prompt window. snapshotFlow
    // observes the Compose-state field on the handle; the VM stores it
    // for later. `distinctUntilChanged` is implicit (snapshotFlow only
    // emits on actual value change).
    LaunchedEffect(videoHandle, videoId) {
        if (videoId == null) return@LaunchedEffect
        snapshotFlow { videoHandle.currentSec }.collect { sec ->
            vm.setVideoCurrentSec(sec)
        }
    }

    // Mirror the iframe's error state into the VM so `ask()` knows to
    // skip the timestamp-window branch and fall back to a full-
    // transcript prompt. Without this, an embed-blocked video would
    // leave the AI anchored at a stale 0.0 timestamp every turn.
    LaunchedEffect(videoHandle, videoId) {
        if (videoId == null) return@LaunchedEffect
        snapshotFlow { videoHandle.hasError }.collect { errored ->
            vm.setVideoUnplayable(errored)
        }
    }

    // Register a WebView-snapshot frame source on the VM as soon as
    // the IFrame player view is composed, and unregister on dispose.
    // The VM's ask() will then upgrade Gemini-provider Discussion
    // doubts to multimodal calls (transcript + screenshots) so the
    // AI can mimic the tutor's exact written / drawn solution. When
    // the doc has no video, when the player is still initialising,
    // when the player view is in an error state, or when the active
    // provider is anything other than Gemini, this is a clean no-op
    // — the existing text-only path continues to run unchanged.
    LaunchedEffect(videoHandle, videoId) {
        if (videoId == null) {
            vm.setVideoFrameSource(null)
            return@LaunchedEffect
        }
        snapshotFlow { videoHandle.playerView }.collect { pv ->
            if (pv != null) {
                vm.setVideoFrameSource(
                    WebViewFrameSource(handle = videoHandle, playerView = pv)
                )
            } else {
                vm.setVideoFrameSource(null)
            }
        }
    }

    // Hardware back exits fullscreen first (if active) instead of
    // navigating off the screen. Same pattern YouTube's own app uses.
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    // Movable video content — placed either inside the inline column
    // (16:9 sticky top) or as a fullscreen overlay. movableContentOf
    // preserves the underlying YouTubePlayerView state across position
    // changes so playback is uninterrupted.
    val videoContent: (@Composable (Modifier, Boolean) -> Unit)? = remember(videoId) {
        if (videoId == null) null
        else movableContentOf<Modifier, Boolean> { modifier, fs ->
            YouTubeVideoArea(
                videoId = videoId,
                handle = videoHandle,
                isFullscreen = fs,
                onFullscreenToggle = { isFullscreen = !isFullscreen },
                onCloseFullscreen  = { isFullscreen = false },
                modifier = modifier
            )
        }
    }

    /* -------- Auto-pause-on-ask helper -------- */

    // Wraps any "ask the AI" trigger with `videoHandle.pause()`. Mic and
    // send both flow through here so we never accidentally talk over
    // the video. We do NOT auto-resume after the AI replies — per spec
    // the student decides when to resume the video.
    fun pauseVideoIfNeeded() {
        if (videoId != null) videoHandle.pause()
    }

    // Cooperative auto-scroll: follow the row TTS is currently speaking,
    // but PAUSE following the moment the student drags the list — so they
    // can scroll back to re-read an earlier step without the viewport
    // yanking them forward at every sentence boundary. A "Yahan padha ja
    // raha hai" pill (rendered below) lets them re-engage with one tap.
    //
    // Word-level smooth follow is also active via [SpokenWordTracker]
    // wired into each chat row's body
    val autoScroll = rememberSpokenAutoScroll(
        nowSpeakingIndex = state.nowSpeakingIndex,
        nowSpokenRange   = state.nowSpokenRange,
        fallbackIndex    = (state.messages.size - 1).coerceAtLeast(0),
        enabled          = state.messages.isNotEmpty()
    )

    var inspectingFrame by remember { mutableStateOf<com.reader.app.domain.model.ImageData?>(null) }
    var fetchingFrameFor by remember { mutableStateOf<Double?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    if (inspectingFrame != null || fetchingFrameFor != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { 
            inspectingFrame = null
            fetchingFrameFor = null
        }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column {
                    if (fetchingFrameFor != null) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f/9f), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    } else {
                        val bmp = remember(inspectingFrame) {
                            try {
                                val base64 = inspectingFrame!!.base64
                                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (bmp != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bmp,
                                contentDescription = "Extracted Video Frame",
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f)
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f/9f), contentAlignment = Alignment.Center) {
                                Text("Failed to extract frame")
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = { 
                            inspectingFrame = null
                            fetchingFrameFor = null
                        }) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

        // -------- Fullscreen branch: video fills the entire screen,
        // chat + composer hidden behind. Movable content is moved
        // here, preserving its state.
        if (isFullscreen && videoContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                videoContent(Modifier.fillMaxSize(), true)
            }
        } else {
            DiscussionInlineLayout(
                state            = state,
                vm               = vm,
                videoContent     = videoContent,
                videoId          = videoId,
                pauseVideoIfNeeded = ::pauseVideoIfNeeded,
                onSeekTo         = { ts -> 
                    // Seek video for continuity
                    if (videoHandle.playerView != null) videoHandle.seekTo(ts)
                    
                    // Show exact image frame popup as requested by user
                    scope.launch {
                        fetchingFrameFor = ts
                        val frame = vm.captureSingleFrame(ts)
                        inspectingFrame = frame
                        fetchingFrameFor = null
                    }
                },
                autoScroll       = autoScroll,
                hasMic           = hasMic,
                onBack           = onBack,
                onRequestMicPermission = {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            )
        }
    }
}

/**
 * Inline (non-fullscreen) layout extracted into its own composable so
 * the parent can swap to the fullscreen branch with one if/else, and
 * so this big tree only re-runs when something inside it actually
 * changes.
 *
 * Receives the movable [videoContent] from the parent so the IFrame
 * player view can survive moving between this column and the
 * fullscreen overlay without re-creating.
 */
@Composable
private fun DiscussionInlineLayout(
    state: DiscussionViewModel.UiState,
    vm: DiscussionViewModel,
    videoContent: (@Composable (Modifier, Boolean) -> Unit)?,
    videoId: String?,
    pauseVideoIfNeeded: () -> Unit,
    onSeekTo: ((Double) -> Unit)?,
    autoScroll: SpokenAutoScrollState,
    hasMic: Boolean,
    onBack: () -> Unit,
    onRequestMicPermission: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {

        // ---------- Top bar ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.weight(1f))

            IconButton(onClick = {
                pauseVideoIfNeeded()
                vm.newSession()
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Naya chat shuru karein")
            }
            if (state.phase == DiscussionViewModel.Phase.Speaking) {
                IconButton(onClick = vm::stopSpeaking) {
                    Icon(Icons.Default.Pause, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = {
                        pauseVideoIfNeeded()
                        vm.replayLastAnswer()
                    },
                    enabled = state.messages.any {
                        it.type == DiscussionViewModel.MsgType.Assistant && !it.isStreaming && it.text.isNotBlank()
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Replay last answer")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ---------- Sticky video (only for YouTube docs) ----------
        if (videoContent != null) {
            videoContent(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                false
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        // ---------- Chat history ----------
        Box(modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
        ) {
            LazyColumn(
                state = autoScroll.lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .spokenAutoScrollViewport(autoScroll)
                    .spokenAutoScrollGestures(autoScroll),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            text = if (videoId != null)
                                "Video chalaiye. Doubt aaye to mic dabaiye ya neeche " +
                                    "type karein — AI us exact moment ka context " +
                                    "(transcript + timestamp) le kar tutor ke style mein " +
                                    "samjhayega."
                            else
                                "Math ya step ka doubt likhein ya mic dabaiye — jaise " +
                                    "\"Step 3 mein kya kiya gaya hai?\" Pichhle " +
                                    "sawal ka jawab dene ke liye bhi pooch sakte hain.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                itemsIndexed(state.messages) { idx, msg ->
                    ChatMessageRow(
                        msg               = msg,
                        isCurrentlySpoken = idx == state.nowSpeakingIndex,
                        spokenRange       = state.nowSpokenRange.takeIf { idx == state.nowSpeakingIndex },
                        autoScroll        = autoScroll,
                        onTimestampClick  = onSeekTo
                    )
                }

                // Live STT chip — appears at the bottom of the chat
                // while the mic is open, mirroring Mode 1.
                if (state.phase == DiscussionViewModel.Phase.Capturing) {
                    item {
                        ChatMessageRow(
                            msg = DiscussionViewModel.ChatMessage(
                                type        = DiscussionViewModel.MsgType.User,
                                text        = state.partialTranscript.ifBlank { "Sun raha hoon…" },
                                isStreaming = true
                            ),
                            isCurrentlySpoken = false,
                            spokenRange       = null,
                            autoScroll        = autoScroll,
                            onTimestampClick  = onSeekTo
                        )
                    }
                }
            }

            if (state.phase == DiscussionViewModel.Phase.Asking &&
                state.messages.lastOrNull()
                    ?.let { it.type == DiscussionViewModel.MsgType.Assistant && it.text.isBlank() } == true
            ) {
                // Animated thinking pill — same component as Reading
                // mode. Only shows BEFORE the first token of the
                // streaming answer arrives (text isBlank). Once tokens
                // start streaming, the chat row itself becomes the
                // "alive" indicator.
                ThinkingPill(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }

            // Re-engagement pill — only meaningful while a message is
            // actually being spoken AND the student has scrolled away.
            JumpToSpokenPill(
                state       = autoScroll,
                targetIndex = state.nowSpeakingIndex ?: -1
            )

            if (state.error != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ---------- Composer ----------
        Composer(
            query        = state.query,
            onQuery      = vm::setQuery,
            onSend       = {
                pauseVideoIfNeeded()
                vm.ask()
            },
            sendEnabled  = state.phase != DiscussionViewModel.Phase.Asking &&
                           state.phase != DiscussionViewModel.Phase.Capturing &&
                           state.query.isNotBlank(),
            isCapturing  = state.phase == DiscussionViewModel.Phase.Capturing,
            micEnabled   = state.phase != DiscussionViewModel.Phase.Asking,
            onMic        = {
                pauseVideoIfNeeded()
                vm.toggleMic()
            },
            onRequestMicPermission = onRequestMicPermission,
            hasMic       = hasMic
        )
    }
}

@Composable
private fun Composer(
    query: String,
    onQuery: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    isCapturing: Boolean,
    micEnabled: Boolean,
    onMic: () -> Unit,
    onRequestMicPermission: () -> Unit,
    hasMic: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tap-to-toggle mic on the left of the composer. While capturing,
        // it lights up tertiary and tapping again finalises the STT
        // session — same UX as Mode 1's bottom bar.
        val micBg by animateColorAsState(
            targetValue = when {
                !micEnabled -> MaterialTheme.colorScheme.surfaceVariant
                isCapturing -> MaterialTheme.colorScheme.tertiary
                else        -> MaterialTheme.colorScheme.primary
            },
            label = "micBg"
        )
        val micFg = when {
            !micEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
            isCapturing -> MaterialTheme.colorScheme.onTertiary
            else        -> MaterialTheme.colorScheme.onPrimary
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(micBg)
                .clickable(enabled = micEnabled) {
                    if (!hasMic) onRequestMicPermission() else onMic()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Mic, contentDescription = "Mic", tint = micFg)
        }
        Spacer(Modifier.size(8.dp))

        TextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp, max = 160.dp),
            placeholder = {
                Text(
                    if (isCapturing) "Sun raha hoon…" else "Apna doubt likhein ya mic dabaiye…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor    = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                unfocusedContainerColor  = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                disabledContainerColor   = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                focusedIndicatorColor    = Color.Transparent,
                unfocusedIndicatorColor  = Color.Transparent,
                disabledIndicatorColor   = Color.Transparent
            ),
            shape = RoundedCornerShape(20.dp)
        )
        Spacer(Modifier.size(8.dp))

        val sendBg by animateColorAsState(
            targetValue = if (sendEnabled) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.surfaceVariant,
            label = "sendBg"
        )
        val sendFg = if (sendEnabled) MaterialTheme.colorScheme.onPrimary
                     else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(sendBg)
                .clickable(enabled = sendEnabled) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = sendFg)
        }
    }
}

/* --------------------------- chat row --------------------------- */

@Composable
private fun ChatMessageRow(
    msg: DiscussionViewModel.ChatMessage,
    isCurrentlySpoken: Boolean,
    spokenRange: IntRange?,
    autoScroll: SpokenAutoScrollState,
    onTimestampClick: ((Double) -> Unit)? = null
) {
    val rowBg by animateColorAsState(
        targetValue = if (isCurrentlySpoken)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
        else Color.Transparent,
        label = "rowBg"
    )

    val wordHighlightBg = MaterialTheme.colorScheme.primary
    val wordHighlightFg = MaterialTheme.colorScheme.onPrimary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBg)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        SourceLabel(msg.type)
        Spacer(Modifier.height(6.dp))

        // Assistant messages get full markdown + LaTeX rendering. User
        // messages stay plain so anything the student types (including
        // stray asterisks / underscores) is shown literally.
        val rendered = remember(msg.text, msg.type) {
            if (msg.type == DiscussionViewModel.MsgType.Assistant && msg.text.isNotEmpty())
                RichTextRenderer.render(msg.text)
            else
                RichTextRenderer.Rendered(msg.text, AnnotatedString(msg.text))
        }

        // Build the displayed AnnotatedString in three layers (later
        // wins on overlap):
        //   1. The rendered AnnotatedString (markdown / math styles).
        //   2. The per-word highlight from TTS (only on the speaking row).
        //   3. A trailing "▌" cursor that's NOT in the spoken string and
        //      never gets a highlight span.
        val display: AnnotatedString = remember(
            rendered, isCurrentlySpoken, spokenRange, msg.isStreaming, msg.type
        ) {
            buildAnnotatedString {
                append(rendered.annotated)

                if (isCurrentlySpoken && spokenRange != null) {
                    val plainLen = rendered.plain.length
                    val end   = (spokenRange.last + 1).coerceAtMost(plainLen)
                    val start = spokenRange.first.coerceAtLeast(0).coerceAtMost(end)
                    if (start < end) {
                        addStyle(
                            style = SpanStyle(
                                background = wordHighlightBg,
                                color      = wordHighlightFg,
                                fontWeight = FontWeight.Bold
                            ),
                            start = start,
                            end   = end
                        )
                    }
                }

                if (msg.isStreaming &&
                    msg.type == DiscussionViewModel.MsgType.Assistant &&
                    msg.text.isNotBlank()
                ) {
                    append(" ▌")
                }
            }
        }

        val style = when (msg.type) {
            DiscussionViewModel.MsgType.User      -> MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic
            )
            DiscussionViewModel.MsgType.Assistant -> MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp, lineHeight = 28.sp
            )
        }

        // Capture the Text composable's layout result + window position so
        // the auto-scroll helper can resolve the highlighted word's
        // bounding box and gently follow it as TTS progresses through a
        // long answer.
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        var textCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

        val hasMath = msg.text.contains("$") || msg.text.contains("\\(") || msg.text.contains("\\[") || msg.text.contains("\\begin")

        if (msg.isStreaming && msg.text.isBlank() && msg.type == DiscussionViewModel.MsgType.Assistant) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "…",
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (msg.type == DiscussionViewModel.MsgType.Assistant && !msg.isStreaming && hasMath) {
            val textColorBytes = MaterialTheme.colorScheme.onBackground.toArgb()
            val textColorHex = String.format("#%06X", (0xFFFFFF and textColorBytes))
            
            com.reader.app.ui.components.MathJaxViewer(
                markdown = msg.text,
                textColorHex = textColorHex,
                textSizePx = 17
            )
        } else {
            Text(
                text  = display,
                style = style,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.onGloballyPositioned { textCoords = it },
                onTextLayout = { textLayout = it }
            )
        }

        SpokenWordTracker(
            state             = autoScroll,
            isCurrentlySpoken = isCurrentlySpoken,
            spokenRange       = spokenRange,
            textLayout        = textLayout,
            textCoords        = textCoords
        )

        // Visual confirmation footer for multimodal answers. Only
        // appears under assistant messages whose answer was actually
        // grounded on attached video screenshots — empty list (the
        // text-only path) keeps this composable hidden.
        //
        // Why we expose this in the UI:
        //   - The student / developer asked us to make multimodal
        //     usage VISIBLE. The pure-backend implementation works
        //     correctly but offers no surface to verify "this answer
        //     used screenshots, that one didn't".
        //   - Listing the captured timestamps makes the connection
        //     between "what the tutor was writing at mm:ss" and the
        //     answer text concrete: the student can scrub the video
        //     to those exact moments and see the same board content
        //     the AI was reading.
        //
        // Styled small + secondary so it doesn't compete with the
        // answer text — it's a quiet receipt, not a feature pitch.
        if (msg.type == DiscussionViewModel.MsgType.Assistant &&
            msg.frameTimestampsSec.isNotEmpty()
        ) {
            FrameGroundingFooter(
                timestampsSec = msg.frameTimestampsSec,
                onTimestampClick = onTimestampClick
            )
        }
    }
}

/**
 * Subtle one-line "this answer used N video screenshots at mm:ss …"
 * indicator. Renders below the assistant message body when the
 * multimodal path produced the answer.
 *
 * Format examples:
 *   `Grounded on 1 video frame · 00:42`
 *   `Grounded on 3 video frames · 00:42 · 01:15 · 01:48`
 */
@Composable
private fun FrameGroundingFooter(
    timestampsSec: List<Double>,
    onTimestampClick: ((Double) -> Unit)? = null
) {
    if (timestampsSec.isEmpty()) return
    
    Spacer(Modifier.height(6.dp))
    
    val noun = if (timestampsSec.size == 1) "video frame" else "video frames"
    val prefix = "Grounded on ${timestampsSec.size} $noun · "
    
    // Use an inline layout to allow timestamps to wrap together
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
    
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = prefix, style = style, color = MaterialTheme.colorScheme.primary)
        
        timestampsSec.forEachIndexed { index, ts ->
            Text(
                text = formatTimestampMmSs(ts),
                style = style,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = if (onTimestampClick != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                modifier = Modifier.clickable(enabled = onTimestampClick != null) {
                    onTimestampClick?.invoke(ts)
                }
            )
            if (index < timestampsSec.lastIndex) {
                Text(text = "·", style = style, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Format `seconds` as `mm:ss` (or `hh:mm:ss` for >= 1 hour). */
private fun formatTimestampMmSs(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

@Composable
private fun SourceLabel(type: DiscussionViewModel.MsgType) {
    val (dot, label) = when (type) {
        DiscussionViewModel.MsgType.User      -> MaterialTheme.colorScheme.tertiary to "YOU"
        DiscussionViewModel.MsgType.Assistant -> MaterialTheme.colorScheme.primary to "TUTOR"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            ),
            color = dot
        )
    }
}
