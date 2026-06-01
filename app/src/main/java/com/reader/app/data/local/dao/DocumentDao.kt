package com.reader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reader.app.data.local.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM document ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM document WHERE id = :id")
    suspend fun get(id: Long): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: DocumentEntity): Long

    @Query("UPDATE document SET lastIndex = :index WHERE id = :id")
    suspend fun updateLastIndex(id: Long, index: Int)

    @Query("UPDATE document SET totalChunks = :total WHERE id = :id")
    suspend fun updateTotalChunks(id: Long, total: Int)

    @Query("UPDATE document SET toneProfile = :profile WHERE id = :id")
    suspend fun updateToneProfile(id: Long, profile: String?)

    @Query("UPDATE document SET storyboardSpec = :spec WHERE id = :id")
    suspend fun updateStoryboardSpec(id: Long, spec: String?)

    @Delete
    suspend fun delete(doc: DocumentEntity)
}
