package com.reader.app.data.repository

import com.reader.app.data.local.dao.TtsPreferencesDao
import com.reader.app.data.local.entity.TtsPreferencesEntity
import kotlinx.coroutines.flow.Flow

class TtsPreferencesRepository(private val dao: TtsPreferencesDao) {

    fun observe(): Flow<TtsPreferencesEntity?> = dao.observe()

    suspend fun get(): TtsPreferencesEntity = dao.get() ?: TtsPreferencesEntity()

    suspend fun save(entity: TtsPreferencesEntity) {
        dao.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun update(transform: (TtsPreferencesEntity) -> TtsPreferencesEntity) {
        save(transform(get()))
    }
}
