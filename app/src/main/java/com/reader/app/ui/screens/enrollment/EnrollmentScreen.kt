package com.reader.app.ui.screens.enrollment

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import com.reader.app.ui.components.MicButton
import com.reader.app.ui.components.ScreenScaffold

private val ENROLL_PROMPT = """
    Hold the mic and read this passage clearly for ~5 seconds, in your normal voice:

    "I am the only voice that may ask doubts in this app. Please remember the way I sound, the rhythm of my speech, and the tone of my reading."
""".trimIndent()

@Composable
fun EnrollmentScreen(
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val vm: EnrollmentViewModel = viewModel(factory = EnrollmentViewModel.factory(context.applicationContext))
    val state by vm.state.collectAsStateWithLifecycle()

    var hasMic by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }
    LaunchedEffect(Unit) { micPermission.launch(Manifest.permission.RECORD_AUDIO) }

    LaunchedEffect(state.phase) {
        if (state.phase == EnrollmentViewModel.Phase.Saved) onDone()
    }

    ScreenScaffold(title = "Voice enrollment", onBack = onBack) {
        Column {
            Text(
                ENROLL_PROMPT,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))

            val phaseLabel = when (state.phase) {
                EnrollmentViewModel.Phase.Idle       -> "Ready"
                EnrollmentViewModel.Phase.Recording  -> "Recording…"
                EnrollmentViewModel.Phase.Processing -> "Processing…"
                EnrollmentViewModel.Phase.Saved      -> "Saved"
                EnrollmentViewModel.Phase.Failed     -> state.message ?: "Failed"
            }
            Text(
                phaseLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (state.phase == EnrollmentViewModel.Phase.Failed)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MicButton(
                    isRecording  = state.phase == EnrollmentViewModel.Phase.Recording,
                    onPressStart = {
                        if (hasMic) vm.startRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onPressEnd   = { if (hasMic) vm.stopRecording() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Hold the mic while reading the passage. Release when done.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
