package com.reader.app.ui.screens.mcq

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The actual attempt UI.
 *
 * Layout (top → bottom):
 *   1. Slim top bar — back, "Q i / N", elapsed/total timer, palette button.
 *   2. Linear progress bar — fraction = answered / total.
 *   3. Question + four option pills (tap to select, tap-again to clear).
 *   4. Bottom action row — Prev / Next on the sides, Submit in the middle.
 *
 * Two overlays:
 *   - **Question palette** (LazyVerticalGrid 5 columns, colour-coded for
 *     answered / unanswered / current), as a bottom sheet-like Surface.
 *   - **Submit confirmation** dialog — shows answered / unanswered /
 *     time-remaining counts so the student can double-check before
 *     committing.
 *
 * Hardware back is intercepted: if the palette is open it just closes
 * the palette; otherwise it pops the screen (current state has already
 * been auto-saved on every interaction so resume works seamlessly).
 */
@Composable
fun McqAttemptScreen(
    quizId: Long,
    onBack: () -> Unit,
    onShowResult: (Long) -> Unit,
    vm: McqAttemptViewModel = viewModel(factory = McqAttemptViewModel.factory(quizId))
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Submitted attempt → navigate to result. ViewModel resets the field
    // after we've consumed it so a recomposition doesn't re-fire.
    LaunchedEffect(state.submittedAttemptId) {
        val id = state.submittedAttemptId ?: return@LaunchedEffect
        onShowResult(id)
        vm.consumeNav()
    }

    BackHandler(enabled = state.showQuestionPalette || state.showSubmitConfirm) {
        when {
            state.showQuestionPalette -> vm.closePalette()
            state.showSubmitConfirm   -> vm.cancelSubmit()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.phase) {
            McqAttemptViewModel.Phase.Loading,
            McqAttemptViewModel.Phase.Submitting -> CenterLoader()
            McqAttemptViewModel.Phase.Error      -> ErrorState(state.errorMessage, onBack)
            McqAttemptViewModel.Phase.Done       -> CenterLoader()  // briefly, until nav fires
            McqAttemptViewModel.Phase.Active     -> ActiveAttempt(state, vm, onBack)
        }
    }
}

@Composable
private fun ActiveAttempt(
    state: McqAttemptViewModel.UiState,
    vm: McqAttemptViewModel,
    onBack: () -> Unit,
) {
    val current = state.questions.getOrNull(state.currentIndex) ?: return
    val total = state.questions.size
    val answered = state.selections.count { it != '_' }

    Column(modifier = Modifier.fillMaxSize()) {

        // ---------- Top bar ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Q ${state.currentIndex + 1} / $total",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            TimerChip(elapsedSec = state.elapsedSeconds, totalSec = state.timeLimitSeconds)
            Spacer(Modifier.size(8.dp))
            IconButton(onClick = vm::openPalette) {
                Icon(Icons.Default.Apps, contentDescription = "Question palette")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ---------- Progress ----------
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else answered.toFloat() / total },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )

        // ---------- Body ----------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                "Question ${state.currentIndex + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                current.question,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(20.dp))

            val selected = vm.selectionFor(state.currentIndex)
            listOf(current.optionA, current.optionB, current.optionC, current.optionD)
                .forEachIndexed { idx, label ->
                    OptionPill(
                        letter      = ('A' + idx).toString(),
                        text        = label,
                        isSelected  = selected == idx,
                        onClick     = {
                            if (selected == idx) vm.clearSelection(state.currentIndex)
                            else vm.select(state.currentIndex, idx)
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }

            if (selected != null) {
                TextButton(onClick = { vm.clearSelection(state.currentIndex) }) {
                    Text("Clear my choice")
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ---------- Bottom action row ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = vm::prev,
                enabled = state.currentIndex > 0,
            ) { Text("Previous") }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = vm::requestSubmit) { Text("Submit") }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = vm::next,
                enabled = state.currentIndex < total - 1,
            ) { Text("Next") }
        }
    }

    if (state.showQuestionPalette) {
        PaletteOverlay(state = state, vm = vm)
    }

    if (state.showSubmitConfirm) {
        SubmitConfirmDialog(
            answered     = answered,
            total        = total,
            elapsedSec   = state.elapsedSeconds,
            totalSec     = state.timeLimitSeconds,
            onConfirm    = { vm.submit() },
            onDismiss    = vm::cancelSubmit,
        )
    }
}

/* --------------------------- option pill --------------------------- */

@Composable
private fun OptionPill(
    letter: String,
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (isSelected) MaterialTheme.colorScheme.primary
                 else MaterialTheme.colorScheme.outline
    val bg = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
             else Color.Transparent
    val fg = if (isSelected) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .background(bg, RoundedCornerShape(10.dp)),
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(border.copy(alpha = if (isSelected) 0.20f else 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/* --------------------------- timer chip --------------------------- */

@Composable
private fun TimerChip(elapsedSec: Int, totalSec: Int) {
    val remaining = (totalSec - elapsedSec).coerceAtLeast(0)
    val almostUp = remaining <= 60
    val color = if (almostUp) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            "⏱ ${formatMmSs(remaining)}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

private fun formatMmSs(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

/* --------------------------- palette overlay --------------------------- */

@Composable
private fun PaletteOverlay(
    state: McqAttemptViewModel.UiState,
    vm: McqAttemptViewModel,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = vm::closePalette),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(enabled = false) { /* swallow taps inside the sheet */ },
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    "Question palette",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap any question to jump to it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                LegendRow()
                Spacer(Modifier.height(12.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(count = state.questions.size) { i ->
                        val isCurrent = i == state.currentIndex
                        val answered = state.selections.getOrNull(i)?.let { it != '_' } == true
                        PaletteCell(
                            number    = i + 1,
                            isCurrent = isCurrent,
                            answered  = answered,
                            onClick   = { vm.goTo(i) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = vm::closePalette,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Close") }
            }
        }
    }
}

@Composable
private fun PaletteCell(number: Int, isCurrent: Boolean, answered: Boolean, onClick: () -> Unit) {
    val (bg, fg) = when {
        isCurrent -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        answered  -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) to MaterialTheme.colorScheme.primary
        else      -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun LegendRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(4.dp))
        Text("Current", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(12.dp))
        LegendDot(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
        Spacer(Modifier.size(4.dp))
        Text("Answered", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(12.dp))
        LegendDot(MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.size(4.dp))
        Text("Skipped", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
    )
}

/* --------------------------- submit confirm dialog --------------------------- */

@Composable
private fun SubmitConfirmDialog(
    answered: Int,
    total: Int,
    elapsedSec: Int,
    totalSec: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val unanswered = (total - answered).coerceAtLeast(0)
    val remaining = (totalSec - elapsedSec).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Submit test?") },
        text    = {
            Column {
                Text("Answered: $answered / $total")
                Spacer(Modifier.height(4.dp))
                Text("Unanswered: $unanswered")
                Spacer(Modifier.height(4.dp))
                Text("Time remaining: ${formatMmSs(remaining)}")
                Spacer(Modifier.height(12.dp))
                Text(
                    "Negative marking: −0.33 har wrong answer ke liye " +
                        "(3 wrong = −1 mark). Skip kiye huye questions ka koi " +
                        "deduction nahi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Submit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Continue test") } },
    )
}

/* --------------------------- shared states --------------------------- */

@Composable
private fun CenterLoader() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String?, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message ?: "Kuch galat ho gaya.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
