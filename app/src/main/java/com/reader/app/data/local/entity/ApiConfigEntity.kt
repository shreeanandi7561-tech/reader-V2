package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.reader.app.domain.model.ApiConfig
import com.reader.app.domain.model.AppMode
import com.reader.app.domain.model.LlmProvider

/**
 * One row per [AppMode]. PK is the mode name so upserts replace the existing row.
 */
@Entity(tableName = "api_config")
data class ApiConfigEntity(
    @PrimaryKey val mode: String,
    val provider: String,
    val apiKey: String,
    val modelName: String
) {
    fun toDomain(): ApiConfig = ApiConfig(
        mode = AppMode.valueOf(mode),
        provider = LlmProvider.fromName(provider) ?: LlmProvider.Groq,
        apiKey = apiKey,
        modelName = modelName
    )

    companion object {
        fun fromDomain(c: ApiConfig) = ApiConfigEntity(
            mode = c.mode.name,
            provider = c.provider.name,
            apiKey = c.apiKey,
            modelName = c.modelName
        )
    }
}
