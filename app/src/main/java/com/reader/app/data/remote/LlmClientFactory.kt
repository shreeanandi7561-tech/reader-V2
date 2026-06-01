package com.reader.app.data.remote

import com.reader.app.domain.model.ApiConfig
import com.reader.app.domain.model.LlmProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds a per-call Retrofit instance for the active [ApiConfig].
 *
 * Timeouts:
 *  - Non-streaming clients (this file): connect 60 s, read 180 s, write 60 s.
 *    Suitable for "ask once, get full JSON back" calls and Gemini.
 *  - Streaming client ([streamingHttpClient]): connect 30 s, read 75 s
 *    (per-read = idle between tokens), no hard call timeout. The stream
 *    is bounded by the idle timeout + the loop / size guards inside
 *    [StreamingLlmClient].
 *
 * `retryOnConnectionFailure` handles transient network drops with one retry.
 */
object LlmClientFactory {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val moshiConverter = MoshiConverterFactory.create(moshi)

    private fun baseHttpClient(authHeader: String? = null): OkHttpClient {
        val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val builder = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(log)

        if (!authHeader.isNullOrBlank()) {
            builder.addInterceptor(Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("Authorization", authHeader)
                    .addHeader("Accept", "application/json")
                    .build()
                chain.proceed(req)
            })
        }
        return builder.build()
    }

    fun openAiCompat(config: ApiConfig): OpenAiCompatApi {
        require(config.provider.isOpenAiCompatible) {
            "Provider ${config.provider} is not OpenAI-compatible"
        }
        val client = baseHttpClient(authHeader = "Bearer ${config.apiKey}")
        return Retrofit.Builder()
            .baseUrl(config.provider.baseUrl)
            .client(client)
            .addConverterFactory(moshiConverter)
            .build()
            .create(OpenAiCompatApi::class.java)
    }

    fun gemini(config: ApiConfig): GeminiApi {
        require(config.provider == LlmProvider.Gemini) { "Expected Gemini config" }
        val client = baseHttpClient(authHeader = null)
        return Retrofit.Builder()
            .baseUrl(config.provider.baseUrl)
            .client(client)
            .addConverterFactory(moshiConverter)
            .build()
            .create(GeminiApi::class.java)
    }

    fun streamingHttpClient(apiKey: String): OkHttpClient {
        val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Per-read on the streaming body — i.e. the *idle* timeout
            // between consecutive tokens. 75 s gives reasoning models
            // (Groq Qwen-QwQ, Llama-3.1-Reasoning, NIM Nemotron) enough
            // headroom to "think" between bursts of tokens, but is short
            // enough to surface a truly stalled upstream within ~minute.
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Intentionally NO callTimeout. A long answer that keeps
            // streaming tokens cleanly for several minutes is a *good*
            // outcome — the previous 90 s call cap was the single biggest
            // cause of "answer cut off mid-sentence" for long generations.
            // The stream is bounded instead by:
            //   1. the per-read idle timeout above,
            //   2. the in-stream loop guard in StreamingLlmClient, and
            //   3. the absolute response-size cap in StreamingLlmClient.
            .retryOnConnectionFailure(true)
            .addInterceptor(log)
            .addInterceptor(Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "text/event-stream")
                    .build()
                chain.proceed(req)
            })
            .build()
    }

    fun moshiInstance(): Moshi = moshi
}
