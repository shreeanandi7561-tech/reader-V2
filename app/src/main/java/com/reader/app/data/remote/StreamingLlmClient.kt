package com.reader.app.data.remote

import com.reader.app.data.remote.dto.ChatCompletionRequest
import com.reader.app.data.remote.dto.ChatCompletionResponse
import com.reader.app.data.remote.dto.ChatMessage
import com.reader.app.data.remote.dto.ResponseFormat
import com.reader.app.domain.model.ApiConfig
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Streaming LLM client for OpenAI-compatible providers (Groq, Nvidia NIM).
 *
 * The provider is *asked* to stream via `stream=true`, but in practice some
 * providers / models silently downgrade to a regular JSON response (no SSE).
 * Earlier the client would then sit on `BufferedReader.readLine()` until the
 * 300 s read timeout because no `data:` lines ever arrived — the user saw
 * "Thinking…" forever and concluded the AI was broken.
 *
 * Now we:
 *  1. Inspect the response `Content-Type`. If it's not an event-stream we
 *     parse the whole body as [ChatCompletionResponse] and emit the answer
 *     in one shot — same UX as a non-streaming call.
 *  2. Bound the stream by a per-read *idle* timeout (in OkHttp config) and
 *     by an in-stream loop guard + absolute size cap (here). The previous
 *     hard 90 s call cap was the main cause of "answer cut off mid-sentence"
 *     for legitimately long generations — gone now.
 *  3. Detect runaway repetition. Some models (especially small / quantised
 *     ones) get stuck emitting the same phrase forever; we now break the
 *     stream as soon as three identical adjacent N-grams appear in the
 *     accumulator (for N in 16/32/64/128). What the user has so far is
 *     still useful and gets emitted; the loop never reaches them.
 *  4. Always close the response body in `finally` so the OkHttp connection
 *     pool isn't leaked when the collector cancels.
 */
object StreamingLlmClient {

    /**
     * Hard cap on streamed response size. ~64 KB ≈ 16 K tokens, which
     * comfortably fits a fully-structured HTML notes document (TOC +
     * 6-10 sections + callouts) plus headroom. Acts as a final
     * circuit breaker even if the loop guard is fooled.
     */
    private const val MAX_RESPONSE_CHARS = 64_000

    /**
     * How often (in accumulated chars) to re-run the loop check.
     *
     * Lowered from 256 → 32 (was 48 in v2) because the previous
     * interval missed loops where the repeated phrase was short
     * (e.g. one Hindi sentence ≈ 60 chars repeating five times); we'd
     * accumulate ~250 chars of repetition before the check kicked in.
     * 32 means we re-evaluate ~8 times per second at typical Groq
     * token rates, catching even short loops within ~2 cycles. Cost
     * is negligible — the detector is O(tail length) with the tail
     * capped at 1200 chars.
     */
    private const val LOOP_CHECK_EVERY_CHARS = 32

    /**
     * Default wallclock cap for a streaming call when the caller
     * doesn't pass one. 3 minutes is plenty for a long technical
     * answer (a 4 K-token explanation at 50 tokens/s ≈ 80 s), but
     * cuts off the 5-minute repetition loop the user was hitting
     * even if loop detection somehow misses the pattern.
     *
     * NotesGenerator + McqGenerator pass a longer cap (5 min) because
     * they legitimately produce 8-16 K tokens of structured output.
     * Set to `null` to disable entirely (only useful for tests).
     */
    private const val DEFAULT_WALLCLOCK_MS: Long = 180_000L

    @JsonClass(generateAdapter = true)
    data class StreamDelta(val content: String? = null)

    @JsonClass(generateAdapter = true)
    data class StreamChoice(val delta: StreamDelta? = null, val finish_reason: String? = null)

    @JsonClass(generateAdapter = true)
    data class StreamChunk(val choices: List<StreamChoice>? = null)

    private val streamChunkAdapter by lazy {
        LlmClientFactory.moshiInstance().adapter(StreamChunk::class.java)
    }
    private val fullResponseAdapter by lazy {
        LlmClientFactory.moshiInstance().adapter(ChatCompletionResponse::class.java)
    }
    private val requestAdapter by lazy {
        LlmClientFactory.moshiInstance().adapter(ChatCompletionRequest::class.java)
    }

