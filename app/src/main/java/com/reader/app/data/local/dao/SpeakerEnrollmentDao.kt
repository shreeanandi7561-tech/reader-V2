package com.reader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reader.app.data.local.entity.SpeakerEnrollmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeakerEnrollmentDao {

    @Query("SELECT * FROM speaker_enrollment WHERE id = :id LIMIT 1")
    suspend fun get(id: String = SpeakerEnrollmentEntity.DEFAULT_ID): SpeakerEnrollmentEntity?

    @Query("SELECT * FROM speaker_enrollment WHERE id = :id LIMIT 1")
    fun observe(id: String = SpeakerEnrollmentEntity.DEFAULT_ID): Flow<SpeakerEnrollmentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SpeakerEnrollmentEntity)

    @Query("DELETE FROM speaker_enrollment WHERE id = :id")
    suspend fun delete(id: String = SpeakerEnrollmentEntity.DEFAULT_ID)
}
