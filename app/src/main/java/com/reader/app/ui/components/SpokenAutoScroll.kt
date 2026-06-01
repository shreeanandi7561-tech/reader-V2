package com.reader.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Cooperative auto-scroll for chat-style screens that follow a TTS cursor.
 *
 * The earlier behaviour was a flat `LaunchedEffect(nowSpeakingIndex) {
 * animateScrollToItem(target) }`. That works for the first sentence but is
 * actively hostile after that: every sentence boundary fires a fresh
 * scroll, so if the student scrolled up to re-read the previous step the
 * viewport gets yanked back down within ~500 ms. Effectively unusable
 * during a long TTS playback.
 *
 * This helper makes the auto-follow *cooperative* AND *word-aware*:
 *
 *  - **Row-level follow (default):** when [nowSpeakingIndex] changes the
 *    list animate-scrolls so the speaking row's top is at the viewport
 *    top — same as before. This is the "agar highlighted line screen
 *    par nahi hai to wahan le aao" behaviour.
 *  - **Word-level follow (new):** once the speaking row is on-screen,
 *    we read the [TextLayoutResult] of the row's body and the row's
 *    position inside the LazyColumn viewport, then *gently* scroll-by
 *    a small delta whenever the highlighted word's bottom edge crosses
 *    ~55% of the viewport. Each scroll only goes far enough to put
 *    the word back at ~50% of viewport — usually one line of text —
 *    so successive word events feel like a continuous slow crawl, not
 *    a series of jumps. This is the "jaise jaise TTS aage badhe, dhime
 *    dhime focus karke screen scroll karte jaenge" behaviour.
 *  - **User drags the list:** following is paused. Subsequent
 *    `nowSpeakingIndex` / word events do not move the viewport. A
 *    "Yahan padha ja raha hai" pill (rendered below) lets them
 *    re-engage with one tap.
 *  - **User taps the pill:** following resumes and we animate-scroll
 *    back to the current speaking row in one smooth jump.
 *
 * A *tap* on the list does NOT pause following — only a drag past the
 * platform touch slop does. This is the right contract: tapping the
 * surface (e.g. to dismiss the IME) shouldn't break TTS follow.
 *
 * Re-engagement is explicit (pill tap), not time-based. Auto-resuming
 * after a few seconds of idleness is too surprising — the student may
 * have stopped scrolling specifically to re-read something.
 */
@Stable
class SpokenAutoScrollState internal constructor(
    val lazyListState: LazyListState
) {
    /** True once the user has dragged the list; cleared by [resume]. */
    var followingPaused: Boolean by mutableStateOf(false)
        private set

    /**
     * LazyColumn's top edge in window-pixel coordinates. Reported by
     * [spokenAutoScrollViewport]. Stays at 0 until the first layout
     * pass — [viewportHeightPx] is the gate the auto-scroll uses to
     * know whether the viewport is measured yet.
     */
    internal var viewportTopPx: Float by mutableStateOf(0f)
    internal var viewportHeightPx: Float by mutableStateOf(0f)

    /**
     * Window-coordinate vertical bounds of the word currently being
     * highlighted by TTS. Reported by [SpokenWordTracker]. `null` when
     * nothing is being spoken or before the first onRangeStart event
     * arrives in the active row.
     */
    internal var wordTopPx: Float? by mutableStateOf(null)
    internal var wordBottomPx: Float? by mutableStateOf(null)

    internal fun pauseFromUserDrag() {
        if (!followingPaused) followingPaused = true
    }

    /**
     * Re-engage following. The auto-scroll [LaunchedEffect] in
     * [rememberSpokenAutoScroll] keys on [followingPaused], so flipping
     * this to false naturally triggers a scroll to the current target —
     * no need to also animate-scroll here (which would race with the
     * effect and double-issue).
     */
    internal fun resume() {
        followingPaused = false
    }

    internal fun reportViewport(topPx: Float, heightPx: Float) {
        viewportTopPx = topPx
        viewportHeightPx = heightPx
    }

    internal fun reportSpokenWord(topPx: Float?, bottomPx: Float?) {
        wordTopPx = topPx
        wordBottomPx = bottomPx
    }
}

