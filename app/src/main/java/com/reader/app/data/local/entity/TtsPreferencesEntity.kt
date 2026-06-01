package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding the user's TTS customisation:
 * language, pitch, speech rate, and selected voice.
 *
 * Pitch and rate are clamped to [0.5, 2.0] by [TtsController].
 */
@Entity(tableName = "tts_preferences")
data class TtsPreferencesEntity(
    @PrimaryKey val id: String = DEFAULT_ID,
    val languageTag: String = "hi-IN",
    val pitch: Float = 1.0f,
    val speechRate: Float = 1.0f,
    val voiceName: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object { const val DEFAULT_ID = "default" }
}
