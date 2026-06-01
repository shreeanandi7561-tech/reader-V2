package com.reader.app.data.repository

import com.reader.app.data.local.dao.SpeakerEnrollmentDao
import com.reader.app.data.local.entity.SpeakerEnrollmentEntity
import com.reader.app.domain.model.SpeakerEnrollment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists the enrolled speaker template (single row keyed by a sentinel id).
 */
class SpeakerEnrollmentRepository(private val dao: SpeakerEnrollmentDao) {

    fun observe(): Flow<SpeakerEnrollment?> =
        dao.observe().map { it?.toDomain() }

    suspend fun get(): SpeakerEnrollment? = dao.get()?.toDomain()

    suspend fun save(embedding: FloatArray) {
        dao.upsert(
            SpeakerEnrollmentEntity(
                embedding = embedding,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clear() = dao.delete()

    private fun SpeakerEnrollmentEntity.toDomain() =
        SpeakerEnrollment(embedding = embedding, updatedAt = updatedAt)
}