/**
 * Hook up cooperative + word-aware auto-scroll to a TTS cursor.
 *
 * @param nowSpeakingIndex the index of the message currently being spoken,
 *   or `null` when nothing is being spoken.
 * @param nowSpokenRange   char range inside that message currently being
 *   pronounced. Drives the word-level smooth scroll. `null` when no
 *   word is highlighted (e.g. before the first onRangeStart).
 * @param fallbackIndex    where to scroll when nothing is being spoken
 *   (e.g. the last message in the chat). Pass `-1` to skip the fallback
 *   scroll.
 * @param enabled          when `false`, the helper does nothing — useful
 *   when the chat is empty and there's nothing to scroll to.
 */
@Composable
fun rememberSpokenAutoScroll(
    nowSpeakingIndex: Int?,
    nowSpokenRange: IntRange?,
    fallbackIndex: Int,
    enabled: Boolean = true
): SpokenAutoScrollState {
    val listState = rememberLazyListState()
    val state = remember(listState) { SpokenAutoScrollState(listState) }

    // Row-level auto-follow. Re-keys on followingPaused so a manual
    // pill resume immediately scrolls to the current target without
    // waiting for the next nowSpeakingIndex change.
    LaunchedEffect(nowSpeakingIndex, fallbackIndex, enabled, state.followingPaused) {
        if (!enabled) return@LaunchedEffect
        if (state.followingPaused) return@LaunchedEffect
        val target = nowSpeakingIndex ?: fallbackIndex
        if (target < 0) return@LaunchedEffect
        listState.animateScrollToItem(target)
    }

    // Word-level smooth follow. Triggers a tiny animateScrollBy whenever
    // the highlighted word's bottom edge crosses ~55% of viewport, just
    // enough to put it back at ~50% — usually one line of text. Successive
    // word events overlap and feel like a continuous gentle crawl. The
    // cap-and-target geometry is asymmetric on purpose: we never scroll
    // *up* in response to word events (that's row-level scroll's job at
    // the row boundary). Asymmetry avoids the "first-word-of-new-row
    // tug-back" bug where a midpoint-centring rule would yank the row
    // off the top of the viewport just because the first word starts
    // near the top.
    LaunchedEffect(
        enabled,
        state.followingPaused,
        nowSpeakingIndex,
        nowSpokenRange,
        state.wordTopPx,
        state.wordBottomPx,
        state.viewportTopPx,
        state.viewportHeightPx
    ) {
        if (!enabled) return@LaunchedEffect
        if (state.followingPaused) return@LaunchedEffect
        if (nowSpeakingIndex == null || nowSpokenRange == null) return@LaunchedEffect
        val wordBottom = state.wordBottomPx ?: return@LaunchedEffect
        val viewportH = state.viewportHeightPx
        if (viewportH <= 0f) return@LaunchedEffect

        val wordBottomInViewport = wordBottom - state.viewportTopPx
        val triggerLine = viewportH * WORD_FOLLOW_TRIGGER_FRACTION
        if (wordBottomInViewport <= triggerLine) return@LaunchedEffect

        val targetLine = viewportH * WORD_FOLLOW_TARGET_FRACTION
        val delta = wordBottomInViewport - targetLine
        if (delta < WORD_FOLLOW_MIN_DELTA_PX) return@LaunchedEffect
        listState.animateScrollBy(
            value = delta,
            animationSpec = tween(
                durationMillis = WORD_FOLLOW_DURATION_MS,
                easing = LinearEasing
            )
        )
    }

    return state
}

/**
 * Modifier for the LazyColumn that owns this auto-scroll. Reports the
 * column's vertical bounds in window-pixel coordinates so the word-
 * level effect can position the highlighted word inside it.
 *
 * Apply *in addition to* [spokenAutoScrollGestures] — the order doesn't
 * matter, they hook different things.
 */
fun Modifier.spokenAutoScrollViewport(state: SpokenAutoScrollState): Modifier =
    this.onGloballyPositioned { coords ->
        state.reportViewport(
            topPx    = coords.positionInWindow().y,
            heightPx = coords.size.height.toFloat()
        )
    }

/**
 * Place inside the *currently-speaking* chat row's body to feed the
 * word-level smooth follow.
 *
 * The row passes:
 *   - its [TextLayoutResult] (captured via `Text(onTextLayout = …)`)
 *   - its window-coords [LayoutCoordinates] (captured via
 *     `Modifier.onGloballyPositioned`)
 *
 * We then resolve the bounding box of the first char in [spokenRange]
 * and publish the word's window-pixel y-range upward so
 * [rememberSpokenAutoScroll]'s effect can scroll the LazyColumn.
 *
 * Safe to call on every row — when [isCurrentlySpoken] is false the
 * tracker stays passive. We don't aggressively *clear* the reported
 * bounds when the row stops speaking; the next speaker overwrites
 * within one frame, and if speech ends entirely the auto-scroll effect
 * is gated on `nowSpokenRange != null` so stale bounds are harmless.
 */
