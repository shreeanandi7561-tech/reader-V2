package com.reader.app.ui.screens.ttssettings

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reader.app.ui.components.ScreenScaffold

/**
 * TTS Settings screen — granular voice control:
 *   - Language selection (Hindi default + 11 Indian languages + English)
 *   - Voice selection (filtered to chosen language)
 *   - Pitch slider 0.5x - 2.0x
 *   - Speech rate slider 0.5x - 2.0x
 *   - Test button speaks a preview sample
 *   - Save persists to Room
 */
@Composable
fun TtsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: TtsSettingsViewModel = viewModel(
        factory = TtsSettingsViewModel.factory(context.applicationContext)
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.ensureReady() }
    LaunchedEffect(state.savedMessage) {
        state.savedMessage?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    ScreenScaffold(title = "Voice & Speech", onBack = onBack) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                Text(
                    "Customise how the document is read aloud. Hindi is set by default. Adjust pitch and speed to your comfort. Changes are saved automatically — Save button just confirms.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))

                SectionHeader("LANGUAGE")
                Spacer(Modifier.height(8.dp))
                LanguageDropdown(
                    selectedTag    = state.languageTag,
                    options        = state.availableLanguages,
                    onSelect       = vm::setLanguage
                )

                Spacer(Modifier.height(24.dp))
                SectionHeader("VOICE")
                Spacer(Modifier.height(8.dp))
                if (state.availableVoices.isEmpty()) {
                    Text(
                        "No specific voices available for the selected language. The system default will be used.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    VoiceDropdown(
                        selectedName   = state.voiceName,
                        options        = state.availableVoices,
                        onSelect       = vm::setVoice
                    )
                }

                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(24.dp))

                SectionHeader("PITCH")
                Text(
                    "${"%.1f".format(state.pitch)}x — ${pitchHint(state.pitch)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.pitch,
                    onValueChange = vm::setPitch,
                    valueRange = 0.5f..2.0f,
                    steps = 14
                )

                Spacer(Modifier.height(20.dp))
                SectionHeader("SPEED")
                Text(
                    "${"%.1f".format(state.speechRate)}x — ${rateHint(state.speechRate)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.speechRate,
                    onValueChange = vm::setSpeechRate,
                    valueRange = 0.5f..2.0f,
                    steps = 14
                )

                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = vm::testVoice) { Text("Test voice") }
                    if (state.hasUnsavedChanges) {
                        Button(onClick = vm::save) { Text("Save") }
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

                Spacer(Modifier.height(80.dp))     // breathing room above the snackbar
            }

            // Snackbar OUTSIDE the scroll column so "Voice settings saved"
            // (and any future feedback) is visible no matter where the user
            // is scrolled to. Was a child of the scrolling Column before,
            // which meant the user had to scroll to the bottom to even see
            // the toast — they assumed Save wasn't working.
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
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selectedTag: String,
    options: List<TtsSettingsViewModel.LanguageOption>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.tag == selectedTag }?.display ?: selectedTag

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text("Language") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.display) },
                    onClick = { onSelect(opt.tag); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDropdown(
    selectedName: String?,
    options: List<TtsSettingsViewModel.VoiceOption>,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val display = if (selectedName == null) "System default"
    else options.firstOrNull { it.name == selectedName }?.display ?: selectedName

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text("Voice") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("System default") },
                onClick = { onSelect(null); expanded = false }
            )
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.display) },
                    onClick = { onSelect(opt.name); expanded = false }
                )
            }
        }
    }
}

private fun pitchHint(p: Float): String = when {
    p < 0.85f -> "deeper"
    p > 1.15f -> "higher"
    else      -> "natural"
}

private fun rateHint(r: Float): String = when {
    r < 0.85f -> "slower"
    r > 1.15f -> "faster"
    else      -> "natural"
}
