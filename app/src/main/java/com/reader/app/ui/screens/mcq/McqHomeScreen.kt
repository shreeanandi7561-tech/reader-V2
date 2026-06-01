package com.reader.app.ui.screens.mcq

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reader.app.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date

/**
 * MCQ home screen for a document.
 *
 * Shows:
 *   - "Generate new MCQ test" button. Once tapped, the generation
 *     runs on an Application-scoped coroutine via
 *     [com.reader.app.di.GenerationManager] — the user can leave the
 *     screen and a system notification will fire on completion.
 *   - A "running in background" banner with explicit "you can leave"
 *     guidance + an OK button so the user feels safe pressing Back.
 *   - List of previously-generated quizzes. Tap to start / resume.
 *   - Error / "blocked, no MCQs found" banner above the list.
 */
@Composable
fun McqHomeScreen(
    documentId: Long,
    onBack: () -> Unit,
    onOpenAttempt: (Long) -> Unit,
) {
    val context = LocalContext.current
    val vm: McqHomeViewModel = viewModel(
        factory = McqHomeViewModel.factory(documentId, context.applicationContext as android.app.Application)
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val quizzes by vm.quizzes.collectAsStateWithLifecycle()

    com.reader.app.ui.components.RequestNotificationPermission()

    // Auto-jump to the attempt screen when a fresh quiz arrives — but
    // only if the user is still on this screen. If they've already
    // left, the notification will alert them and they can come back
    // and tap the new quiz from the list.
    LaunchedEffect(state.freshlyCreatedQuizId) {
        val id = state.freshlyCreatedQuizId ?: return@LaunchedEffect
        onOpenAttempt(id)
        vm.consumeFreshQuizNav()
    }

    ScreenScaffold(title = "MCQ Tests", onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                state.title.ifBlank { "Document" },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "MCQ test sirf un videos pe banega jahan teacher questions, " +
                    "options aur answers discuss karte hain.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            GenerateButton(
                isBusy   = state.isBusy,
                progress = state.progressLabel,
                onClick  = vm::generateQuiz,
            )

            if (state.isBusy) {
                Spacer(Modifier.height(12.dp))
                BackgroundBanner(
                    message = "Generation background mein chal raha hai. Aap is screen se " +
                        "chale jaa sakte hain — jaise hi test ready hoga, notification mil jaayega.",
                    onLeave = onBack,
                )
            }

            if (state.errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Banner(
                    text   = state.errorMessage!!,
                    isError = true,
                    onDismiss = vm::consumeError,
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Text(
                "PREVIOUS TESTS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (quizzes.isEmpty()) {
                Text(
                    "Abhi tak koi MCQ test nahi banaya — upar 'Generate' dabaiye.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(quizzes, key = { it.id }) { quiz ->
                        QuizRow(
                            createdAt        = quiz.createdAt,
                            questionCount    = quiz.questionCount,
                            timeLimitSeconds = quiz.timeLimitSeconds,
                            onClick          = { onOpenAttempt(quiz.id) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun GenerateButton(
    isBusy: Boolean,
    progress: String?,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = !isBusy) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier   = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color      = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(8.dp))
            Text(progress ?: "Generate ho raha hai…")
        } else {
            Text("Generate new MCQ test")
        }
    }
}

@Composable
private fun BackgroundBanner(message: String, onLeave: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = onLeave) { Text("Theek hai, baad mein dekhunga") }
            }
        }
    }
}

@Composable
private fun Banner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (isError) MaterialTheme.colorScheme.onErrorContainer
                      else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onDismiss() },
        color = container,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
        )
    }
}

@Composable
private fun QuizRow(
    createdAt: Long,
    questionCount: Int,
    timeLimitSeconds: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$questionCount",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$questionCount questions · ${timeLimitSeconds / 60} min",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Generated ${DateFormat.getDateTimeInstance().format(Date(createdAt))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            "→",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
