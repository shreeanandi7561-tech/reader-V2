package com.reader.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/* ---------- OpenAI-compatible vision (Groq + Nvidia NIM) ---------- *
 *
 * The OpenAI-compatible chat-completions endpoint accepts EITHER
 *   { "role": "...", "content": "..." }              ← existing [ChatMessage]
 * OR
 *   { "role": "...", "content": [{"type": "text"|"image_url", ...}] }   ← these DTOs
 *
 * Both Groq and Nvidia NIM follow this shape because they aim for
 * full OpenAI-API parity. Vision-capable models on these providers —
 * `llama-3.2-11b-vision-preview` / `-90b-vision-preview` on Groq,
 * `meta/llama-3.2-11b-vision-instruct` / `-90b-vision-instruct` on
 * NIM, plus the LLaMA-4 Scout / Maverick families on either — accept
 * this multimodal request body. Text-only models on the SAME
 * providers will reject it with a 400 invalid_request_error: the
 * caller therefore pre-filters via [com.reader.app.domain.model.LlmProvider.supportsImageContent]
 * so this code path only fires for model names that actually accept
 * images.
 *
 * We keep these DTOs as separate classes (rather than making
 * [ChatMessage.content] polymorphic) on purpose — every existing
 * text-only call site continues to send the simpler string-content
 * shape with zero risk of accidental wire-format drift, while the
 * vision call path is opt-in and clearly tagged.
 */

/**
 * `image_url` block of a content part. The OpenAI spec accepts either
 * a public HTTPS URL or a `data:image/...;base64,...` data-URL — we
 * always send data-URLs so no upstream HTTP hosting is required and
 * the image stays inline with the request body.
 */
@JsonClass(generateAdapter = true)
data class ChatVisionImageUrl(
    val url: String
)

/**
 * One element of a multimodal `content` array. Either a text part
 * (`type = "text"`, [text] populated, [imageUrl] null) or an image
 * part (`type = "image_url"`, [imageUrl] populated, [text] null).
 *
 * Use the [text] / [image] factory methods to construct — they pin
 * the right `type` literal so wire-format mismatches can't leak
 * through.
 */
@JsonClass(generateAdapter = true)
data class ChatVisionContentPart(
    /** `"text"` or `"image_url"` — the literal strings the OpenAI spec defines. */
    val type: String,
    /** Populated when [type] == `"text"`. Null otherwise. */
    val text: String? = null,
    /** Populated when [type] == `"image_url"`. Null otherwise. */
    @Json(name = "image_url") val imageUrl: ChatVisionImageUrl? = null
) {
    companion object {
        /** A text content part. */
        fun text(value: String): ChatVisionContentPart =
            ChatVisionContentPart(type = "text", text = value)

        /**
         * An image content part wrapping a `data:<mime>;base64,...` URL.
         * Use [image] when you have raw bytes; the call sites in
         * `LlmRepository.askMultimodal` already construct the data URL.
         */
        fun image(dataUrl: String): ChatVisionContentPart =
            ChatVisionContentPart(
                type     = "image_url",
                imageUrl = ChatVisionImageUrl(url = dataUrl)
            )
    }
}

/**
 * One message of a multimodal chat-completion request.
 *
 * Unlike [ChatMessage] (string content), [content] is always an array
 * of parts. We ALSO use the array form for the system message, not
 * just the user message, so the request shape is uniform — most
 * OpenAI-compat providers accept array-form system messages and
 * sticking to one shape avoids a "system message rejected, user
 * message accepted" failure mode.
 */
@JsonClass(generateAdapter = true)
data class ChatVisionMessage(
    val role: String,
    val content: List<ChatVisionContentPart>
)

/**
 * Multimodal chat-completions request. Mirrors [ChatCompletionRequest]
 * field-for-field so the response (which is the same `choices[].message`
 * shape regardless of input modality) parses through the existing
 * [ChatCompletionResponse] DTO.
 *
 * `stream = false` is hard-set: the multimodal path in this app is
 * non-streaming on purpose, matching the existing Gemini multimodal
 * UX. The "thinking" pill stays visible until the full answer is
 * back. SSE parsing for vision-deltas would add a separate code path
 * with no UX gain for a single-shot doubt reply — re-evaluate later
 * if doubt answers start growing past ~1.5K tokens.
 */
@JsonClass(generateAdapter = true)
data class ChatVisionRequest(
    val model: String,
    val messages: List<ChatVisionMessage>,
    val temperature: Double = 0.7,
    val top_p: Double = 0.8,
    val max_tokens: Int = 4096,
    val stream: Boolean = false,
)
