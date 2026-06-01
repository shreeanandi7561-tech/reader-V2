package com.reader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reader.app.data.local.entity.GeneratedNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeneratedNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: GeneratedNoteEntity)

    @Query("SELECT * FROM generated_note WHERE documentId = :documentId")
    suspend fun get(documentId: Long): GeneratedNoteEntity?

    @Query("SELECT * FROM generated_note WHERE documentId = :documentId")
    fun observe(documentId: Long): Flow<GeneratedNoteEntity?>

    @Query("UPDATE generated_note SET theme = :theme, fontScale = :fontScale, margin = :margin, updatedAt = :updatedAt WHERE documentId = :documentId")
    suspend fun updatePrefs(documentId: Long, theme: String, fontScale: Double, margin: String, updatedAt: Long)

    /**
     * Insert a stub row for [documentId] if none exists. Used by
     * [com.reader.app.data.repository.NotesRepository.saveCustomization]
     * so the user's `customPrompt` / `languageOverride` choices can
     * be persisted BEFORE the first generation completes (without
     * clobbering an existing row's html if one already exists).
     *
     * Stub rows have empty `html` — the screen / view-model treats
     * those as "no notes yet" (same as a missing row) and the auto-
     * generate-on-open path will run as expected.
     */
    @Query(
        """
        INSERT OR IGNORE INTO generated_note
            (documentId, title, html, createdAt, updatedAt, theme, fontScale, margin, customPrompt, languageOverride)
        VALUES
            (:documentId, :title, '', :now, :now, 'light', 1.0, 'normal', NULL, NULL)
        """
    )
    suspend fun insertStubIfMissing(documentId: Long, title: String, now: Long)

    @Query(
        "UPDATE generated_note SET customPrompt = :customPrompt, " +
            "languageOverride = :languageOverride, updatedAt = :updatedAt " +
            "WHERE documentId = :documentId"
    )
    suspend fun updateCustomization(
        documentId: Long,
        customPrompt: String?,
        languageOverride: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM generated_note WHERE documentId = :documentId")
    suspend fun delete(documentId: Long)
}
