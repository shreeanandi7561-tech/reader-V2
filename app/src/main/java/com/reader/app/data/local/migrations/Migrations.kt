package com.reader.app.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v4 → v5: YouTube-aware documents.
 *
 *  - `document` gets two nullable columns:
 *      - `youtubeVideoId` — the 11-char YouTube video id, set only on
 *         docs imported from a YouTube URL. Null for paste / file imports
 *         (which keeps their existing Discussion-mode behaviour intact).
 *      - `toneProfile`    — cached 1–2 paragraph teaching-style profile
 *         that the prompt builder injects into every Q&A so the AI's
 *         answer mimics the actual video tutor's tone. Filled by a
 *         one-time LLM call after import; null if extraction failed.
 *
 *  - New `transcript_cue` table holds per-cue captions with timestamps
 *    so Discussion mode can grab "what was just said" at any moment in
 *    the video. Indexed on `(documentId, startSec)` for fast range
 *    scans against the doubt-asking timestamp window.
 *
 * The DB still keeps `fallbackToDestructiveMigration()` as a safety net
 * — but having a real migration here means existing user docs survive
 * the upgrade, which matters because re-uploading a YouTube video that
 * has lost its transcript is genuinely painful (network calls + re-
 * fetch). All values default to null so old rows continue to behave
 * exactly like text docs.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `document` ADD COLUMN `youtubeVideoId` TEXT DEFAULT NULL"
        )
        db.execSQL(
            "ALTER TABLE `document` ADD COLUMN `toneProfile` TEXT DEFAULT NULL"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `transcript_cue` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `documentId` INTEGER NOT NULL,
                `startSec` REAL NOT NULL,
                `durSec` REAL NOT NULL,
                `text` TEXT NOT NULL,
                FOREIGN KEY(`documentId`) REFERENCES `document`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transcript_cue_documentId_startSec` " +
                "ON `transcript_cue` (`documentId`, `startSec`)"
        )
    }
}

/**
 * v5 → v6: Generate section.
 *
 *   - `mcq_quiz`     — one generation per row (a doc can have many).
 *   - `mcq_question` — exactly four options per row, FK'd to a quiz.
 *   - `mcq_attempt`  — one student run; encodes selections in a single
 *                      length-N string (`_`/`0..3` per question) so we
 *                      don't need a TypeConverter and the column is
 *                      cheap to read on every auto-save tick.
 *   - `generated_note` — one row per document (PK is `documentId`),
 *                      stores the LLM-produced HTML + view prefs. Re-
 *                      generating REPLACE-upserts the row.
 *
 * All FKs cascade so deleting a document silently nukes its quizzes,
 * questions, attempts, and notes — matches the cascade story for
 * `transcript_cue` introduced in v5.
 *
 * Schema below is hand-derived from the entity classes; a v7 migration
 * adds-only-additively per Room's strict-schema rules. Old rows in the
 * older tables are untouched.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // mcq_quiz
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mcq_quiz` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `documentId` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `questionCount` INTEGER NOT NULL,
                `timeLimitSeconds` INTEGER NOT NULL,
                `markPerCorrect` REAL NOT NULL,
                `negativeMarkPerWrong` REAL NOT NULL,
                FOREIGN KEY(`documentId`) REFERENCES `document`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_mcq_quiz_documentId` " +
                "ON `mcq_quiz` (`documentId`)"
        )

        // mcq_question
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mcq_question` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `quizId` INTEGER NOT NULL,
                `orderIndex` INTEGER NOT NULL,
                `question` TEXT NOT NULL,
                `optionA` TEXT NOT NULL,
                `optionB` TEXT NOT NULL,
                `optionC` TEXT NOT NULL,
                `optionD` TEXT NOT NULL,
                `correctIndex` INTEGER NOT NULL,
                `confidence` REAL NOT NULL,
                `source` TEXT NOT NULL,
                `originalSnippet` TEXT,
                FOREIGN KEY(`quizId`) REFERENCES `mcq_quiz`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_mcq_question_quizId_orderIndex` " +
                "ON `mcq_question` (`quizId`, `orderIndex`)"
        )

        // mcq_attempt
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mcq_attempt` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `quizId` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `selectedAnswers` TEXT NOT NULL,
                `currentIndex` INTEGER NOT NULL,
                `elapsedSeconds` INTEGER NOT NULL,
                `timeLimitSeconds` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `correctCount` INTEGER NOT NULL,
                `wrongCount` INTEGER NOT NULL,
                `skippedCount` INTEGER NOT NULL,
                `marksObtained` REAL NOT NULL,
                `maxMarks` REAL NOT NULL,
                FOREIGN KEY(`quizId`) REFERENCES `mcq_quiz`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_mcq_attempt_quizId_status` " +
                "ON `mcq_attempt` (`quizId`, `status`)"
        )

        // generated_note (PK = documentId, single row per doc)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `generated_note` (
                `documentId` INTEGER NOT NULL PRIMARY KEY,
                `title` TEXT NOT NULL,
                `html` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `theme` TEXT NOT NULL,
                `fontScale` REAL NOT NULL,
                `margin` TEXT NOT NULL,
                FOREIGN KEY(`documentId`) REFERENCES `document`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_generated_note_documentId` " +
                "ON `generated_note` (`documentId`)"
        )
    }
}

/**
 * v6 → v7: per-document Notes customisation.
 *
 * Adds two nullable TEXT columns to `generated_note`:
 *  - `customPrompt`     — user's custom system prompt for the next
 *    generation. NULL means "use the built-in default prompt". The
 *    user's text is sent to the LLM verbatim with NO additional
 *    wrapping (the spec calls for "exact vaisa hi hone do").
 *  - `languageOverride` — explicit language for the next generation.
 *    Stored as the LanguageDetect enum name (`Hindi` / `Hinglish` /
 *    `English`). NULL means auto-detect from the transcript, which
 *    matches the pre-v7 behaviour for every existing row.
 *
 * Both columns are NULLable with no default — `ALTER TABLE ADD
 * COLUMN` of a nullable type is the cheapest possible migration in
 * SQLite (no data rewrite). Existing notes rows continue to
 * "auto-detect language + use default prompt" as before.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `generated_note` ADD COLUMN `customPrompt` TEXT DEFAULT NULL"
        )
        db.execSQL(
            "ALTER TABLE `generated_note` ADD COLUMN `languageOverride` TEXT DEFAULT NULL"
        )
    }
}


/**
 * v7 → v8: storyboard preview-frame grids for the multimodal frame
 * fallback path.
 *
 * Adds one nullable TEXT column to `document`:
 *   - `storyboardSpec` — YouTube's raw `playerStoryboardSpecRenderer.spec`
 *     string captured at import time. Lets Discussion mode fall back to
 *     `i.ytimg.com/sb/...` preview cells whenever the WebView frame
 *     capture returns mostly-black bitmaps (which happens on Android
 *     API levels / GPU stacks where the video surface isn't blitted
 *     into the WebView's Canvas — a real and common failure mode).
 *
 * `ALTER TABLE ADD COLUMN` of a nullable type is the cheapest possible
 * SQLite migration (no row rewrite). Existing rows keep
 * `storyboardSpec = NULL` and the multimodal frame pipeline behaves
 * exactly as before — WebView capture only, no fallback. Re-importing
 * the same YouTube URL re-captures the spec and unlocks the fallback.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `document` ADD COLUMN `storyboardSpec` TEXT DEFAULT NULL"
        )
    }
}
