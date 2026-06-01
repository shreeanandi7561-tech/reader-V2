package com.reader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reader.app.data.local.entity.TranscriptCueEntity

@Dao
interface TranscriptCueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TranscriptCueEntity>)

    /** All cues for a doc in playback order. */
    @Query("SELECT * FROM transcript_cue WHERE documentId = :documentId ORDER BY startSec ASC")
    suspend fun getAllForDocument(documentId: Long): List<TranscriptCueEntity>

    /**
     * Cues whose start lies in the half-open window `[fromSec, toSec)`,
     * in playback order. Used by Discussion mode to grab "what was just
     * being said" when the student raises a doubt.
     */
    @Query(
        """
        SELECT * FROM transcript_cue
        WHERE documentId = :documentId
          AND startSec >= :fromSec
          AND startSec < :toSec
        ORDER BY startSec ASC
        """
    )
    suspend fun getInWindow(
        documentId: Long,
        fromSec: Double,
        toSec: Double
    ): List<TranscriptCueEntity>

    @Query("DELETE FROM transcript_cue WHERE documentId = :documentId")
    suspend fun deleteAllForDocument(documentId: Long)

    @Query("SELECT COUNT(*) FROM transcript_cue WHERE documentId = :documentId")
    suspend fun countForDocument(documentId: Long): Int
}
