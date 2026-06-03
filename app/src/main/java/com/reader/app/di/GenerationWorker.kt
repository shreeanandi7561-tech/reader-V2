package com.reader.app.di

import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.reader.app.domain.mcq.McqGenerator
import com.reader.app.domain.mcq.VideoQuestionDetector
import com.reader.app.domain.model.AppMode
import com.reader.app.domain.notes.NotesGenerator

/**
 * Long-running CoroutineWorker that runs ONE MCQ or PDF Notes
 * generation. Lives independently of the UI process — created by
 * WorkManager when [GenerationManager.startMcq] / [startNotes]
 * enqueue a unique work request.
 *
 * **Survives the app being closed.** Calling [setForeground] promotes
 * the worker to a foreground service with the persistent
 * "Generating…" notification. That keeps the worker process alive
 * across recents-swipe and OS memory pressure. Only force-stop via
 * Settings can end the work; that's the same boundary every Android
 * background task hits.
 *
 * **Never retries.** The worker explicitly returns `Result.failure()`
 * on errors instead of `Result.retry()` because the LLM call is non-
 * idempotent — a retry would re-bill the BYOK key for a call that
 * may have already partially succeeded. Users can re-tap Generate if
 * they want to try again.
 *
 * Output data conventions (consumed by [GenerationManager.statusFor]):
 *   - "docTitle" → the document title for the completion banner.
 *   - "resultId" → quizId (Mcq) or documentId (Notes), so the screen
 *     can navigate without an extra DB lookup.
 *   - "error"    → human-readable failure reason for the UI banner.
 */
class GenerationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val INPUT_TYPE = "type"
        const val INPUT_DOCUMENT_ID = "documentId"
        const val INPUT_DOCUMENT_TITLE = "documentTitle"

        const val OUTPUT_DOC_TITLE = "docTitle"
        const val OUTPUT_RESULT_ID = "resultId"
        const val OUTPUT_ERROR = "error"

        const val PROGRESS_MESSAGE = "message"
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val typeStr = inputData.getString(INPUT_TYPE)
            ?: return failure("Invalid generation type")
        val type = runCatching { GenerationManager.Type.valueOf(typeStr) }.getOrNull()
            ?: return failure("Unknown generation type: $typeStr")
        val documentId = inputData.getLong(INPUT_DOCUMENT_ID, -1L)
        if (documentId < 0) return failure("Missing documentId")
        val documentTitle = inputData.getString(INPUT_DOCUMENT_TITLE).orEmpty()
            .ifBlank { if (type == GenerationManager.Type.Mcq) "MCQ Test" else "Notes" }

        // Promote to foreground BEFORE the heavy work starts so the
        // persistent notification appears immediately and the OS
        // protects the process from kill. If quota / permission denies
        // foreground promotion, fall through and run as background
        // work — slightly higher kill risk but generation still
        // produces a result.
        runCatching { setForeground(buildForegroundInfo(type, documentTitle, "Starting…")) }

        return try {
            when (type) {
                GenerationManager.Type.Mcq -> doMcq(documentId, documentTitle)
                GenerationManager.Type.Notes -> doNotes(documentId, documentTitle)
                GenerationManager.Type.PolishNotes -> doPolishNotes(documentId, documentTitle)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e.message ?: "Generation fail hua.").also {
                postFailure(type, documentId, documentTitle, e.message ?: "Unknown error")
            }
        }
    }

    /* ---------------- MCQ pipeline ---------------- */

    private suspend fun doMcq(documentId: Long, documentTitle: String): androidx.work.ListenableWorker.Result {
        val cfg = ServiceLocator.configRepository.get(AppMode.Generate)
        if (cfg == null || !cfg.isComplete()) {
            return failure("Mode 3 settings configure karein. Settings → Mode 3 mein API key + model name daalein.")
                .also { postFailure(GenerationManager.Type.Mcq, documentId, documentTitle, "Mode 3 settings missing") }
        }

        setProgress(workDataOf(PROGRESS_MESSAGE to "Transcript check kar raha hoon…"))
        runCatching {
            setForeground(buildForegroundInfo(
                GenerationManager.Type.Mcq, documentTitle,
                "Transcript check kar raha hoon…"
            ))
        }

        val transcript = runCatching { ServiceLocator.documentRepository.loadFullText(documentId) }
            .getOrNull().orEmpty()
        if (transcript.isBlank()) {
            return failure("Transcript khaali hai.").also {
                postFailure(GenerationManager.Type.Mcq, documentId, documentTitle, "Transcript khaali hai")
            }
        }

        // Collect previously-generated question texts so the new set
        // doesn't repeat them (fresh-set-on-regenerate contract).
        val previousQuestions = runCatching {
            ServiceLocator.mcqRepository.getAllQuestionsForDocument(documentId)
        }.getOrNull().orEmpty()
        val previousTexts = previousQuestions.map { it.question }

        // Detect whether the transcript has explicitly-numbered
        // questions ("Q.1", "pehla sawaal", "प्रश्न 2", etc.).
        // If yes → Mode A (extract ONLY those questions as MCQs).
        // If no  → Mode B (topic-based: top 10 important MCQs from
        //          full document, excluding previously-generated ones).
        val detection = VideoQuestionDetector.detect(transcript)
        val questionSegments: List<VideoQuestionDetector.QuestionSegment> = when (detection) {
            is VideoQuestionDetector.Result.HasQuestions -> detection.segments
            VideoQuestionDetector.Result.NoQuestions     -> emptyList()
        }

        // Mode A skips eligibility entirely — the detector already
        // confirmed the questions exist. Mode B uses the extraction prompt
        // which has now been updated to GENERATE 10-20 conceptual MCQs
        // if the transcript is pure theory. So we no longer run the
        // eligibility check that used to block theory videos.

        val modeLabel = if (questionSegments.isNotEmpty())
            "Video ke ${questionSegments.size} questions se MCQs bana raha hoon…"
        else "MCQs extract kar raha hoon…"
        setProgress(workDataOf(PROGRESS_MESSAGE to modeLabel))
        runCatching {
            setForeground(buildForegroundInfo(
                GenerationManager.Type.Mcq, documentTitle,
                "$modeLabel (1-2 min)"
            ))
        }

        val result = McqGenerator.generate(
            config = cfg,
            documentId = documentId,
            documentTitle = documentTitle,
            transcript = transcript,
            previousQuestionTexts = previousTexts,
            questionSegments = questionSegments,
        ).getOrElse { e ->
            return failure(e.message ?: "MCQ generation fail").also {
                postFailure(GenerationManager.Type.Mcq, documentId, documentTitle, e.message ?: "MCQ generation fail")
            }
        }
        val quizId = ServiceLocator.mcqRepository.saveQuizWithQuestions(result.quiz, result.questions)

        NotificationHelper.postCompletion(
            application = applicationContext as Application,
            type = GenerationManager.Type.Mcq,
            documentId = documentId,
            title = "MCQ test ready",
            body = "$documentTitle — ${result.questions.size} questions",
        )
        return androidx.work.ListenableWorker.Result.success(workDataOf(
            OUTPUT_DOC_TITLE to documentTitle,
            OUTPUT_RESULT_ID to quizId,
        ))
    }

    /* ---------------- Notes pipeline ---------------- */

    private suspend fun doNotes(documentId: Long, documentTitle: String): androidx.work.ListenableWorker.Result {
        val cfg = ServiceLocator.configRepository.get(AppMode.Generate)
        if (cfg == null || !cfg.isComplete()) {
            return failure("Mode 3 settings configure karein. Settings → Mode 3 mein API key + model name daalein.")
                .also { postFailure(GenerationManager.Type.Notes, documentId, documentTitle, "Mode 3 settings missing") }
        }

        val transcript = runCatching { ServiceLocator.documentRepository.loadFullText(documentId) }
            .getOrNull().orEmpty()
        if (transcript.isBlank()) {
            return failure("Transcript khaali hai.").also {
                postFailure(GenerationManager.Type.Notes, documentId, documentTitle, "Transcript khaali hai")
            }
        }

        // Per-document customizations are read from the cached
        // generated_note row (DB v7+) — persisted via the screen's
        // settings panel BEFORE this worker runs. Custom prompt is
        // honoured EXACTLY (per the user spec); language override
        // wins over auto-detect when set. Both are nullable: null →
        // "use defaults", which is what every freshly-imported doc
        // and every pre-v7 row sees.
        val notesRepo = ServiceLocator.notesRepository
        val existing = notesRepo.get(documentId)
        val customPrompt: String? = existing?.customPrompt?.takeIf { it.isNotBlank() }
        val langOverride: com.reader.app.domain.text.LanguageDetect.Lang? =
            existing?.languageOverride?.let { name ->
                runCatching {
                    com.reader.app.domain.text.LanguageDetect.Lang.valueOf(name)
                }.getOrNull()
            }

        setProgress(workDataOf(PROGRESS_MESSAGE to "Notes generate ho rahe hain (1-2 min)…"))
        runCatching {
            setForeground(buildForegroundInfo(
                GenerationManager.Type.Notes, documentTitle,
                "Notes generate ho rahe hain (1-2 min)…"
            ))
        }

        val html = NotesGenerator.generate(
            config             = cfg,
            title              = documentTitle,
            transcript         = transcript,
            customSystemPrompt = customPrompt,
            languageOverride   = langOverride,
        ).getOrElse { e ->
            return failure(e.message ?: "Notes generation fail").also {
                postFailure(GenerationManager.Type.Notes, documentId, documentTitle, e.message ?: "Notes generation fail")
            }
        }

        // Preserve any view prefs the user chose for this doc — re-
        // generate must not reset their theme / font / margin.
        notesRepo.save(
            documentId = documentId,
            title = documentTitle,
            html = html,
            theme = existing?.theme ?: "light",
            fontScale = existing?.fontScale ?: 1.0,
            margin = existing?.margin ?: "normal",
        )

        NotificationHelper.postCompletion(
            application = applicationContext as Application,
            type = GenerationManager.Type.Notes,
            documentId = documentId,
            title = "PDF notes ready",
            body = "$documentTitle — open Reader to preview & save as PDF",
        )
        return androidx.work.ListenableWorker.Result.success(workDataOf(
            OUTPUT_DOC_TITLE to documentTitle,
            OUTPUT_RESULT_ID to documentId,
        ))
    }

    private suspend fun doPolishNotes(documentId: Long, documentTitle: String): androidx.work.ListenableWorker.Result {
        val cfg = ServiceLocator.configRepository.get(AppMode.Generate)
        if (cfg == null || !cfg.isComplete()) {
            return failure("Mode 3 settings configure karein. Settings → Mode 3 mein API key + model name daalein.")
                .also { postFailure(GenerationManager.Type.PolishNotes, documentId, documentTitle, "Mode 3 settings missing") }
        }

        val notesRepo = ServiceLocator.notesRepository
        val existing = notesRepo.get(documentId)
        val origHtml = existing?.html ?: ""
        if (origHtml.isBlank()) {
            return failure("Polish karne ke liye notes HTML khaali hai.").also {
                postFailure(GenerationManager.Type.PolishNotes, documentId, documentTitle, "Notes content not found")
            }
        }

        setProgress(workDataOf(PROGRESS_MESSAGE to "Notes polish ho rahe hain (1-2 min)…"))
        runCatching {
            setForeground(buildForegroundInfo(
                GenerationManager.Type.PolishNotes, documentTitle,
                "Notes polish ho rahe hain (1-2 min)…"
            ))
        }

        val html = com.reader.app.domain.notes.NotesGenerator.polish(
            config       = cfg,
            title        = documentTitle,
            originalHtml = origHtml,
        ).getOrElse { e ->
            return failure(e.message ?: "Notes polish fail").also {
                postFailure(GenerationManager.Type.PolishNotes, documentId, documentTitle, e.message ?: "Notes polish fail")
            }
        }

        notesRepo.save(
            documentId = documentId,
            title = documentTitle,
            html = html,
            theme = existing?.theme ?: "light",
            fontScale = existing?.fontScale ?: 1.0,
            margin = existing?.margin ?: "normal",
        )

        NotificationHelper.postCompletion(
            application = applicationContext as Application,
            type = GenerationManager.Type.PolishNotes,
            documentId = documentId,
            title = "Polished notes ready",
            body = "Your notes have been refined and visual quality is improved!",
        )
        return androidx.work.ListenableWorker.Result.success(workDataOf(
            OUTPUT_DOC_TITLE to documentTitle,
            OUTPUT_RESULT_ID to documentId,
        ))
    }

    /* ---------------- helpers ---------------- */

    private fun failure(message: String): androidx.work.ListenableWorker.Result =
        androidx.work.ListenableWorker.Result.failure(workDataOf(OUTPUT_ERROR to message))

    private fun postFailure(
        type: GenerationManager.Type,
        documentId: Long,
        documentTitle: String,
        reason: String,
    ) {
        NotificationHelper.postCompletion(
            application = applicationContext as Application,
            type = type,
            documentId = documentId,
            title = when (type) {
                GenerationManager.Type.Mcq         -> "MCQ generation didn't complete"
                GenerationManager.Type.Notes       -> "Notes generation didn't complete"
                GenerationManager.Type.PolishNotes  -> "Notes polishing didn't complete"
            },
            body = "$documentTitle — $reason",
        )
    }

    private fun buildForegroundInfo(
        type: GenerationManager.Type,
        documentTitle: String,
        message: String,
    ): ForegroundInfo {
        val notifTitle = when (type) {
            GenerationManager.Type.Mcq         -> "Generating MCQ test"
            GenerationManager.Type.Notes       -> "Generating PDF notes"
            GenerationManager.Type.PolishNotes  -> "Polishing PDF notes"
        }
        val body = "$documentTitle — $message"
        val notification = NotificationHelper.buildOngoingNotification(
            applicationContext, notifTitle, body
        )
        val notifId = NotificationHelper.ongoingNotificationId(
            type, inputData.getLong(INPUT_DOCUMENT_ID, 0L)
        )
        // Android 14+ requires the type to be specified at the
        // ForegroundInfo level when the manifest declares one. We
        // declare `dataSync` in AndroidManifest.xml + here.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(notifId, notification)
        }
    }
}
