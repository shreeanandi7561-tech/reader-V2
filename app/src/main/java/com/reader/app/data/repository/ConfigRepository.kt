package com.reader.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.reader.app.data.local.dao.ApiConfigDao
import com.reader.app.domain.model.ApiConfig
import com.reader.app.domain.model.AppMode
import com.reader.app.domain.model.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Global Load-Balanced API Configuration.
 * Replaces the previous mode-specific provider settings.
 * Uses Round-Robin to cycle through up to 10 stored Gemini keys.
 */
class ConfigRepository(
    private val context: Context,
    private val dao: ApiConfigDao // Kept for constructor compat, largely unused
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("api_loadbalancer", Context.MODE_PRIVATE)
    private val _keysFlow = MutableStateFlow<List<String>>(loadKeys())

    private var currentKeyIndex = 0

    fun getKeys(): List<String> = _keysFlow.value

    private fun loadKeys(): List<String> {
        val list = mutableListOf<String>()
        for (i in 1..10) {
            list.add(prefs.getString("key_$i", "") ?: "")
        }
        return list
    }

    suspend fun saveKeys(keys: List<String>) {
        val edit = prefs.edit()
        for (i in 0 until 10) {
            edit.putString("key_${i + 1}", keys.getOrElse(i) { "" }.trim())
        }
        edit.apply()
        _keysFlow.value = loadKeys()
    }

    /** Returns the next available key in standard round-robin fashion. */
    private fun getNextKeyConfig(mode: AppMode): ApiConfig? {
        val validKeys = _keysFlow.value.filter { it.isNotBlank() }
        if (validKeys.isEmpty()) return null
        
        val key = validKeys[currentKeyIndex % validKeys.size]
        currentKeyIndex++ // Auto-advance for load-balancing exactly as requested
        return ApiConfig(
            mode = mode,
            provider = LlmProvider.Gemini,
            apiKey = key,
            modelName = "gemini-1.5-flash-latest" // Hardcoded as requested
        )
    }

    /** Call this when limit quota is nearing or 429/403 is caught, or blindly on each request. */
    fun advanceKey() {
        currentKeyIndex++
    }

    // --- Backwards Compatibility with AppMode calls ---
    
    fun observeAll(): Flow<Map<AppMode, ApiConfig>> = _keysFlow.map { keys ->
        val validKeys = keys.filter { it.isNotBlank() }
        val fallbackKey = validKeys.firstOrNull() ?: ""
        AppMode.entries.associateWith { mode ->
            ApiConfig(
                mode = mode,
                provider = LlmProvider.Gemini,
                apiKey = fallbackKey,
                modelName = "gemini-1.5-flash-latest"
            )
        }
    }

    suspend fun get(mode: AppMode): ApiConfig? {
        return getNextKeyConfig(mode)
    }

    suspend fun save(config: ApiConfig) {
        // Obsolete in new global-keys model, but keeping signature so old callers don't crash
    }
}
