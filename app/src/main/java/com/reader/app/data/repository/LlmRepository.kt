package com.reader.app.data.repository

import com.reader.app.data.remote.LlmClientFactory
import com.reader.app.data.remote.StreamingLlmClient
import com.reader.app.data.remote.dto.ChatCompletionRequest
import com.reader.app.data.remote.dto.ChatMessage
import com.reader.app.data.remote.dto.ChatVisionContentPart
import com.reader.app.data.remote.dto.ChatVisionMessage
import com.reader.app.data.remote.dto.ChatVisionRequest
import com.reader.app.data.remote.dto.GeminiContent
import com.reader.app.data.remote.dto.GeminiGenerateRequest
import com.reader.app.data.remote.dto.GeminiGenerationConfig
import com.reader.app.data.remote.dto.GeminiInlineData
import com.reader.app.data.remote.dto.GeminiPart
import com.reader.app.data.remote.dto.GeminiSystemInstruction
import com.reader.app.data.remote.dto.ResponseFormat
import com.reader.app.domain.model.ApiConfig
import com.reader.app.domain.model.ImageData
import com.reader.app.domain.model.LlmProvider
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import com.reader.app.di.ServiceLocator

/**
 * Hides provider differences. Caller passes a system + user prompt and gets
 * back a plain string response, regardless of whether the active config is
 * Groq, Nvidia NIM, or Gemini.
 *
 * Errors include the provider, status code, model name, and the response
 * body's first 300 chars - almost every "404" the user will hit is from a
 * mistyped model name (e.g. `llama-3-70b` vs `llama-3.1-70b-versatile`), and
 * surfacing the body makes that obvious.
 */
class LlmRepository {

    /**
     * Send a single non-streaming chat completion.
     *
     * @param jsonMode when `true`, asks the provider to constrain the
     *   response to a valid JSON object via the provider-specific
     *   knob (`response_format` for OpenAI-compatible, `responseMimeType`
     *   for Gemini). Used by the Generate section's MCQ extractor; the
     *   default `false` matches every chat-style call site.
     */
    /**
     * Send a single non-streaming chat completion.
     *
     * @param maxTokens upper bound on output length. Default 4096
     *   matches the previous hard-coded value; bump to 8192-16384 for
     *   structured-HTML / long-JSON outputs (Generate section).
     * @param temperature 0.0 (deterministic) … 1.0 (creative). Lower
     *   for verbatim-extraction tasks (MCQ uses 0.2), default 0.7 for
     *   chat.
     * @param jsonMode when `true`, asks the provider to constrain the
     *   response to a valid JSON object via the provider-specific
     *   knob (`response_format` for OpenAI-compatible, `responseMimeType`
     *   for Gemini).
     */
    private fun isQuotaExceededError(throwable: Throwable): Boolean {
        val msg = (throwable.message ?: "").lowercase()
        val isQuota = msg.contains("quota") ||
                msg.contains("rate limit") ||
                msg.contains("exceeded") ||
                msg.contains("429") ||
                msg.contains("exhausted")
        if (throwable is HttpException) {
            if (throwable.code() == 429) return true
        }
        val cause = throwable.cause
        if (cause != null && cause != throwable) {
            return isQuota || isQuotaExceededError(cause)
        }
        return isQuota
    }

    suspend fun ask(
        config: ApiConfig,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 4096,
        temperature: Double = 0.7,
        jsonMode: Boolean = false
    ): Result<String> {
        var currentConfig = config
        var lastErr: Throwable? = null
        val keysCount = maxOf(1, ServiceLocator.configRepository.getKeys().filter { it.isNotBlank() }.size)
        val maxAttempts = minOf(10, keysCount)

        for (attempt in 1..maxAttempts) {
            val result = executeAsk(currentConfig, systemPrompt, userPrompt, maxTokens, temperature, jsonMode)
            if (result.isSuccess) {
                ServiceLocator.configRepository.recordSuccess(currentConfig.apiKey)
                return result
            }

            val exception = result.exceptionOrNull() ?: RuntimeException("Unknown error")
            lastErr = exception

            val isQuota = isQuotaExceededError(exception)
            ServiceLocator.configRepository.recordFailure(currentConfig.apiKey, exception.message.orEmpty(), isQuota)

            if (isQuota && attempt < maxAttempts) {
                val nextConfig = ServiceLocator.configRepository.get(currentConfig.mode)
                if (nextConfig != null && nextConfig.apiKey != currentConfig.apiKey) {
                    currentConfig = nextConfig
                    continue
                }
            }
            break
        }
        return Result.failure(lastErr ?: RuntimeException("Request failed across all retries"))
    }

