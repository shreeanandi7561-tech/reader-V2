package com.reader.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reader.app.domain.model.AppMode
import com.reader.app.domain.model.LlmProvider
import com.reader.app.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?,
    onEnrollVoice: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.savedMessage) {
        state.savedMessage?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    ScreenScaffold(title = "Settings", onBack = onBack) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                Text(
                    "Bring-your-own keys for the LLM. Speech-to-text uses the phone's built-in recognizer (free, on-device).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))

                ModeBlock(
                    heading  = "MODE 1 · READING",
                    form     = state.reading,
                    original = state.readingOriginal,
                    onChange = { f -> vm.update(AppMode.Reading) { f } },
                    onSave   = { vm.save(AppMode.Reading) }
                )

                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(28.dp))

                ModeBlock(
                    heading  = "MODE 2 · DISCUSSION",
                    form     = state.discussion,
                    original = state.discussionOriginal,
                    onChange = { f -> vm.update(AppMode.Discussion) { f } },
                    onSave   = { vm.save(AppMode.Discussion) }
                )

                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(28.dp))

                ModeBlock(
                    heading  = "MODE 3 · GENERATE",
                    form     = state.generate,
                    original = state.generateOriginal,
                    onChange = { f -> vm.update(AppMode.Generate) { f } },
                    onSave   = { vm.save(AppMode.Generate) }
                )

                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(28.dp))

                EnrollmentBlock(
                    updatedAt = state.enrollmentUpdatedAt,
                    onEnroll  = onEnrollVoice,
                    onClear   = vm::clearEnrollment
                )

                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(28.dp))

                Text(
                    "VOICE & SPEECH",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Customise the language, voice, pitch, and speed of the document reader.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenTtsSettings) { Text("Open voice settings") }

                Spacer(Modifier.height(80.dp))
            }

            // Snackbar OUTSIDE the scrolling column so "MODE 1 saved" /
            // "MODE 2 saved" / "Voice enrollment cleared" feedback is
            // always visible at the bottom of the screen.
            SnackbarHost(
                hostState = snackbar,
                modifier  = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) { Snackbar { Text(it.visuals.message) } }
        }
    }
}

@Composable
private fun ModeBlock(
    heading: String,
    form: SettingsViewModel.ModeForm,
    original: SettingsViewModel.ModeForm,
    onChange: (SettingsViewModel.ModeForm) -> Unit,
    onSave: () -> Unit
) {
    Text(heading, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))

    ProviderDropdown(
        selected   = form.provider,
        onSelected = { onChange(form.copy(provider = it)) }
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = form.apiKey,
        onValueChange = { onChange(form.copy(apiKey = it)) },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = form.modelName,
        onValueChange = { onChange(form.copy(modelName = it)) },
        label = { Text("Model name") },
        placeholder = { Text(placeholderFor(form.provider)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        val hasChanges = form != original
        Button(
            onClick = onSave, 
            enabled = form.toConfig().isComplete() && hasChanges
        ) {
            Text(if (hasChanges) "Save" else "Saved")
        }
    }
}

@Composable
private fun EnrollmentBlock(
    updatedAt: Long?,
    onEnroll: () -> Unit,
    onClear: () -> Unit
) {
    Text(
        "VOICE ENROLLMENT",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    val statusText = if (updatedAt == null) {
        "Not enrolled. Without enrollment, hands-free barge-in is disabled — only the mic button will work."
    } else {
        "Enrolled on ${DateFormat.getDateTimeInstance().format(Date(updatedAt))}"
    }
    Text(statusText, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onEnroll) {
            Text(if (updatedAt == null) "Enroll my voice" else "Re-enroll")
        }
        if (updatedAt != null) {
            OutlinedButton(onClick = onClear) { Text("Clear") }
        }
    }
}

/** Known-good model identifiers per provider. Copy these exactly. */
private fun placeholderFor(p: LlmProvider): String = when (p) {
    LlmProvider.Groq      -> "llama-3.1-70b-versatile"
    LlmProvider.NvidiaNim -> "qwen/qwen3-coder-480b-a35b-instruct"
    LlmProvider.Gemini    -> "gemini-1.5-pro"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(
    selected: LlmProvider,
    onSelected: (LlmProvider) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LlmProvider.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.displayName) },
                    onClick = { onSelected(p); expanded = false }
                )
            }
        }
    }
}
