package com.reader.app.di

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Frontend for the Generate section's background work.
 *
 * **What this used to be:** an in-process singleton CoroutineScope
 * that ran generations on `Dispatchers.IO`. That kept the LLM call
 * alive across screen-pops but NOT across the user closing the app.
 *
 * **What this is now:** a thin wrapper over [WorkManager]. Each
 * generation enqueues a unique [GenerationWorker] which:
 *   - runs as a foreground service with a persistent notification;
 *   - keeps the process alive across swipe-from-recents and OS memory
 *     pressure;
 *   - re-enters the queue on reboot (WorkManager persists the
 *     WorkRequest table) so a transcript-import-then-immediately-
 *     close flow still produces notes the next morning.
 *
 * **Status flow:** the screens / VMs subscribe to [statusFor] which
 * maps WorkManager's `WorkInfo` directly into the same [Status]
 * sealed type the previous in-memory manager exposed. No VM API
 * change beyond passing the [Application] reference (already done in
 * the AndroidViewModel refactor).
 *
 * **`enqueueUniqueWork(KEEP)`** semantics: if the user taps
 * "Regenerate" while a generation is already running for the same
 * document, the new request is DROPPED (existing work continues).
 * This is the right behaviour for non-idempotent LLM calls — never
 * accidentally double-bill.
 */
object GenerationManager {

    enum class Type { Mcq, Notes, PolishNotes }

    data class Key(val type: Type, val documentId: Long) {
        /** Stable WorkManager unique-work name. */
        val workName: String get() = "${type.name.lowercase()}-$documentId"
    }

    sealed class Status {
        data object Idle : Status()
        data class Running(val message: String) : Status()
        data class Done(
            val docTitle: String,
            val resultId: Long,
        ) : Status()
        data class Failed(val message: String) : Status()
    }

    /* ---------------- public API ---------------- */

    fun startMcq(
        application: Application,
        documentId: Long,
        documentTitle: String,
    ) = enqueue(application, Type.Mcq, documentId, documentTitle)

    fun startNotes(
        application: Application,
        documentId: Long,
        documentTitle: String,
    ) = enqueue(application, Type.Notes, documentId, documentTitle)

    fun startPolishNotes(
        application: Application,
        documentId: Long,
        documentTitle: String,
    ) = enqueue(application, Type.PolishNotes, documentId, documentTitle)

    /**
     * Live status for one generation. The flow stays subscribed
     * through process death + restart — when the screen reopens the
     * app, the previous (in-flight or finished) WorkInfo is still
     * there and emits the right [Status] immediately.
     */
    fun statusFor(application: Application, key: Key): Flow<Status> =
        WorkManager.getInstance(application)
            .getWorkInfosForUniqueWorkFlow(key.workName)
            .map { infos ->
                val info = infos.firstOrNull() ?: return@map Status.Idle
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Status.Running(
                        "Queue mein hai — abhi shuru hoga…"
                    )
                    WorkInfo.State.RUNNING -> Status.Running(
                        info.progress.getString(GenerationWorker.PROGRESS_MESSAGE)
                            ?: "Generate ho raha hai…"
                    )
                    WorkInfo.State.SUCCEEDED -> Status.Done(
                        docTitle = info.outputData.getString(GenerationWorker.OUTPUT_DOC_TITLE).orEmpty(),
                        resultId = info.outputData.getLong(GenerationWorker.OUTPUT_RESULT_ID, 0L),
                    )
                    WorkInfo.State.FAILED -> Status.Failed(
                        info.outputData.getString(GenerationWorker.OUTPUT_ERROR)
                            ?: "Generation fail hua."
                    )
                    WorkInfo.State.CANCELLED -> Status.Failed("Generation cancel ho gaya.")
                }
            }
            .distinctUntilChanged()

    /**
     * `Status.Done` / `Status.Failed` are sticky in WorkManager — the
     * row stays SUCCEEDED until pruned. This helper deletes the
     * unique-work row so the screen's status flow flips back to
     * [Status.Idle], unblocking the user to start a fresh generation
     * (especially needed after a Failed state, otherwise re-tapping
     * Generate is a no-op due to KEEP policy).
     */
    fun consume(application: Application, key: Key) {
        // pruneWork() removes terminal rows globally; safer to just
        // cancel by name which both cancels (no-op for terminal) and
        // marks the row eligible for pruning.
        WorkManager.getInstance(application)
            .cancelUniqueWork(key.workName)
    }

    /* ---------------- internal ---------------- */

    private fun enqueue(
        application: Application,
        type: Type,
        documentId: Long,
        documentTitle: String,
    ) {
        val key = Key(type, documentId)
        val data = workDataOf(
            GenerationWorker.INPUT_TYPE to type.name,
            GenerationWorker.INPUT_DOCUMENT_ID to documentId,
            GenerationWorker.INPUT_DOCUMENT_TITLE to documentTitle,
        )
        val req = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // RUN_AS_NON_EXPEDITED_WORK_REQUEST: try to run as
            // expedited (foreground-quota-eligible, lower kill risk),
            // but if the app is out of expedited quota, fall back to
            // regular work rather than failing outright.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(application).enqueueUniqueWork(
            key.workName,
            ExistingWorkPolicy.KEEP,  // tap-while-running = no-op (no double-bill)
            req,
        )
    }
}
