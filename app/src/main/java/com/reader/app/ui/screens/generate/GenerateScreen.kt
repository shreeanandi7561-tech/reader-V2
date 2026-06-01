package com.reader.app.ui.screens.generate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reader.app.ui.components.ScreenScaffold

/**
 * Generate hub — pick a "what to make" before drilling into the actual
 * generator screen. Two cards by design: MCQ test and PDF notes. Kept
 * deliberately empty otherwise — adding history rows / counts here
 * would create a third surface to maintain (the MCQ home and the
 * Notes screen each already own that).
 */
@Composable
fun GenerateScreen(
    documentId: Long,
    onBack: () -> Unit,
    onOpenMcq: (Long) -> Unit,
    onOpenNotes: (Long) -> Unit,
    vm: GenerateViewModel = viewModel(factory = GenerateViewModel.factory(documentId))
) {
    val state by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = state.title.ifBlank { "Generate" }, onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Iss document ke transcript se MCQ test ya printable PDF notes generate karein.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            GenerateCard(
                title    = "MCQ Test",
                subtitle = "Transcript se questions, options aur sahi answers nikaal kar timed test banayein.",
                bullets  = listOf(
                    "Auto-detect MCQs from teacher discussion",
                    "Timer, palette, save & resume, neg marking"
                ),
                onClick  = { onOpenMcq(documentId) }
            )
            Spacer(Modifier.height(16.dp))
            GenerateCard(
                title    = "PDF Notes",
                subtitle = "Imperfect transcript ko clean, structured study notes mein convert karein.",
                bullets  = listOf(
                    "Headings, bullets, examples, formula boxes",
                    "Live HTML preview + Save as PDF"
                ),
                onClick  = { onOpenNotes(documentId) }
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "Tip: ek alag MODE 3 · GENERATE API key Settings mein set kar sakte hain " +
                    "(generation longer hota hai, alag model pick kar sakte hain).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GenerateCard(
    title: String,
    subtitle: String,
    bullets: List<String>,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp))
            bullets.forEach { line ->
                Box(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "•  $line",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Open  →",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
