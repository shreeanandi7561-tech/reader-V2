package com.reader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reader.app.data.local.entity.TtsPreferencesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TtsPreferencesDao {

    @Query("SELECT * FROM tts_preferences WHERE id = :id LIMIT 1")
    suspend fun get(id: String = TtsPreferencesEntity.DEFAULT_ID): TtsPreferencesEntity?

    @Query("SELECT * FROM tts_preferences WHERE id = :id LIMIT 1")
    fun observe(id: String = TtsPreferencesEntity.DEFAULT_ID): Flow<TtsPreferencesEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TtsPreferencesEntity)
}
