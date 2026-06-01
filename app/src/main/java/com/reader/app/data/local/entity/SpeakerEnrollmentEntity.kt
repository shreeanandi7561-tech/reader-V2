package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding the enrolled student's voice template. The
 * [embedding] is a pre-normalised float vector (see [com.reader.app.domain.audio.SpeakerEmbedder]).
 *
 * The PK is a fixed sentinel string so saves always replace.
 */
@Entity(tableName = "speaker_enrollment")
data class SpeakerEnrollmentEntity(
    @PrimaryKey val id: String = DEFAULT_ID,
    val embedding: FloatArray,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_ID = "default"
    }

    // FloatArray equals/hashCode are reference-based by default; spell them out
    // so Room's diffing and tests behave.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerEnrollmentEntity) return false
        return id == other.id &&
            updatedAt == other.updatedAt &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + updatedAt.hashCode()
        r = 31 * r + embedding.contentHashCode()
        return r
    }
}
