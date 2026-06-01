package com.reader.app.ui.screens.reading

import android.Manifest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.reader.app.domain.text.RichTextRenderer
import com.reader.app.ui.components.JumpToSpokenPill
import com.reader.app.ui.components.SpokenAutoScrollState
import com.reader.app.ui.components.SpokenWordTracker
import com.reader.app.ui.components.ThinkingPill
import com.reader.app.ui.components.rememberSpokenAutoScroll
import com.reader.app.ui.components.spokenAutoScrollGestures
import com.reader.app.ui.components.spokenAutoScrollViewport

/**
 * Chat-style Reading screen.
 *
 * Layout (top → bottom):
 *   1. A minimal top bar — only the back arrow. No book title.
 *   2. Thin progress bar (cursor / total).
 *   3. Chat timeline:
 *        - Doc messages (book sentences) appear one at a time as TTS
 *          speaks them. Inside the active message, the SINGLE WORD that
 *          the engine is pronouncing right now is highlighted (driven by
 *          [TtsController.currentWordRange]).
 *        - User questions and AI answers appear inline.
 *   4. Bottom **control bar** with all playback buttons + tap-to-toggle
 *      mic. The user explicitly asked for controls at the bottom.
 */
@Composable
fun ReadingScreen(
    documentId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val vm: ReadingViewModel = viewModel(
        factory = ReadingViewModel.factory(documentId, context.applicationContext)
    )
    val state by vm.state.collectAsStateWithLifecycle()

    var hasMic by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    LaunchedEffect(Unit) {
        vm.ensureTtsReady(context)
        hasMic = vm.stt.hasPermission()
        if (!hasMic) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Cooperative auto-scroll: follow the row TTS is currently speaking,
    // but PAUSE following the moment the student drags the list — so they
    // can scroll back to re-read an earlier sentence without the viewport
    // yanking them forward at every TTS boundary. A "Yahan padha ja raha
    // hai" pill (rendered below) lets them re-engage with one tap.
    //
    // Word-level smooth follow is also active via [SpokenWordTracker]
    // wired into each chat row's body — once the speaking row is on
    // screen, the viewport drifts gently to keep the highlighted word
    // inside a comfortable vertical band as TTS progresses through it.
    val autoScroll = rememberSpokenAutoScroll(
        nowSpeakingIndex = state.nowSpeakingIndex,
        nowSpokenRange   = state.nowSpokenRange,
        fallbackIndex    = (state.messages.size - 1).coerceAtLeast(0),
        enabled          = state.messages.isNotEmpty()
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Minimal top bar — just back.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (state.totalSentences > 0) {
                LinearProgressIndicator(
                    progress = {
                        state.cursorIndex.toFloat() /
                            state.totalSentences.toFloat().coerceAtLeast(1f)
                    },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

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
                                "Play dabaiye — document ek-ek sentence chat mein aakar TTS se " +
                                    "padha jayega. Mic dabaiye apna sawaal poochne ke liye.",
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
                            autoScroll        = autoScroll
                        )
                    }

                    if (state.phase == ReadingViewModel.Phase.Capturing) {
                        item {
                            ChatMessageRow(
                                msg = ReadingViewModel.ChatMessage(
                                    type        = ReadingViewModel.MsgType.User,
                                    text        = state.partialTranscript.ifBlank { "Sun raha hoon…" },
                                    isStreaming = true
                                ),
                                isCurrentlySpoken = false,
                                spokenRange       = null,
                                autoScroll        = autoScroll
                            )
                        }
                    }
                }

                if (state.phase == ReadingViewModel.Phase.Thinking) {
                    // Animated 3-dot pill — replaces a static "Tutor
                    // soch raha hai…" label that read as "frozen UI"
                    // because the text never moved. Dots pulse at
                    // ~3 Hz, see [ThinkingPill] for the timing math.
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
            BottomControlBar(
                phase       = state.phase,
                ttsReady    = state.ttsReady,
                hasMic      = hasMic,
                onReset     = vm::reset,
                onPrev      = vm::goBack,
                onPlayPause = vm::togglePlayPause,
                onNext      = vm::goForward,
                onMic       = vm::toggleMic,
                onRequestMicPermission = {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            )
        }
    }
}

/* --------------------------- bottom control bar --------------------------- */

@Composable
private fun BottomControlBar(
    phase: ReadingViewModel.Phase,
    ttsReady: Boolean,
    hasMic: Boolean,
    onReset: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onMic: () -> Unit,
    onRequestMicPermission: () -> Unit
) {
    val playIcon = if (phase == ReadingViewModel.Phase.Reading)
        Icons.Default.Pause else Icons.Default.PlayArrow
    val playLabel = if (phase == ReadingViewModel.Phase.Reading) "Pause" else "Play"

    val isCapturing = phase == ReadingViewModel.Phase.Capturing
    val micEnabled  = phase != ReadingViewModel.Phase.Thinking &&
                      phase != ReadingViewModel.Phase.Speaking

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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onReset, enabled = ttsReady, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset to start")
        }
        IconButton(onClick = onPrev, enabled = ttsReady, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous sentence")
        }
        IconButton(onClick = onPlayPause, enabled = ttsReady, modifier = Modifier.size(44.dp)) {
            Icon(playIcon, contentDescription = playLabel)
        }
        IconButton(onClick = onNext, enabled = ttsReady, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next sentence")
        }

        Spacer(Modifier.weight(1f))

        // Tap-to-toggle mic — Box + clickable so no FAB ripple eats events.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(micBg)
                .clickable(enabled = micEnabled) {
                    if (!hasMic) onRequestMicPermission() else onMic()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Mic, contentDescription = "Mic", tint = micFg)
        }
    }
}

