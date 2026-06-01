package com.reader.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Composable side-effect that one-shot requests
 * [android.Manifest.permission.POST_NOTIFICATIONS] on Android 13+ if
 * not already granted.
 *
 * Drop this at the top of any screen that kicks off background work
 * which the user will later want a system notification for. If the
 * user denies, future generations still complete normally — the
 * notification step inside [com.reader.app.di.NotificationHelper] is
 * a silent no-op when the permission is missing, so denial never
 * blocks any feature.
 *
 * Pre-Tiramisu builds (API < 33) treat this permission as install-
 * time and always-granted, so this composable is a literal no-op on
 * older devices.
 */
@Composable
fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* swallow result; both paths are fine */ }

    // Single-shot per composition: only fire if not already granted.
    val alreadyGranted = remember {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(alreadyGranted) {
        if (!alreadyGranted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
