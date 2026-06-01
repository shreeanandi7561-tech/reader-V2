package com.reader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reader.app.data.local.entity.ApiConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiConfigDao {

    @Query("SELECT * FROM api_config WHERE mode = :mode LIMIT 1")
    suspend fun get(mode: String): ApiConfigEntity?

    @Query("SELECT * FROM api_config")
    fun observeAll(): Flow<List<ApiConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ApiConfigEntity)
}