    /**
     * Stream chat completion tokens from an OpenAI-compatible endpoint.
     *
     * The flow always emits at least one non-empty string on success — either
     * incremental SSE deltas or, for providers that don't actually stream,
     * the full answer as a single emission.
     *
     * @param maxTokens  upper bound on response length. The default
     *   (4096) matches the previous hardcoded value; Generate-section
     *   callers pass 8192 (MCQ extraction) or 16384 (HTML notes).
     * @param temperature  sampling temperature. Lower for verbatim-
     *   preserving tasks (MCQ uses 0.2); default 0.7 for chat.
     * @param jsonMode  when true, sets `response_format: {type:
     *   "json_object"}` on the request. Required for the Generate
     *   section's MCQ extractor.
     */
    fun streamTokens(
        config: ApiConfig,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 4096,
        temperature: Double = 0.7,
        jsonMode: Boolean = false,
        wallclockMs: Long? = DEFAULT_WALLCLOCK_MS,
    ): Flow<String> = flow {
        require(config.provider.isOpenAiCompatible) {
            "Streaming only supported for OpenAI-compatible providers (Groq, Nvidia NIM). " +
                "For Gemini, use the non-streaming path."
        }

        val requestBody = ChatCompletionRequest(
            model = config.modelName,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt)
            ),
            temperature = temperature,
            max_tokens = maxTokens,
            stream = true,
            response_format = if (jsonMode) ResponseFormat("json_object") else null,
        )
        val jsonBody = requestAdapter.toJson(requestBody)

        val url = "${config.provider.baseUrl}v1/chat/completions"
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val client = LlmClientFactory.streamingHttpClient(config.apiKey)
        val response = client.newCall(request).execute()

        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(400).orEmpty()
                    .replace('\n', ' ').trim()
                val hint = when (response.code) {
                    401 -> " — check the API key."
                    403 -> " — key valid but doesn't have access to this model."
                    404 -> " — model name probably wrong."
                    429 -> " — rate-limited."
                    else -> ""
                }
                throw RuntimeException(
                    "${config.provider.displayName} HTTP ${response.code} for model " +
                        "'${config.modelName}'$hint" +
                        if (errorBody.isNotEmpty()) "\n$errorBody" else ""
                )
            }

            val body = response.body ?: throw RuntimeException("Empty response body")
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val isSse = contentType.contains("text/event-stream") ||
                contentType.contains("application/x-ndjson")

            if (!isSse) {
                // Provider ignored stream=true and returned a regular JSON
                // ChatCompletionResponse. Parse the whole body and emit the
                // answer as one chunk — matches the non-streaming code path.
                val raw = body.string()
                val parsed = runCatching { fullResponseAdapter.fromJson(raw) }.getOrNull()
                val text = parsed?.firstText().orEmpty().ifBlank { raw.take(800) }
                if (text.isNotBlank()) emit(text)
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
            val acc = StringBuilder(2048)
            var lastLoopCheckedAt = 0
            // Wallclock deadline — relative to "first byte of the
            // request body arriving here". The OkHttp idle-timeout
            // already protects against a stalled stream (no token in
            // 75 s); this is the *upper bound* for an actively-but-
            // pathologically streaming call (e.g. a small model stuck
            // emitting tokens slowly inside a 5-minute repetition
            // loop). Null disables the cap.
            val deadlineNanos: Long? = wallclockMs?.let {
                System.nanoTime() + it * 1_000_000L
            }
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                // Wallclock guard. Checked once per SSE line — i.e. at
                // the same cadence as the OkHttp read returns. If
                // exceeded we break; whatever the caller already saw
                // through emit() is preserved.
                if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) break

                val l = line ?: continue
                if (!l.startsWith("data:")) continue
                val json = l.removePrefix("data:").trim()
                if (json == "[DONE]") break
                if (json.isEmpty()) continue

                val chunk = runCatching { streamChunkAdapter.fromJson(json) }.getOrNull()
                val token = chunk?.choices?.firstOrNull()?.delta?.content
                if (token.isNullOrEmpty()) continue

                emit(token)
                acc.append(token)

                // Absolute size cap — protects against runaway loops that
                // somehow slip past the suffix-repetition check below.
                if (acc.length >= MAX_RESPONSE_CHARS) break

                // Loop detection: only re-scan every LOOP_CHECK_EVERY_CHARS
                // of new content. Detection cost is O(L) (~1200 char
                // compares + a small normalise pass), run at most ~250
                // times per max-size response.
                if (acc.length - lastLoopCheckedAt >= LOOP_CHECK_EVERY_CHARS) {
                    lastLoopCheckedAt = acc.length
                    if (isLoopingTail(acc)) break
                }
            }
        } finally {
            runCatching { response.close() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Returns true iff the accumulator's tail looks like the model is
     * stuck repeating itself.
     *
     * Three layers, OR'd:
     *
     * **(a) Strict adjacent N-grams** — `acc[-N:] == acc[-2N:-N] ==
     * acc[-3N:-2N]` for some N in {8, 16, 32, 64, 128}. Catches the
     * common case where the model emits exactly the same byte
     * sequence three times in a row.
     *
     * **(b) Multi-scale fuzzy tail repetition** — for each window
     * `(needleLen, hayLen, threshold)` in three scales, count
     * non-overlapping occurrences of the NORMALISED last `needleLen`
     * chars within the NORMALISED last `hayLen` chars; trigger if
     * any scale reaches `threshold`.
     *   - `(30, 240, 3)`   — short sentence repeating many times.
     *   - `(60, 600, 3)`   — medium phrase / line repeating 5+ times.
     *   - `(120, 1200, 3)` — full paragraph repeating 3+ times.
     * The previous detector only had the (30, 240) scale, which
     * missed the user's reported case where a *full paragraph* was
     * looping — last 240 chars only fit ~2 paragraphs, so the same
     * 30-char tail-needle never appeared 3 times. The 1200-char
     * window catches that.
     *
     * **(c) Normalised comparison** — both needle and haystack are
     * lowercased and runs of whitespace are collapsed to a single
     * space before searching. This catches loops where the model
     * varies whitespace / capitalisation between iterations
     * (`"Yeh sahi hai.\nYeh sahi hai. Yeh  sahi hai. "`) — those
     * pass through the byte-exact (a) detector but trigger here.
     *
     * False-positive risk is tolerable: if a legitimate answer
     * happens to repeat the same 30-char phrase three times within
     * 240 chars (e.g. a list "ek-ek-ek …"), we cut off slightly
     * early. The user gets the partial answer instead of a 5-minute
     * loop, which is the lesser evil.
     *
     * Cost: O(N) per call, N ≤ 1200 (longest haystack). Run every
     * [LOOP_CHECK_EVERY_CHARS] chars (= 32). Total work caps at
     * roughly the legitimate emission rate, negligible on any
     * device.
     */
    private fun isLoopingTail(acc: CharSequence): Boolean {
        val n = acc.length

        // (a) strict adjacent N-grams
        for (window in intArrayOf(8, 16, 32, 64, 128)) {
            if (n < 3 * window) continue
            val a0 = n - window
            val b0 = n - 2 * window
            val c0 = n - 3 * window
            if (regionEquals(acc, a0, b0, window) &&
                regionEquals(acc, b0, c0, window)
            ) return true
        }

        // (b) + (c) multi-scale fuzzy + normalised
        // Each scale is tried independently — first to trigger wins.
        // Threshold 3 across all scales: a phrase repeating 3× is the
        // "looking stuck" line every model crosses; legitimate answers
        // almost never repeat the same 30/60/120-char phrase 3× in
        // their last 240/600/1200 chars.
        for (scale in FUZZY_SCALES) {
            val needleLen  = scale[0]
            val hayLen     = scale[1]
            val threshold  = scale[2]
            if (n < needleLen + hayLen) continue

            val tail   = acc.subSequence(n - hayLen, n).toString()
            val needle = tail.substring(tail.length - needleLen)
            val nTail   = normaliseForLoop(tail)
            val nNeedle = normaliseForLoop(needle)
            // After whitespace-collapse a 30-char raw needle can shrink
            // to ~15 chars in the heaviest cases. Below 8 chars we
            // refuse — too high false-positive risk on any list-y
            // legitimate output ("haan, bilkul, theek").
            if (nNeedle.length < 8) continue

            var count = 0
            var idx = 0
            while (idx <= nTail.length - nNeedle.length) {
                val found = nTail.indexOf(nNeedle, idx)
                if (found < 0) break
                count++
                if (count >= threshold) return true
                // Non-overlapping search — skip past the match.
                idx = found + nNeedle.length
            }
        }
        return false
    }

    /**
     * (needleLen, hayLen, threshold) tuples for the fuzzy detector.
     * Ordered cheapest-first so the common short-loop case bails
     * out before we look at longer windows.
     */
    private val FUZZY_SCALES: Array<IntArray> = arrayOf(
        intArrayOf(30, 240, 3),
        intArrayOf(60, 600, 3),
        intArrayOf(120, 1200, 3),
    )

    /**
     * Lowercase the input and collapse runs of whitespace to a single
     * space. Used in the fuzzy loop detector so superficial
     * formatting differences between repetitions ("hello.\n", "hello. ",
     * "Hello. ") collapse to the same comparison key.
     */
    private fun normaliseForLoop(s: CharSequence): String {
        val sb = StringBuilder(s.length)
        var lastWasSpace = false
        for (c in s) {
            if (c.isWhitespace()) {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(c.lowercaseChar())
                lastWasSpace = false
            }
        }
        return sb.toString()
    }

    private fun regionEquals(s: CharSequence, i: Int, j: Int, len: Int): Boolean {
        for (k in 0 until len) if (s[i + k] != s[j + k]) return false
        return true
    }
}
