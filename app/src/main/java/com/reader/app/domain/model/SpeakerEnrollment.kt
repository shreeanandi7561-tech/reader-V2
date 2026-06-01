package com.reader.app.domain.model

/**
 * Domain wrapper for the enrolled student's voice template. The [embedding] is
 * an L2-normalised float vector produced by
 * [com.reader.app.domain.audio.SpeakerEmbedder].
 */
data class SpeakerEnrollment(
    val embedding: FloatArray,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerEnrollment) return false
        return updatedAt == other.updatedAt && embedding.contentEquals(other.embedding)
    }
    override fun hashCode(): Int = 31 * updatedAt.hashCode() + embedding.contentHashCode()
}