    private suspend fun executeAsk(
        config: ApiConfig,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 4096,
        temperature: Double = 0.7,
        jsonMode: Boolean = false
    ): Result<String> = runCatching {
        try {
            when (config.provider) {
                LlmProvider.Groq, LlmProvider.NvidiaNim -> {
                    val api = LlmClientFactory.openAiCompat(config)
                    val res = api.chat(
                        ChatCompletionRequest(
                            model    = config.modelName,
                            messages = listOf(
                                ChatMessage("system", systemPrompt),
                                ChatMessage("user", userPrompt)
                            ),
                            temperature = temperature,
                            max_tokens = maxTokens,
                            response_format = if (jsonMode) ResponseFormat("json_object") else null,
                        )
                    )
                    res.firstText().orEmpty()
                }
                LlmProvider.Gemini -> {
                    val api = LlmClientFactory.gemini(config)
                    val res = api.generate(
                        model  = config.modelName,
                        apiKey = config.apiKey,
                        body   = GeminiGenerateRequest(
                            systemInstruction = GeminiSystemInstruction(
                                parts = listOf(GeminiPart(systemPrompt))
                            ),
                            contents = listOf(
                                GeminiContent(
                                    role  = "user",
                                    parts = listOf(GeminiPart(userPrompt))
                                )
                            ),
                            generationConfig = GeminiGenerationConfig(
                                temperature = temperature,
                                maxOutputTokens = maxTokens,
                                responseMimeType = if (jsonMode) "application/json" else null,
                            ),
                        )
                    )
                    res.firstText().orEmpty()
                }
            }
        } catch (e: HttpException) {
            throw RuntimeException(formatHttpError(config, e), e)
        }
    }

    /**
     * Multimodal non-streaming chat completion: text prompt + a list of
     * images attached as inline parts.
     *
     * **Gemini-only today.** Gemini's `:generateContent` endpoint
     * natively accepts images via `parts: [{inline_data: {...}}]`. The
     * OpenAI-compatible providers (Groq + Nvidia NIM) shipped in this
     * app either do not accept images at all or use a different DTO
     * shape (`content: [{type:"image_url", ...}]`) that this codebase's
     * [ChatMessage] DTO can't represent — see [ChatVisionRequest] and
     * the dedicated [OpenAiCompatApi.chatVision] method, which now
     * carry the array-of-parts shape on the same `v1/chat/completions`
     * URL. Vision-capable models on Groq + NIM (`*-vision-preview`,
     * `*-vision-instruct`, the LLaMA-4 Scout / Maverick families,
     * Microsoft's `phi-3-vision`, Pixtral, Qwen2-VL, etc.) accept
     * this body.
     *
     * Caller contract:
     *  - [images] may be empty; in that case this is functionally
     *    identical to [ask] (we still send a request — the caller
     *    should usually fall back to [ask] / [askStreaming] instead so
     *    they get streaming behaviour for OpenAI-compat providers).
     *  - Each [ImageData.base64] must be raw base64 with no
     *    `data:image/...;base64,` prefix; for the OpenAI-compat path we
     *    prepend the data-URL header internally so the wire body is
     *    valid spec-compliant JSON.
     *  - The caller is responsible for pre-filtering on the model
     *    name via [LlmProvider.supportsImageContent]. Calling this
     *    with a text-only model name on Groq / NIM will produce a
     *    `400 invalid_request_error` from the upstream provider.
     *
     * Streaming is intentionally not implemented here: Gemini's chat
     * path in this app is already non-streaming today (see [ask]'s
     * `LlmProvider.Gemini` branch), and the multimodal Groq / NIM
     * path is non-streaming for matching UX. The UI shows the
     * "thinking" pill until the full answer comes back, same as a
     * text-only Gemini call.
     */
    suspend fun askMultimodal(
        config: ApiConfig,
        systemPrompt: String,
        userPrompt: String,
        images: List<ImageData>,
        maxTokens: Int = 4096,
        temperature: Double = 0.7,
    ): Result<String> {
        var currentConfig = config
        var lastErr: Throwable? = null
        val keysCount = maxOf(1, ServiceLocator.configRepository.getKeys().filter { it.isNotBlank() }.size)
        val maxAttempts = minOf(10, keysCount)

        for (attempt in 1..maxAttempts) {
            val result = executeAskMultimodal(currentConfig, systemPrompt, userPrompt, images, maxTokens, temperature)
            if (result.isSuccess) {
                ServiceLocator.configRepository.recordSuccess(currentConfig.apiKey)
                return result
            }

            val exception = result.exceptionOrNull() ?: RuntimeException("Unknown error")
            lastErr = exception

            val isQuota = isQuotaExceededError(exception)
            ServiceLocator.configRepository.recordFailure(currentConfig.apiKey, exception.message.orEmpty(), isQuota)

            if (isQuota && attempt < maxAttempts) {
                val nextConfig = ServiceLocator.configRepository.get(currentConfig.mode)
                if (nextConfig != null && nextConfig.apiKey != currentConfig.apiKey) {
                    currentConfig = nextConfig
                    continue
                }
            }
            break
        }
        return Result.failure(lastErr ?: RuntimeException("Multimodal request failed across all retries"))
    }