@Composable
fun SpokenWordTracker(
    state: SpokenAutoScrollState,
    isCurrentlySpoken: Boolean,
    spokenRange: IntRange?,
    textLayout: TextLayoutResult?,
    textCoords: LayoutCoordinates?
) {
    LaunchedEffect(
        isCurrentlySpoken, spokenRange, textLayout, textCoords
    ) {
        if (!isCurrentlySpoken) return@LaunchedEffect
        if (spokenRange == null) {
            state.reportSpokenWord(null, null)
            return@LaunchedEffect
        }
        val tlr = textLayout ?: return@LaunchedEffect
        val coords = textCoords ?: return@LaunchedEffect
        val len = tlr.layoutInput.text.length
        if (len <= 0) return@LaunchedEffect
        val charIdx = spokenRange.first.coerceIn(0, len - 1)
        val box = runCatching { tlr.getBoundingBox(charIdx) }.getOrNull()
            ?: return@LaunchedEffect
        val textTop = coords.positionInWindow().y
        state.reportSpokenWord(
            topPx    = textTop + box.top,
            bottomPx = textTop + box.bottom
        )
    }
}

/**
 * Modifier that watches for user drag gestures and pauses auto-follow.
 *
 * Implementation note: we attach this on the LazyColumn modifier with
 * `PointerEventPass.Initial` so we observe the gesture before the
 * LazyColumn's own scrollable does. We never consume any change, so the
 * LazyColumn still scrolls normally — we just snoop on the drag delta.
 *
 * We only flip `followingPaused` once the cumulative absolute drag exceeds
 * the platform touch slop. That way single taps (e.g. on a chat row to
 * dismiss the keyboard) don't accidentally pause TTS follow.
 */
fun Modifier.spokenAutoScrollGestures(state: SpokenAutoScrollState): Modifier =
    this.pointerInput(state) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            var dragAccum = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: break
                if (!change.pressed) break
                dragAccum += abs(change.positionChange().y)
                if (dragAccum > slop) {
                    state.pauseFromUserDrag()
                    break
                }
            }
        }
    }

/**
 * Pill that appears when the user has scrolled away during TTS playback.
 *
 * Place this inside the same `Box` as the LazyColumn; it self-positions
 * at the bottom-center via [BoxScope.align].
 *
 * @param targetIndex the message index to scroll back to (typically the
 *   current `nowSpeakingIndex`). Pass a negative value to keep the pill
 *   hidden even when paused — caller's responsibility to gate visibility
 *   on "is anything actually being spoken".
 */
@Composable
fun BoxScope.JumpToSpokenPill(
    state: SpokenAutoScrollState,
    targetIndex: Int,
    modifier: Modifier = Modifier,
    label: String = "Yahan padha ja raha hai"
) {
    val visible = state.followingPaused && targetIndex >= 0
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY  = { it }) + fadeOut(),
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 12.dp)
    ) {
        val bg = MaterialTheme.colorScheme.primary
        val fg = MaterialTheme.colorScheme.onPrimary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(bg)
                .clickable { state.resume() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = fg
            )
        }
    }
}

/* --- word-follow tuning constants ---
 *
 * These are deliberately conservative. Trigger fires when the word's
 * bottom edge crosses 55% of viewport height; we then animate-scroll
 * just enough to bring it back to 50%. A typical line of text is ~24-
 * 32 px tall; scrolling 5% of viewport (≈40 px on a 800-px viewport)
 * is roughly one line, so the student sees a continuous one-line-at-a-
 * time crawl as TTS reads through a long paragraph.
 *
 * Asymmetric (no upper-bound rule) by design — see
 * [rememberSpokenAutoScroll]'s comment.
 */
private const val WORD_FOLLOW_TRIGGER_FRACTION = 0.55f
private const val WORD_FOLLOW_TARGET_FRACTION  = 0.50f

/** Below this delta (window-px), don't bother scrolling. */
private const val WORD_FOLLOW_MIN_DELTA_PX = 4f

/**
 * Per-event scroll duration. Word events arrive every 200-400 ms; a
 * 280 ms tween gets cancelled and re-issued as new events come in,
 * which feels like a single continuous slow scroll rather than a
 * series of stutters.
 */
private const val WORD_FOLLOW_DURATION_MS = 280
