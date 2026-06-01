package com.reader.app.data.repository

import com.reader.app.data.local.dao.GeneratedNoteDao
import com.reader.app.data.local.entity.GeneratedNoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for cached HTML notes per document.
 *
 * REPLACE-upsert is intentional — regenerating notes is the user-facing
 * way to recover from a bad first cut, and we don't keep history. The
 * student's view prefs (`theme`, `fontScale`, `margin`) survive a
 * regeneration because [updatePrefs] writes them in-place without
 * touching the HTML blob; same for the user's per-doc customisations
 * (`customPrompt`, `languageOverride`) which are preserved across
 * `save()` so a regenerate doesn't reset them.
 */
class NotesRepository(private val dao: GeneratedNoteDao) {

    suspend fun get(documentId: Long): GeneratedNoteEntity? = dao.get(documentId)

    fun observe(documentId: Long): Flow<GeneratedNoteEntity?> = dao.observe(documentId)

    suspend fun save(
        documentId: Long,
        title: String,
        html: String,
        theme: String,
        fontScale: Double,
        margin: String,
    ): GeneratedNoteEntity {
        val now = System.currentTimeMillis()
        val existing = dao.get(documentId)
        // Customizations carry across regeneration — they're a
        // user-set knob, not derived from the LLM output, so a fresh
        // `save()` must NOT clobber them.
        val row = GeneratedNoteEntity(
            documentId = documentId,
            title      = title,
            html       = html,
            createdAt  = existing?.createdAt ?: now,
            updatedAt  = now,
            theme      = theme,
            fontScale  = fontScale,
            margin     = margin,
            customPrompt     = existing?.customPrompt,
            languageOverride = existing?.languageOverride,
        )
        dao.upsert(row)
        return row
    }

    suspend fun updatePrefs(
        documentId: Long,
        theme: String,
        fontScale: Double,
        margin: String,
    ) {
        dao.updatePrefs(documentId, theme, fontScale, margin, System.currentTimeMillis())
    }

    /**
     * Persist the user's per-doc customisation choices.
     *
     * Creates a stub row (empty html) if no row exists yet — this
     * lets the user customise BEFORE the first generation completes,
     * and survives process death. The view-model + screen treat a
     * row with blank html the same as a missing row (i.e. "no notes
     * yet, auto-generate") so the stub doesn't show up as broken
     * cached HTML.
     *
     * If a real row already exists, only the customisation columns
     * are touched — html / theme / font / margin are preserved.
     *
     * Pass `null` for either parameter to clear that customisation
     * back to "use default".
     */
    suspend fun saveCustomization(
        documentId: Long,
        title: String,
        customPrompt: String?,
        languageOverride: String?,
    ) {
        val now = System.currentTimeMillis()
        dao.insertStubIfMissing(documentId, title, now)
        dao.updateCustomization(documentId, customPrompt, languageOverride, now)
    }
}
