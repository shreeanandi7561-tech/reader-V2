package com.reader.app.data.remote

import com.reader.app.data.remote.dto.ChatCompletionRequest
import com.reader.app.data.remote.dto.ChatCompletionResponse
import com.reader.app.data.remote.dto.ChatVisionRequest
import com.reader.app.data.remote.dto.GeminiGenerateRequest
import com.reader.app.data.remote.dto.GeminiGenerateResponse
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** OpenAI-compatible chat completions (used by Groq + Nvidia NIM). */
interface OpenAiCompatApi {
    @POST("v1/chat/completions")
    suspend fun chat(@Body body: ChatCompletionRequest): ChatCompletionResponse

    /**
     * Multimodal variant of [chat] — same endpoint, same response
     * shape (a `ChatCompletionResponse`), but the request body uses
     * the array-of-parts `content` form so we can attach images.
     *
     * Hits the SAME `v1/chat/completions` URL — both Groq and Nvidia
     * NIM dispatch on the body shape, not the path. Vision-capable
     * models on those providers (`*-vision-preview`,
     * `*-vision-instruct`, the LLaMA-4 Scout / Maverick families)
     * accept this body; text-only models on the same providers will
     * reject it with `400 invalid_request_error`. The caller
     * pre-filters via
     * [com.reader.app.domain.model.LlmProvider.supportsImageContent]
     * so this method only fires for model names that actually
     * support vision.
     */
    @POST("v1/chat/completions")
    suspend fun chatVision(@Body body: ChatVisionRequest): ChatCompletionResponse
}

/** Google Gemini REST endpoint. The API key is passed as a query param. */
interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generate(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body body: GeminiGenerateRequest
    ): GeminiGenerateResponse
}