    private suspend fun executeAskMultimodal(
        config: ApiConfig,
        systemPrompt: String,
        userPrompt: String,
        images: List<ImageData>,
        maxTokens: Int = 4096,
        temperature: Double = 0.7,
    ): Result<String> = runCatching {
        try {
            when (config.provider) {
                LlmProvider.Gemini -> {
                    val api = LlmClientFactory.gemini(config)
                    // Build the user message's parts in stable order:
                    // every image first, then the text prompt last.
                    // Gemini's docs recommend interleaving but in
                    // practice "images then text" works equally well
                    // and keeps the prompt readable in logs. The text
                    // part itself enumerates the frames' timestamps so
                    // the model knows which screenshot maps to which
                    // moment.
                    val parts = buildList {
                        for (img in images) {
                            add(
                                GeminiPart(
                                    inlineData = GeminiInlineData(
                                        mimeType = img.mimeType,
                                        data     = img.base64
                                    )
                                )
                            )
                        }
                        add(GeminiPart(text = userPrompt))
                    }
                    val res = api.generate(
                        model  = config.modelName,
                        apiKey = config.apiKey,
                        body   = GeminiGenerateRequest(
                            systemInstruction = GeminiSystemInstruction(
                                parts = listOf(GeminiPart(text = systemPrompt))
                            ),
                            contents = listOf(
                                GeminiContent(role = "user", parts = parts)
                            ),
                            generationConfig = GeminiGenerationConfig(
                                temperature = temperature,
                                maxOutputTokens = maxTokens,
                                // Multimodal video-frame replies are
                                // free-form Hinglish prose — never JSON.
                                // Forcing application/json here would
                                // produce the same "model wraps prose
                                // in {}" failure mode we hit on the
                                // text path before, so stay null.
                                responseMimeType = null,
                            ),
                        )
                    )
                    res.firstText().orEmpty()
                }
                LlmProvider.Groq, LlmProvider.NvidiaNim -> {
                    // OpenAI-compatible vision: content array of parts
                    // (`{type:"text"}` + `{type:"image_url"}`). Same
                    // endpoint as the text-only [chat] path, just a
                    // different request body. The system message ALSO
                    // uses array form so the request shape is uniform —
                    // most providers accept array-form system messages
                    // and sticking to one shape avoids a "system OK,
                    // user rejected" failure mode.
                    val api = LlmClientFactory.openAiCompat(config)
                    val systemMsg = ChatVisionMessage(
                        role    = "system",
                        content = listOf(ChatVisionContentPart.text(systemPrompt))
                    )
                    val userParts = buildList {
                        for (img in images) {
                            // Build the data URL. The base64 must be
                            // raw (no existing `data:` prefix) per the
                            // [ImageData] contract; we add the prefix
                            // here exactly once so callers can't
                            // accidentally double-encode.
                            val dataUrl = "data:${img.mimeType};base64,${img.base64}"
                            add(ChatVisionContentPart.image(dataUrl))
                        }
                        // Text part LAST — this matches the order the
                        // Gemini path uses (images first, then text)
                        // so the prompt-engineering effects are
                        // consistent across providers.
                        add(ChatVisionContentPart.text(userPrompt))
                    }
                    val userMsg = ChatVisionMessage(role = "user", content = userParts)
                    val res = api.chatVision(
                        ChatVisionRequest(
                            model       = config.modelName,
                            messages    = listOf(systemMsg, userMsg),
                            temperature = temperature,
                            max_tokens  = maxTokens,
                        )
                    )
                    res.firstText().orEmpty()
                }
            }
        } catch (e: HttpException) {
            throw RuntimeException(formatHttpError(config, e), e)
        }
    }

