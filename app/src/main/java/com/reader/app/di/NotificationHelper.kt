package com.reader.app.di

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.reader.app.MainActivity

/**
 * Posts system notifications for the Generate section.
 *
 * Two channels:
 *  - `reader_generation_results` — IMPORTANCE_DEFAULT, fires once on
 *    success / failure, alerts (sound + vibration) so the user knows
 *    their work is ready.
 *  - `reader_generation_ongoing` — IMPORTANCE_LOW, no sound, used for
 *    the persistent foreground-service notification while the
 *    GenerationWorker is running. Drives the "Generating notes…"
 *    chip in the system tray that lets the user keep tabs on a job
 *    even after they close the app.
 *
 * Permission story: on Android 13+ POST_NOTIFICATIONS is runtime-
 * gated. We silently no-op result notifications when the user hasn't
 * granted it (generation still saves to the DB, so the next visit to
 * the screen shows the result). Foreground-service notifications are
 * exempt from POST_NOTIFICATIONS — they're tied to the service
 * lifecycle and the system shows them automatically.
 */
object NotificationHelper {

    const val CHANNEL_ID_RESULTS = "reader_generation_results"
    private const val CHANNEL_NAME_RESULTS = "Generation results"
    private const val CHANNEL_DESC_RESULTS = "MCQ test / PDF notes generation completion alerts."

    const val CHANNEL_ID_ONGOING = "reader_generation_ongoing"
    private const val CHANNEL_NAME_ONGOING = "Generation in progress"
    private const val CHANNEL_DESC_ONGOING = "Persistent notification while a background generation is running."

    /**
     * Notification id allocator. Using `documentId.toInt()` directly
     * would collide for two different doc types (MCQ + Notes) on the
     * same document — so each generation gets its own id derived from
     * the type + doc id.
     */
    fun notificationId(type: GenerationManager.Type, documentId: Long): Int {
        val typeOffset = when (type) {
            GenerationManager.Type.Mcq         -> 1_000_000
            GenerationManager.Type.Notes       -> 2_000_000
            GenerationManager.Type.PolishNotes  -> 3_000_000
        }
        return typeOffset + (documentId.toInt() and 0x7FFFFF)
    }

    /** Distinct id for the ongoing/foreground notification. */
    fun ongoingNotificationId(type: GenerationManager.Type, documentId: Long): Int =
        notificationId(type, documentId) + 100_000_000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID_RESULTS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID_RESULTS, CHANNEL_NAME_RESULTS,
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = CHANNEL_DESC_RESULTS }
                )
            }
            if (nm.getNotificationChannel(CHANNEL_ID_ONGOING) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID_ONGOING, CHANNEL_NAME_ONGOING,
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = CHANNEL_DESC_ONGOING }
                )
            }
        }
    }

    /**
     * Build (but do not post) the persistent notification the
     * GenerationWorker hands to `setForeground`. Caller posts it via
     * `ForegroundInfo`.
     */
    fun buildOngoingNotification(
        context: Context,
        title: String,
        body: String,
    ): android.app.Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_ID_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)  // indeterminate spinner
            .build()
    }

    /**
     * Post (or replace) a "ready" / "failed" notification. Tapping
     * opens the app via the launcher intent — once the user is in,
     * the home screen already shows the freshly-generated quiz / notes
     * via the Flow-backed observers, so we don't need a deep link for v1.
     */
    fun postCompletion(
        application: Application,
        type: GenerationManager.Type,
        documentId: Long,
        title: String,
        body: String,
    ) {
        ensureChannels(application)

        val openIntent = Intent(application, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            application,
            notificationId(type, documentId),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(application, CHANNEL_ID_RESULTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (canPost(application)) {
            try {
                @SuppressLint("MissingPermission")
                NotificationManagerCompat.from(application)
                    .notify(notificationId(type, documentId), notif)
            } catch (e: SecurityException) {
                // Silently ignore if permission was somehow revoked despite check
            }
        }
    }

    /**
     * Pre-Tiramisu (API < 33): permission is install-time, always
     * granted. Tiramisu+: must be granted at runtime; if the user
     * hasn't tapped through the prompt yet, silently no-op.
     *
     * Note: foreground-service notifications are exempt from this
     * check — they're posted by the system as part of the service
     * lifecycle and are always visible regardless of POST_NOTIFICATIONS
     * grant state.
     */
    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
