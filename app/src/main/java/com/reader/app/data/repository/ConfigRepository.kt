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
data class ApiKeyStatus(
    val index: Int,
    val obfuscatedKey: String,
    val successCount: Int,
    val failureCount: Int,
    val isCooldown: Boolean,
    val cooldownRemainingSec: Long,
    val lastError: String
)

class ConfigRepository(
    private val context: Context,
    private val dao: ApiConfigDao // Kept for constructor compat, largely unused
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("api_loadbalancer", Context.MODE_PRIVATE)
    private val _keysFlow = MutableStateFlow<List<String>>(loadKeys())
    private val _statusFlow = MutableStateFlow<List<ApiKeyStatus>>(emptyList())
    val statusFlow: Flow<List<ApiKeyStatus>> = _statusFlow.asStateFlow()

    private var currentKeyIndex = 0

    init {
        updateStatusList()
    }

    fun getKeys(): List<String> = _keysFlow.value

    private fun loadKeys(): List<String> {
        val list = mutableListOf<String>()
        for (i in 1..10) {
            list.add(prefs.getString("key_$i", "") ?: "")
        }
        return list
    }

    private fun obfuscateKey(key: String): String {
        if (key.isBlank()) return "Not configured"
        if (key.length <= 8) return "configured"
        return key.take(6) + "..." + key.takeLast(4)
    }

    private fun updateStatusList() {
        val keys = _keysFlow.value
        val now = System.currentTimeMillis()
        val list = keys.mapIndexed { idx, key ->
            val successes = prefs.getInt("success_$idx", 0)
            val failures = prefs.getInt("failure_$idx", 0)
            val cooldownUntil = prefs.getLong("cooldown_$idx", 0L)
            val lastError = prefs.getString("lasterror_$idx", "") ?: ""
            val isCooldown = now < cooldownUntil
            val remainingSec = if (isCooldown) (cooldownUntil - now) / 1000L else 0L
            
            ApiKeyStatus(
                index = idx,
                obfuscatedKey = obfuscateKey(key),
                successCount = successes,
                failureCount = failures,
                isCooldown = isCooldown,
                cooldownRemainingSec = remainingSec,
                lastError = lastError
            )
        }
        _statusFlow.value = list
    }

    suspend fun saveKeys(keys: List<String>) {
        val currentKeys = _keysFlow.value
        val edit = prefs.edit()
        for (i in 0 until 10) {
            val newKey = keys.getOrElse(i) { "" }.trim()
            edit.putString("key_${i + 1}", newKey)
            
            // If key has changed, reset its status/cooldown
            if (newKey != currentKeys.getOrElse(i) { "" }) {
                edit.putInt("success_$i", 0)
                edit.putInt("failure_$i", 0)
                edit.putLong("cooldown_$i", 0L)
                edit.putString("lasterror_$i", "")
            }
        }
        edit.apply()
        _keysFlow.value = loadKeys()
        updateStatusList()
    }

    /** Returns the next available key dynamically bypassing rate-limited keys on cooldown. */
    private fun getNextKeyConfig(mode: AppMode): ApiConfig? {
        val keys = _keysFlow.value
        val validIndices = keys.indices.filter { keys[it].isNotBlank() }
        if (validIndices.isEmpty()) return null
        
        val now = System.currentTimeMillis()
        var chosenIndex = -1
        
        // Try to pick the next sequential key that is not in cooldown
        for (offset in 0 until validIndices.size) {
            val idx = validIndices[(currentKeyIndex + offset) % validIndices.size]
            val cooldownUntil = prefs.getLong("cooldown_$idx", 0L)
            if (now >= cooldownUntil) {
                chosenIndex = idx
                currentKeyIndex = (currentKeyIndex + offset + 1) % validIndices.size
                break
            }
        }
        
        // Fallback: if all key slots are in cooldown, pick the one with earliest cooldown expiration
        if (chosenIndex == -1) {
            chosenIndex = validIndices.minByOrNull { prefs.getLong("cooldown_$it", 0L) } ?: validIndices[0]
            currentKeyIndex = (validIndices.indexOf(chosenIndex) + 1) % validIndices.size
        }
        
        val key = keys[chosenIndex]
        return ApiConfig(
            mode = mode,
            provider = LlmProvider.Gemini,
            apiKey = key,
            modelName = "gemini-1.5-flash-latest"
        )
    }

    fun recordSuccess(apiKey: String) {
        val keys = _keysFlow.value
        val idx = keys.indexOf(apiKey)
        if (idx == -1) return
        
        val successes = prefs.getInt("success_$idx", 0) + 1
        prefs.edit().putInt("success_$idx", successes).apply()
        
        // Success resets any active cooldown limit
        prefs.edit()
            .putLong("cooldown_$idx", 0L)
            .putString("lasterror_$idx", "")
            .apply()
            
        updateStatusList()
    }

    fun recordFailure(apiKey: String, errorMsg: String, isQuotaExceeded: Boolean) {
        val keys = _keysFlow.value
        val idx = keys.indexOf(apiKey)
        if (idx == -1) return
        
        val failures = prefs.getInt("failure_$idx", 0) + 1
        val edit = prefs.edit().putInt("failure_$idx", failures)
        
        var cleanMsg = errorMsg.trim()
        if (cleanMsg.length > 150) {
            cleanMsg = cleanMsg.take(147) + "..."
        }
        edit.putString("lasterror_$idx", cleanMsg)
        
        if (isQuotaExceeded) {
            // Set cooldown for 10 minutes (600,000 ms) so caller skips this API slot
            val cooldownUntil = System.currentTimeMillis() + 600_000L
            edit.putLong("cooldown_$idx", cooldownUntil)
        }
        edit.apply()
        
        updateStatusList()
    }

    fun resetStats() {
        val edit = prefs.edit()
        for (i in 0 until 10) {
            edit.putInt("success_$i", 0)
            edit.putInt("failure_$i", 0)
            edit.putLong("cooldown_$i", 0L)
            edit.putString("lasterror_$i", "")
        }
        edit.apply()
        updateStatusList()
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
