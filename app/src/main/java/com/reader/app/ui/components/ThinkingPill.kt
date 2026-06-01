package com.reader.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Animated "Tutor soch raha hai…" pill, shown at the bottom of chat
 * surfaces (Reading + Discussion) while the LLM is producing the
 * answer.
 *
 * **Why this exists:** the previous incarnation was a static pill
 * with the same text. The user's complaint was specifically that
 * "AI thinking mein hai iska koi visual cue hi nahi hai" — and
 * they're right: a static label doesn't read as "the system is
 * working", it reads as "the system has stopped". So this version:
 *
 *   1. Pulses three dots in sequence (1-2-3 → 1-2-3 → …) at ~3 Hz.
 *      Eye motion = "alive", which is the entire point.
 *   2. Keeps the same compact size + colour as the static pill so
 *      we don't need to re-layout the screens.
 *   3. Shares **one** infinite-transition between all dots so the
 *      animation phases are derived from a single time source —
 *      the dots are guaranteed to stay in 1/3 / 2/3 / 3/3 phase
 *      offsets even after process suspension/resume.
 *
 * Used from both [com.reader.app.ui.screens.reading.ReadingScreen]
 * and [com.reader.app.ui.screens.discussion.DiscussionScreen]; both
 * pass their own `label` ("Tutor soch raha hai…" by convention) so
 * the component can be reused anywhere a "thinking" affordance is
 * needed.
 */
@Composable
fun ThinkingPill(
    label: String = "Tutor soch raha hai…",
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "thinking-pill")
    // One-second cycle so each dot is visible for ~333 ms — fast
    // enough to read as motion, slow enough not to feel frantic.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue  = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.size(8.dp))
            // Three dots, each fully bright when its 1/3-of-cycle slot
            // is active and dim otherwise. Triangle-wave alpha rather
            // than a binary on/off avoids stroboscope-feel.
            for (i in 0..2) {
                val a = dotAlpha(phase, i)
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .alpha(a)
                        .background(MaterialTheme.colorScheme.onPrimary),
                )
                if (i < 2) Spacer(Modifier.size(4.dp))
            }
        }
    }
}

/**
 * Returns the alpha for [dotIndex] given the global animation
 * [phase] (0..3, looping). Each dot peaks when `phase` is at its
 * own slot (`dotIndex`) and decays linearly to a floor of 0.25.
 *
 * Math: `dist` = circular distance (in slot-units) between the dot
 * and the current phase, in `[0..1.5]`. Alpha = lerp(floor, 1.0)
 * over the closer half of the cycle.
 */
private fun dotAlpha(phase: Float, dotIndex: Int): Float {
    val raw = phase - dotIndex
    val wrapped = ((raw % 3f) + 3f) % 3f       // distance forward in cycle: [0, 3)
    val dist = if (wrapped > 1.5f) 3f - wrapped else wrapped  // shortest distance, [0, 1.5]
    val floor = 0.25f
    val k = (1f - (dist / 1.5f)).coerceIn(0f, 1f)
    return floor + (1f - floor) * k
}

/**
 * Variant that draws on top of the chat scroller without forcing a
 * background colour — used when we want the dots to float over an
 * already-coloured surface (e.g. on top of an Assistant chat row
 * that's already in `primaryContainer`).
 *
 * Currently unused but kept here because both Reading and Discussion
 * may move to in-row indicators when streaming starts but no tokens
 * have arrived yet — adding this now means we don't have to bikeshed
 * the API later.
 */
@Composable
fun InlineThinkingDots(
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "inline-thinking")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue  = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "inline-phase",
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .alpha(dotAlpha(phase, i))
                    .background(color),
            )
            if (i < 2) Spacer(Modifier.size(3.dp))
        }
    }
}
