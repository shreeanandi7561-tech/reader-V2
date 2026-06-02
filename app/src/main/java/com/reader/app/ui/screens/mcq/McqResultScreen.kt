package com.reader.app.ui.screens.mcq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import com.reader.app.ui.components.MathJaxViewer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reader.app.ui.components.ScreenScaffold

/**
 * Result + per-question breakdown for one submitted attempt.
 *
 * Top card: marks obtained / max marks, correct / wrong / skipped
 * counts, time taken. Below it a list of every question with the
 * student's pick, the correct option highlighted, and (when
 * confidence < 1.0) a "low-confidence — verify against source" note.
 */
@Composable
fun McqResultScreen(
    attemptId: Long,
    onBack: () -> Unit,
    vm: McqResultViewModel = viewModel(factory = McqResultViewModel.factory(attemptId))
) {
    val state by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Result", onBack = onBack) {
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.errorMessage != null -> Text(
                state.errorMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> ResultContent(state, vm)
        }
    }
}

@Composable
private fun ResultContent(
    state: McqResultViewModel.UiState,
    vm: McqResultViewModel,
) {
    val attempt = state.attempt ?: return
    val total = state.questions.size
    Column(modifier = Modifier.fillMaxSize()) {
        ScoreCard(
            marks      = attempt.marksObtained,
            maxMarks   = attempt.maxMarks,
            correct    = attempt.correctCount,
            wrong      = attempt.wrongCount,
            skipped    = attempt.skippedCount,
            total      = total,
            elapsedSec = attempt.elapsedSeconds,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "BREAKDOWN",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.questions, key = { _, q -> q.id }) { i, q ->
                val sel = vm.selectionAt(i)
                QuestionResultRow(
                    index             = i,
                    question          = q.question,
                    options           = listOf(q.optionA, q.optionB, q.optionC, q.optionD),
                    correctIndex      = q.correctIndex,
                    studentSelection  = sel,
                    confidence        = q.confidence,
                    source            = q.source,
                    originalSnippet   = q.originalSnippet,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ScoreCard(
    marks: Double,
    maxMarks: Double,
    correct: Int,
    wrong: Int,
    skipped: Int,
    total: Int,
    elapsedSec: Int,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "%.2f / %.0f".format(marks, maxMarks),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Score (with negative marking)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stat("Correct", "$correct", MaterialTheme.colorScheme.primary)
                Stat("Wrong",   "$wrong",   MaterialTheme.colorScheme.error)
                Stat("Skipped", "$skipped", MaterialTheme.colorScheme.onSurfaceVariant)
                Stat("Total",   "$total",   MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Time taken: ${elapsedSec / 60} min ${elapsedSec % 60} sec",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuestionResultRow(
    index: Int,
    question: String,
    options: List<String>,
    correctIndex: Int,
    studentSelection: Int?,
    confidence: Double,
    source: String,
    originalSnippet: String?,
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 12.dp)
    ) {
        Text(
            "Q${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        MathJaxViewer(
            markdown = question,
            textColorHex = String.format("#%06X", (0xFFFFFF and MaterialTheme.colorScheme.onBackground.toArgb()))
        )
        Spacer(Modifier.height(10.dp))

        options.forEachIndexed { i, label ->
            val isCorrect       = i == correctIndex
            val wasStudentPick  = studentSelection == i
            val (bg, fg) = when {
                isCorrect && wasStudentPick     -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) to MaterialTheme.colorScheme.primary
                isCorrect                       -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) to MaterialTheme.colorScheme.primary
                wasStudentPick                  -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f) to MaterialTheme.colorScheme.error
                else                            -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .padding(10.dp)
            ) {
                val suffix = when {
                    isCorrect && wasStudentPick -> "   ✓ correct (your pick)"
                    isCorrect                   -> "   ✓ correct"
                    wasStudentPick              -> "   ✗ your pick"
                    else                        -> ""
                }
                MathJaxViewer(
                    markdown = "${('A' + i)}.  $label$suffix",
                    textColorHex = String.format("#%06X", (0xFFFFFF and fg.toArgb()))
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        if (confidence < 0.85 || source == "ai_filled") {
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    if (source == "ai_filled") append("Some options were AI-filled. ")
                    if (confidence < 0.85)     append("Confidence ${"%.0f".format(confidence * 100)}% — verify against transcript.")
                }.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!originalSnippet.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    "Source: \"${originalSnippet.trim()}\"",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