    private fun formatHttpError(config: ApiConfig, e: HttpException): String {
        val body = runCatching { e.response()?.errorBody()?.string()?.take(300) }
            .getOrNull()
            .orEmpty()
            .replace('\n', ' ')
            .trim()
        val hint = when (e.code()) {
            401 -> " - check the API key."
            403 -> " - the key is valid but doesn't have access to this model."
            404 -> " - model name probably wrong (or endpoint not enabled)."
            429 -> " - rate-limited."
            else -> ""
        }
        return "${config.provider.displayName} HTTP ${e.code()} for model '${config.modelName}'$hint" +
                if (body.isNotEmpty()) "\n$body" else ""
    }

    /**
     * Stream tokens from an OpenAI-compatible provider. For Gemini, falls
     * back to non-streaming [ask] and emits the entire result as a single
     * token.
     *
     * **Resilience**: if the SSE stream completes without ever emitting a
     * non-empty token (e.g. provider returned an empty success, model is
     * silently rejected, etc.), we automatically fall back to the
     * non-streaming `ask` path and emit its result as one chunk. This
     * means the UI either gets an answer or a clear error — never the
     * "stuck on Thinking…" state we used to hit.
     */
    fun askStreaming(
        config: ApiConfig,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 4096,
        temperature: Double = 0.7,
        jsonMode: Boolean = false,
        wallclockMs: Long? = 180_000L,
    ): Flow<String> {
        return kotlinx.coroutines.flow.flow {
            var currentConfig = config
            val keysCount = maxOf(1, ServiceLocator.configRepository.getKeys().filter { it.isNotBlank() }.size)
            val maxAttempts = minOf(10, keysCount)
            var success = false
            var lastErr: Throwable? = null
            
            for (attempt in 1..maxAttempts) {
                try {
                    var tokensEmitted = false
                    executeAskStreaming(
                        currentConfig, systemPrompt, userPrompt, maxTokens, temperature, jsonMode, wallclockMs
                    ).collect { token ->
                        if (token.isNotEmpty()) {
                            if (!tokensEmitted) {
                                tokensEmitted = true
                                ServiceLocator.configRepository.recordSuccess(currentConfig.apiKey)
                            }
                            emit(token)
                        }
                    }
                    success = true
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                    val isQuota = isQuotaExceededError(e)
                    ServiceLocator.configRepository.recordFailure(currentConfig.apiKey, e.message.orEmpty(), isQuota)
                    
                    if (isQuota && attempt < maxAttempts) {
                        val nextConfig = ServiceLocator.configRepository.get(currentConfig.mode)
                        if (nextConfig != null && nextConfig.apiKey != currentConfig.apiKey) {
                            currentConfig = nextConfig
                            continue
                        }
                    }
                    throw e
                }
            }
            if (!success && lastErr != null) {
                throw lastErr
            }
        }
    }

