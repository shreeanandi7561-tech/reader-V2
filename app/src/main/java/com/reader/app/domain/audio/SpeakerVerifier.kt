package com.reader.app.domain.audio

/**
 * Cosine-similarity gate between an enrolled embedding and a candidate.
 *
 * Both vectors are expected to be L2-normalised by [SpeakerEmbedder], so the
 * cosine reduces to a dot product. The default [DEFAULT_THRESHOLD] is set
 * deliberately permissive for a 24-dim log-mel-mean embedding; tighten it
 * once we have user-side calibration data.
 */
object SpeakerVerifier {

    /** Empirical default for the 24-dim log-mel-mean embedding. */
    const val DEFAULT_THRESHOLD = 0.85f

    data class Result(val similarity: Float, val accepted: Boolean)

    fun verify(
        enrolled: FloatArray,
        candidate: FloatArray,
        threshold: Float = DEFAULT_THRESHOLD
    ): Result {
        require(enrolled.size == candidate.size) {
            "embedding length mismatch (${enrolled.size} vs ${candidate.size})"
        }
        var dot = 0f
        for (i in enrolled.indices) dot += enrolled[i] * candidate[i]
        // Already unit-norm, but clamp for numerical safety.
        val sim = dot.coerceIn(-1f, 1f)
        return Result(similarity = sim, accepted = sim >= threshold)
    }
}
