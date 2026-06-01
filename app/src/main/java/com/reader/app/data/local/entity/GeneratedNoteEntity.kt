package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached HTML notes for a document.
 *
 * One row per document (PK is `documentId`, not autogen). Regenerating
 * replaces the row — we don't keep history for v1; the LLM is expensive
 * enough that the student typically only generates once.
 *
 * `html` is the full self-contained document — `<html>…<style>…</style>…<body>…`
 * — produced by [com.reader.app.domain.notes.NotesGenerator] from the
 * transcript. It already embeds the print-CSS so the WebView render
 * matches the PDF render byte-for-byte.
 *
 * View prefs (`fontScale`, `theme`, `margin`) are persisted so the
 * student's last reading style sticks on reopen. They DO NOT change
 * the cached HTML — the WebView injects them as CSS variables on top.
 * That keeps "regenerate" idempotent: re-running the LLM gives a fresh
 * `html` blob without nuking the student's preferred font size.
 */
@Entity(
    tableName = "generated_note",
    foreignKeys = [
        ForeignKey(
            entity        = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns  = ["documentId"],
            onDelete      = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId"], unique = true)]
)
data class GeneratedNoteEntity(
    @PrimaryKey val documentId: Long,
    val title: String,
    val html: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** "light" | "sepia" | "dark" — preview/PDF theme. */
    val theme: String = "light",
    /** 0.85 .. 1.50 — multiplier on base font size. */
    val fontScale: Double = 1.0,
    /** "compact" | "normal" | "wide" — page margin preset. */
    val margin: String = "normal",
    /**
     * User's custom system prompt for the next generation. `null`
     * means "use the built-in default prompt".
     *
     * Persisted per-document so the user's customisations stick
     * across regeneration / app restart. Honoured EXACTLY as the
     * user typed it — no app-side prompt-engineering wrapping —
     * per the spec "jab user Apne hisab se kuchh kar raha hai to
     * completely uske hisab Se Hi hone dijiye".
     *
     * Added in DB v7.
     */
    val customPrompt: String? = null,
    /**
     * User's language override for the next generation. Stored as
     * the [com.reader.app.domain.text.LanguageDetect.Lang] enum
     * name string (`Hindi` / `Hinglish` / `English`). `null` means
     * "auto-detect from the transcript", which is the default and
     * what every notes row created before v7 has. Added in DB v7.
     */
    val languageOverride: String? = null,
)