    private fun executeAskStreaming(
        config: ApiConfig,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 4096,
        temperature: Double = 0.7,
        jsonMode: Boolean = false,
        wallclockMs: Long? = 180_000L,
    ): Flow<String> {
        if (!config.provider.isOpenAiCompatible) {
            // Gemini fallback: non-streaming, emit as one chunk.
            return kotlinx.coroutines.flow.flow {
                val result = ask(
                    config = config,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    jsonMode = jsonMode,
                )
                result.onSuccess { if (it.isNotBlank()) emit(it) }
                result.onFailure { throw it }
            }
        }
        return kotlinx.coroutines.flow.flow {
            var anyEmitted = false
            try {
                StreamingLlmClient.streamTokens(
                    config = config,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    jsonMode = jsonMode,
                    wallclockMs = wallclockMs,
                ).collect { token ->
                    if (token.isNotEmpty()) {
                        anyEmitted = true
                        emit(token)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Propagate cancellation up — never trigger a fresh fallback
                // HTTP call when the caller (or a parent scope) has been
                // cancelled.
                throw e
            } catch (e: Exception) {
                if (anyEmitted) throw e
                // Streaming threw before producing anything — try the
                // plain JSON path before giving up.
                val fallback = ask(
                    config = config,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    jsonMode = jsonMode,
                )
                fallback.onSuccess { if (it.isNotBlank()) emit(it) else throw e }
                fallback.onFailure { throw e }
                return@flow
            }
            if (!anyEmitted) {
                val fallback = ask(
                    config = config,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    jsonMode = jsonMode,
                )
                fallback.onSuccess { if (it.isNotBlank()) emit(it) }
                fallback.onFailure { throw it }
            }
        }
    }

    /**
     * Convenience: stream + accumulate into a single `Result<String>`.
     *
     * Used by the Generate section's NotesGenerator and McqGenerator
     * extraction. Streaming bypasses both the non-streaming HTTP read-
     * timeout (since each token resets the idle timer) and the silent
     * `max_tokens` truncation that was producing partial-but-invalid
     * HTML / JSON. Non-streaming `ask` is fine for short responses
     * (eligibility check, chat replies); use this whenever the
     * expected output is more than ~2 K tokens.
     */
    suspend fun askStreamingFull(
        config: ApiConfig,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 8192,
        temperature: Double = 0.7,
        jsonMode: Boolean = false,
        wallclockMs: Long? = 300_000L,
    ): Result<String> = runCatching {
        val acc = StringBuilder()
        try {
            askStreaming(
                config = config,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                maxTokens = maxTokens,
                temperature = temperature,
                jsonMode = jsonMode,
                wallclockMs = wallclockMs,
            ).collect { token ->
                acc.append(token)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // If we already accumulated something useful, fold the
            // partial output into the error message — easier than
            // throwing away kilobytes of (truncated) JSON / HTML.
            // Callers (NotesGenerator / McqGenerator) decide whether
            // to salvage; both currently throw because partial JSON /
            // partial HTML is worse than a clean failure.
            if (acc.isEmpty()) throw e
            throw RuntimeException(
                "Stream interrupted after ${acc.length} chars: ${e.message}", e
            )
        }
        acc.toString()
    }
}

/**
 * Coalesces a stream of token deltas into UI-rate snapshots of the
 * running concatenation.
 *
 * The naive ViewModel pattern is:
 *
 *     val acc = StringBuilder()
 *     llm.askStreaming(...).collect { token ->
 *         acc.append(token)
 *         updateMessage(idx, acc.toString(), isStreaming = true)
 *     }
 *
 * That fires a fresh `_state.update { ... }` on EVERY token. With Groq
 * pushing 50–150 tokens/s, the chat row's `RichTextRenderer.render(text)`
 * (an O(N) parse over the whole accumulated answer) runs ~N² times for
 * an N-token answer — visibly stutters around the 1000-token mark on
 * mid-tier devices, and is the root cause of the user's "lag" complaint
 * during long generations.
 *
 * `coalesceTokensForUi` accumulates tokens internally and emits the
 * running concatenation at most once every [minIntervalMs] (default
 * 50 ms ≈ 20 fps). The first token always emits immediately so the
 * "first words" appear with no perceptible cold-start delay, and the
 * final accumulator is always emitted on upstream completion so callers
 * still see the full answer (even if the last few tokens arrived inside
 * the throttle window).
 *
 * Exception-transparent: any throw from upstream propagates as-is,
 * without an extra trailing emission. The caller's existing error
 * handling owns that path.
 *
 * Empty tokens are filtered (no need to flush UI for nothing) and do
 * not reset the throttle timer — same observable behaviour as before
 * but cheaper.
 */
internal fun kotlinx.coroutines.flow.Flow<String>.coalesceTokensForUi(
    minIntervalMs: Long = 50L
): kotlinx.coroutines.flow.Flow<String> {
    // Capture the upstream Flow before opening `flow { ... }` — inside
    // that builder `this` is `FlowCollector<String>`, so a bare
    // `collect { ... }` would not resolve to the upstream's collect
    // extension.
    val upstream = this
    return kotlinx.coroutines.flow.flow {
        val acc = StringBuilder()
        var lastEmit = 0L
        var pending = false
        upstream.collect { token ->
            if (token.isEmpty()) return@collect
            acc.append(token)
            pending = true
            val now = System.currentTimeMillis()
            if (now - lastEmit >= minIntervalMs) {
                lastEmit = now
                pending = false
                emit(acc.toString())
            }
        }
        if (pending) emit(acc.toString())
    }
}
