package com.reader.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Push-to-talk mic. [onPressStart] fires when finger touches; [onPressEnd]
 * fires on release/cancel — both used by the ReadingViewModel to drive STT.
 */
@Composable
fun MicButton(
    isRecording: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (isRecording) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        try {
                            tryAwaitRelease()
                        } finally {
                            onPressEnd()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Mic, contentDescription = "Hold to speak", tint = fg)
    }
}
