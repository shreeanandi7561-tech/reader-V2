package com.reader.app.data.remote.dto

import com.squareup.moshi.JsonClass

/* ---------- OpenAI-compatible (Groq + Nvidia NIM) ---------- */

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val role: String,           // "system" | "user" | "assistant"
    val content: String
)

@JsonClass(generateAdapter = true)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val top_p: Double = 0.8,
    val max_tokens: Int = 4096,
    val stream: Boolean = false,
    /**
     * OpenAI-compatible JSON mode. Set to `ResponseFormat("json_object")`
     * to force the model to emit valid JSON instead of free-form text.
     * Critical for the Generate section's MCQ extractor, where small
     * / quantised models otherwise wrap their JSON in prose despite
     * the system-prompt instructions.
     *
     * Null (default) for normal chat — the field is omitted from the
     * wire JSON in that case (Moshi codegen skips nulls), so providers
     * that don't recognise `response_format` are unaffected.
     */
    val response_format: ResponseFormat? = null,
)

/**
 * `{"type": "json_object"}` — the only value Groq + most OpenAI-compat
 * providers currently accept. Wrapped in a typed object so callers
 * can't accidentally pass arbitrary strings.
 */
@JsonClass(generateAdapter = true)
data class ResponseFormat(val type: String)

@JsonClass(generateAdapter = true)
data class ChatCompletionChoice(
    val index: Int?,
    val message: ChatMessage?,
    val finish_reason: String?
)

@JsonClass(generateAdapter = true)
data class ChatCompletionResponse(
    val id: String?,
    val model: String?,
    val choices: List<ChatCompletionChoice> = emptyList()
) {
    fun firstText(): String? = choices.firstOrNull()?.message?.content
}
