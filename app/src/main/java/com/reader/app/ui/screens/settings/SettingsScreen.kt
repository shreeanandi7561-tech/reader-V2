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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
                    "Global Gemini API Keys (Load Balanced)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Provide up to 10 Gemini API keys. The app will automatically round-robin and load balance across them to avoid hitting rate limits. Model is hardcoded to gemini-flash-latest.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))

                state.apiKeys.forEachIndexed { index, key ->
                    val status = state.keyStatuses.getOrNull(index)
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { vm.updateKey(index, it) },
                            label = { Text("API Key ${index + 1}") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (key.isNotBlank() && status != null) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Success: ${status.successCount}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "Failed: ${status.failureCount}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (status.failureCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (status.isCooldown) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Warning,
                                            contentDescription = "Cooldown",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Cooldown: ${status.cooldownRemainingSec}s",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else {
                                    Text(
                                        "Active",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                    )
                                }
                            }
                            if (status.lastError.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Error: ${status.lastError}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { vm.resetStats() },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Reset Metrics")
                    }

                    val hasChanges = state.apiKeys != state.originalApiKeys
                    if (hasChanges) {
                        Button(
                            onClick = { vm.saveKeys() },
                            enabled = state.apiKeys.any { it.isNotBlank() }
                        ) {
                            Text("Save Keys")
                        }
                    } else {
                        Button(
                            onClick = {},
                            enabled = false,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            )
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                contentDescription = "Saved",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Saved")
                        }
                    }
                }

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
