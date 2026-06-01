package com.reader.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.reader.app.data.local.dao.ApiConfigDao
import com.reader.app.data.local.dao.DocumentChunkDao
import com.reader.app.data.local.dao.DocumentDao
import com.reader.app.data.local.dao.GeneratedNoteDao
import com.reader.app.data.local.dao.McqDao
import com.reader.app.data.local.dao.SpeakerEnrollmentDao
import com.reader.app.data.local.dao.TranscriptCueDao
import com.reader.app.data.local.dao.TtsPreferencesDao
import com.reader.app.data.local.entity.ApiConfigEntity
import com.reader.app.data.local.entity.DocumentChunkEntity
import com.reader.app.data.local.entity.DocumentEntity
import com.reader.app.data.local.entity.GeneratedNoteEntity
import com.reader.app.data.local.entity.McqAttemptEntity
import com.reader.app.data.local.entity.McqQuestionEntity
import com.reader.app.data.local.entity.McqQuizEntity
import com.reader.app.data.local.entity.SpeakerEnrollmentEntity
import com.reader.app.data.local.entity.TranscriptCueEntity
import com.reader.app.data.local.entity.TtsPreferencesEntity
import com.reader.app.data.local.migrations.MIGRATION_4_5
import com.reader.app.data.local.migrations.MIGRATION_5_6
import com.reader.app.data.local.migrations.MIGRATION_6_7
import com.reader.app.data.local.migrations.MIGRATION_7_8

/**
 * Schema:
 *  - v1 introduced ApiConfig + Document + DocumentChunk
 *  - v2 added SpeakerEnrollment + TranscriptionConfig (Whisper BYOK)
 *  - v3 dropped TranscriptionConfig — STT moved to on-device SpeechRecognizer.
 *  - v4 added TtsPreferences (pitch, speech rate, language, voice name).
 *  - v5 added YouTube support: `document.youtubeVideoId`,
 *    `document.toneProfile`, and a new `transcript_cue` table holding
 *    per-cue captions with start / dur timestamps. Existing user docs
 *    keep working unchanged (both new columns are nullable, defaulting
 *    null → "this doc is plain text, render the chat-only layout").
 *  - v6 added the Generate section: `mcq_quiz`, `mcq_question`,
 *    `mcq_attempt` (the MCQ pipeline + saveable attempt state) and
 *    `generated_note` (one cached HTML notes blob per document, plus
 *    persisted view prefs). All cascade-delete on parent doc.
 *  - v7 added `generated_note.customPrompt` and
 *    `generated_note.languageOverride` (both nullable TEXT) so the
 *    user can persist per-document Notes customisations across
 *    regenerate / app restart.
 *  - v8 added `document.storyboardSpec` (nullable TEXT) — YouTube's
 *    public preview-grid descriptor captured at import. Drives the
 *    storyboard fallback inside the multimodal video-frame doubt
 *    pipeline: when the WebView frame capture returns mostly-black
 *    bitmaps (hardware-protected video surface), the Composite source
 *    fetches the matching cell from `i.ytimg.com/sb/...` instead so
 *    the AI still gets visual context. Null on old rows / videos
 *    without a storyboard — those rows just keep using WebView-only
 *    capture, exactly as before.
 */
@Database(
    entities = [
        ApiConfigEntity::class,
        DocumentEntity::class,
        DocumentChunkEntity::class,
        SpeakerEnrollmentEntity::class,
        TtsPreferencesEntity::class,
        TranscriptCueEntity::class,
        McqQuizEntity::class,
        McqQuestionEntity::class,
        McqAttemptEntity::class,
        GeneratedNoteEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun apiConfigDao(): ApiConfigDao
    abstract fun documentDao(): DocumentDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun speakerEnrollmentDao(): SpeakerEnrollmentDao
    abstract fun ttsPreferencesDao(): TtsPreferencesDao
    abstract fun transcriptCueDao(): TranscriptCueDao
    abstract fun mcqDao(): McqDao
    abstract fun generatedNoteDao(): GeneratedNoteDao

    companion object {
        @Volatile private var INSTANCE: ReaderDatabase? = null

        fun get(context: Context): ReaderDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReaderDatabase::class.java,
                    "reader.db"
                )
                    // Real migrations preserve user data across upgrades;
                    // destructive fallback is only a final safety net (e.g.
                    // corrupted DB). Re-fetching a YouTube transcript or re-
                    // running an MCQ generation is genuinely painful, so we
                    // always ship a real Migration object alongside the
                    // entity changes.
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
