package com.reader.app.data.repository

import com.reader.app.data.local.dao.ApiConfigDao
import com.reader.app.data.local.entity.ApiConfigEntity
import com.reader.app.domain.model.ApiConfig
import com.reader.app.domain.model.AppMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reads/writes BYOK configuration. Each [AppMode] has at most one row.
 */
class ConfigRepository(private val dao: ApiConfigDao) {

    fun observeAll(): Flow<Map<AppMode, ApiConfig>> =
        dao.observeAll().map { list ->
            list.associate { entity ->
                val domain = entity.toDomain()
                domain.mode to domain
            }
        }

    suspend fun get(mode: AppMode): ApiConfig? =
        dao.get(mode.name)?.toDomain()

    suspend fun save(config: ApiConfig) {
        dao.upsert(ApiConfigEntity.fromDomain(config))
    }
}