/* --------------------------- chat row + word highlight --------------------------- */

@Composable
private fun ChatMessageRow(
    msg: ReadingViewModel.ChatMessage,
    isCurrentlySpoken: Boolean,
    spokenRange: IntRange?,
    autoScroll: SpokenAutoScrollState
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

        // Assistant messages get full markdown + LaTeX rendering — the
        // AI is allowed to use **bold** / *italic* / headings / lists /
        // `$x^2$`-style math and they show up styled. Doc messages
        // (book paragraphs) and User messages stay plain so anything in
        // the source / typed by the student is rendered literally.
        val rendered = remember(msg.text, msg.type) {
            if (msg.type == ReadingViewModel.MsgType.Assistant && msg.text.isNotEmpty())
                RichTextRenderer.render(msg.text)
            else
                RichTextRenderer.Rendered(msg.text, AnnotatedString(msg.text))
        }

        // Build the displayed AnnotatedString in three layers (later
        // wins on overlap):
        //   1. The rendered AnnotatedString (markdown / math styles for
        //      the AI message; plain text for Doc / User).
        //   2. The per-word TTS highlight (only on the speaking row).
        //      Char range is in `rendered.plain` coordinates which by
        //      construction are identical to AnnotatedString positions.
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
                    msg.type == ReadingViewModel.MsgType.Assistant &&
                    msg.text.isNotBlank()
                ) {
                    append(" ▌")
                }
            }
        }

        val style = when (msg.type) {
            ReadingViewModel.MsgType.Doc       -> MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp, lineHeight = 30.sp
            )
            ReadingViewModel.MsgType.User      -> MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic
            )
            ReadingViewModel.MsgType.Assistant -> MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp, lineHeight = 28.sp
            )
        }

        // Capture the Text composable's layout result + window position so
        // the auto-scroll helper can resolve the highlighted word's
        // bounding box and gently follow it as TTS progresses through a
        // long message.
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        var textCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

        Text(
            text  = display,
            style = style,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.onGloballyPositioned { textCoords = it },
            onTextLayout = { textLayout = it }
        )

        SpokenWordTracker(
            state             = autoScroll,
            isCurrentlySpoken = isCurrentlySpoken,
            spokenRange       = spokenRange,
            textLayout        = textLayout,
            textCoords        = textCoords
        )
    }
}

@Composable
private fun SourceLabel(type: ReadingViewModel.MsgType) {
    val (dot, label) = when (type) {
        ReadingViewModel.MsgType.Doc       -> MaterialTheme.colorScheme.onSurfaceVariant to "BOOK"
        ReadingViewModel.MsgType.User      -> MaterialTheme.colorScheme.tertiary to "YOU"
        ReadingViewModel.MsgType.Assistant -> MaterialTheme.colorScheme.primary to "TUTOR"
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
