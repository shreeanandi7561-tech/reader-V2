package com.reader.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/* ---------- Gemini (generativelanguage.googleapis.com v1beta) ---------- */

/**
 * One inline image attached to a [GeminiPart] for multimodal calls.
 *
 * The Gemini REST shape is:
 * ```
 * { "inline_data": { "mime_type": "image/jpeg", "data": "<base64>" } }
 * ```
 *
 * `data` is the raw base64 (no `data:image/...;base64,` prefix), exactly
 * as Google's docs require. Typical mime types are `image/jpeg`,
 * `image/png` and `image/webp`.
 */
@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    @Json(name = "mime_type") val mimeType: String,
    val data: String
)

/**
 * One element of a Gemini message's `parts` array.
 *
 * Either [text] OR [inlineData] is populated for any given part — Moshi
 * codegen emits whichever is non-null and skips the other (because both
 * fields default to null and the codegen `serializeNull = false`
 * default drops null fields from the wire JSON). This lets the same
 * DTO carry plain text turns (the existing flow) and image-attached
 * turns (the new multimodal flow) without a sealed-class hierarchy /
 * custom adapter, keeping the diff small.
 *
 * Constructors:
 *  - `GeminiPart(text = "...")` — text-only, the original shape.
 *  - `GeminiPart(inlineData = GeminiInlineData(...))` — image-only.
 *
 * The single-arg `GeminiPart("...")` form still works because [text]
 * is the first parameter and [inlineData] defaults to null.
 */
@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String? = null,
    @Json(name = "inline_data") val inlineData: GeminiInlineData? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val role: String? = null,        // "user" | "model"
    val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiSystemInstruction(val parts: List<GeminiPart>)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    val temperature: Double = 0.7,
    /**
     * Cap on Gemini output tokens. Default null → Gemini uses its
     * model-specific default (typically 8K for Pro, 2K for Flash).
     * Generate-section callers pass an explicit value (16384 for HTML
     * notes, 8192 for MCQ extraction) so structured output isn't
     * truncated mid-document.
     */
    val maxOutputTokens: Int? = null,
    /**
     * Forces the model to emit JSON. Set to `"application/json"` for
     * the Generate section's MCQ extractor; null (default) for normal
     * Gemini chat.
     */
    val responseMimeType: String? = null,
)

@JsonClass(generateAdapter = true)
data class GeminiGenerateRequest(
    val systemInstruction: GeminiSystemInstruction?,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig()
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent?,
    val finishReason: String?
)

@JsonClass(generateAdapter = true)
data class GeminiGenerateResponse(
    val candidates: List<GeminiCandidate> = emptyList()
) {
    /**
     * Concatenate the text from every text-typed part of the first
     * candidate. Image parts (which Gemini never returns from a chat
     * call today, but might in the future) are skipped — `it.text` is
     * null for those, and we coerce to empty string before joining.
     */
    fun firstText(): String? =
        candidates.firstOrNull()?.content?.parts?.joinToString(separator = "") {
            it.text.orEmpty()
        }
}
