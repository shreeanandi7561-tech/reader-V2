package com.reader.app.domain.model

/**
 * BYOK configuration for a single [AppMode]. Persisted in Room with [mode] as PK.
 * The Mode 1 (Reading) and Mode 2 (Discussion) configs are stored as two rows
 * so the user can pick a different provider/model/key for each.
 */
data class ApiConfig(
    val mode: AppMode,
    val provider: LlmProvider,
    val apiKey: String,
    val modelName: String
) {
    fun isComplete(): Boolean =
        apiKey.isNotBlank() && modelName.isNotBlank()
}
