package com.reader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reader.app.data.local.entity.DocumentChunkEntity

@Dao
interface DocumentChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<DocumentChunkEntity>)

    @Query("SELECT * FROM document_chunk WHERE documentId = :documentId ORDER BY orderIndex ASC")
    suspend fun getAllForDocument(documentId: Long): List<DocumentChunkEntity>

    /** Returns the last N chunks ending at [endIndexInclusive], ascending. */
    @Query(
        """
        SELECT * FROM document_chunk
        WHERE documentId = :documentId
          AND orderIndex <= :endIndexInclusive
        ORDER BY orderIndex DESC
        LIMIT :n
        """
    )
    suspend fun getRecent(documentId: Long, endIndexInclusive: Int, n: Int): List<DocumentChunkEntity>

    @Query("DELETE FROM document_chunk WHERE documentId = :documentId")
    suspend fun deleteAllForDocument(documentId: Long)
}
