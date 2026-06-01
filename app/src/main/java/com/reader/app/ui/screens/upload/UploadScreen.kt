package com.reader.app.ui.screens.upload

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reader.app.ui.components.ScreenScaffold

@Composable
fun UploadScreen(
    onBack: () -> Unit,
    onCreated: (id: Long, isVideoDoc: Boolean) -> Unit,
    vm: UploadViewModel = viewModel(factory = UploadViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.createdId) {
        state.createdId?.let { onCreated(it, state.createdAsVideoDoc) }
    }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.loadFromUri(context, uri)
    }

    ScreenScaffold(title = "New document", onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

            Text(
                "Text-based documents supported hain — .txt, .md, .csv, .pdf " +
                    "(selectable text wala), .pptx, .docx. Image-based ya scanned " +
                    "PDFs reject ho jayenge.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            // ---------- File picker ----------

            SectionHeading("FILE SE IMPORT")
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { pickFile.launch(UploadViewModel.ACCEPTED_MIMES) },
                    enabled = !state.isLoadingFile && !state.isFetchingYouTube && !state.isImporting
                ) { Text(if (state.isLoadingFile) "Loading…" else "Pick file") }

                if (state.isLoadingFile) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(20.dp))

            // ---------- YouTube ----------

            SectionHeading("YOUTUBE URL SE IMPORT")
            Spacer(Modifier.height(6.dp))
            Text(
                "Video ka link paste karein — Hindi captions ya subtitles fetch karenge. " +
                    "Hindi captions na ho to video ka title use hoga taaki student usi par " +
                    "doubt discuss kar sake. (Sirf Hindi captions hi accept hote hain.)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = state.youTubeUrl,
                onValueChange = vm::setYouTubeUrl,
                label = { Text("YouTube URL") },
                placeholder = { Text("https://www.youtube.com/watch?v=…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { vm.fetchFromYouTube() },
                    enabled = state.youTubeUrl.isNotBlank() &&
                              !state.isFetchingYouTube &&
                              !state.isLoadingFile &&
                              !state.isImporting
                ) { Text(if (state.isFetchingYouTube) "Fetching…" else "Fetch transcript") }

                if (state.isFetchingYouTube) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                    )
                }
            }
            if (state.youTubeStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.youTubeStatus!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(20.dp))

            // ---------- Title + body ----------

            SectionHeading("DOCUMENT")
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = state.title,
                onValueChange = vm::setTitle,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.rawText,
                onValueChange = vm::setRawText,
                label = { Text("Content (file ya YouTube se auto-fill ho jata hai, ya manually paste karein)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            )

            if (state.error != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.import() },
                    enabled = !state.isImporting &&
                              !state.isLoadingFile &&
                              !state.isFetchingYouTube &&
                              state.title.isNotBlank() &&
                              state.rawText.isNotBlank()
                ) { Text(if (state.isImporting) "Importing…" else "Save document") }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionHeading(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 1.4.sp
        ),
        color = MaterialTheme.colorScheme.primary
    )
}
