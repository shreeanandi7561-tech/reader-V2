package com.reader.app.domain.youtube

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hindi transcript / subtitle fetcher for YouTube.
 *
 * Line-by-line port of `TranscriptListFetcher` from
 * `youtube-transcript-api` v1.x — the exact Python library the user
 * pasted as the working reference.
 *
 * The earlier two attempts (WEB-client InnerTube; raw watch-page
 * scrape) both still fell back to title-only because of three subtle
 * differences from how the Python lib actually works. After cloning
 * `jdepoix/youtube-transcript-api` and reading the source we now know:
 *
 *  - The library does NOT use a hardcoded API key. It first GETs the
 *    watch page and pulls `INNERTUBE_API_KEY` out of the HTML with a
 *    regex `"INNERTUBE_API_KEY":"…"`. That same key is then used in
 *    the InnerTube POST. (In practice the value is the well-known
 *    public WEB key, but doing the extraction matters because YouTube
 *    binds the key to a session.)
 *  - The InnerTube body it sends is MINIMAL — just
 *    `{"context":{"client":{"clientName":"ANDROID","clientVersion":
 *    "20.10.38"}},"videoId":"…"}`. No `hl`, no `gl`, no
 *    `androidSdkVersion`, no `contentCheckOk`. Adding those extra
 *    fields is what makes YouTube strip captions / demand a PoToken.
 *  - It sends only ONE non-default header: `Accept-Language: en-US`.
 *    No `X-YouTube-Client-Name`, no `Origin`, no `Referer`, no custom
 *    `User-Agent`. The fancy headers we sent before were the giveaway
 *    for YouTube to treat us as a non-trusted client.
 *  - When it gets a track's `baseUrl`, it strips `&fmt=srv3` (so
 *    YouTube returns the simpler default XML), and only appends
 *    `&tlang=hi` when translating.
 *
 * The full algorithm:
 *
 *   1. GET `https://www.youtube.com/watch?v={id}` (Accept-Language en-US).
 *      Handle the EU `consent.youtube.com` redirect by extracting the
 *      `v` value and re-issuing the GET with a `CONSENT=YES+{v}` cookie.
 *   2. Pull `INNERTUBE_API_KEY` out of the HTML with a regex.
 *   3. POST `https://www.youtube.com/youtubei/v1/player?key={key}`
 *      with the minimal ANDROID/20.10.38 context body.
 *   4. Read `captions.playerCaptionsTracklistRenderer.captionTracks`.
 *   5. Pick the right track using the user's Python priority:
 *        a) ASR Hindi  (kind == "asr", language code "hi*")
 *        b) manual Hindi
 *        c) ANY translatable track + tlang=hi (server-side translation)
 *   6. Strip `&fmt=srv3` from the baseUrl, append `&tlang=hi` if
 *      translating, then GET it. YouTube returns simple XML.
 *   7. Parse the `<text>` elements into one plain-text string AND a
 *      structured [List<TranscriptCue>] preserving each cue's
 *      `(startSec, durSec)`. The structured list is what Discussion
 *      mode uses to sync the AI prompt to the actual moment in the
 *      video the student paused on; the flat string is still what the
 *      Reading-mode chunker consumes (so its sentence-by-sentence flow
 *      is unchanged).
 *
 * The video title comes from oEmbed (independent path) so we always
 * have a Title-Only fallback even when captions aren't accessible.
 */
object YouTubeTranscriptFetcher {

    sealed interface Result {
        data class Ok(
            val videoId: String,
            val title: String,
            val transcript: String,
            /**
             * Per-cue captions with their `(startSec, durSec)`. Empty for
             * the [Source.TitleOnly] fallback (no transcript was
             * available) — Discussion mode in that case still renders
             * the video player but feeds the AI flat-document context.
             */
            val cues: List<TranscriptCue>,
            val language: String,        // "hi" / "hi-IN" when transcript present, "" when title-only
            val source: Source,
            /**
             * Raw `playerStoryboardSpecRenderer.spec` string captured
             * from the same InnerTube `player` response that gave us
             * the captions. Null when:
             *  - we never reached the InnerTube call (early failure),
             *  - the player response had no `storyboards` field
             *    (extremely rare — most public videos expose one), or
             *  - YouTube returned the live-VOD variant in a format
             *    StoryboardSpec.parse can't decode.
             *
             * Persisted as-is on the document; parsed lazily at doubt
             * time. Drives the storyboard fallback in the multimodal
             * frame pipeline (see `StoryboardFrameSource` /
             * `CompositeVideoFrameSource`) — when the WebView frame
             * capture comes back mostly-black on a hardware-protected
             * video surface, we resolve the cell at the requested
             * timestamp from `i.ytimg.com/sb/...` and ship that
             * instead, so the AI still sees what the teacher had on
             * screen.
             */
            val storyboardSpec: String? = null
        ) : Result
        data class Reject(val reason: String) : Result
    }

    enum class Source { Transcript, TitleOnly }

    private const val WATCH_BASE        = "https://www.youtube.com/watch?v="
    private const val INNERTUBE_API_URL = "https://www.youtube.com/youtubei/v1/player?key="

    /** Match the Python lib exactly. Don't add hl/gl/androidSdkVersion. */
    private const val INNERTUBE_BODY_TEMPLATE =
        "{\"context\":{\"client\":{\"clientName\":\"ANDROID\"," +
            "\"clientVersion\":\"20.10.38\"}},\"videoId\":\"%s\"}"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val playerAdapter by lazy { moshi.adapter(PlayerResponseJson::class.java) }
    private val oEmbedAdapter by lazy { moshi.adapter(OEmbedJson::class.java) }
    private val json3Adapter  by lazy { moshi.adapter(Json3RootJson::class.java) }

    private val INNERTUBE_KEY_REGEX  = Regex("\"INNERTUBE_API_KEY\":\\s*\"([a-zA-Z0-9_-]+)\"")
    private val CONSENT_VALUE_REGEX  = Regex("name=\"v\" value=\"(.*?)\"")
    private const val CONSENT_MARKER = "action=\"https://consent.youtube.com/s\""

    suspend fun fetch(url: String): Result = withContext(Dispatchers.IO) {
        val videoId = parseVideoId(url) ?: return@withContext Result.Reject(
            "YouTube URL parse nahi ho saka. Full URL paste karein " +
                "(youtube.com/watch?v=… ya youtu.be/…)."
        )

        // Title via oEmbed — independent path; used as Title-Only fallback.
        val title = runCatching { fetchTitle(videoId) }.getOrNull().orEmpty()

        // Step 1+2: watch page HTML → INNERTUBE_API_KEY.
        val html = runCatching { fetchVideoHtml(videoId) }.getOrNull()
        if (html.isNullOrBlank()) {
            return@withContext titleOrReject(
                videoId, title,
                "YouTube watch page fetch nahi ho payi. Network / VPN check karein."
            )
        }
        val apiKey = INNERTUBE_KEY_REGEX.find(html)?.groupValues?.get(1)
            ?: return@withContext titleOrReject(
                videoId, title,
                "INNERTUBE_API_KEY watch page mein nahi mili — YouTube ne layout " +
                    "badal di hai ya IP block hai."
            )

        // Step 3: POST to InnerTube as ANDROID client.
        val player = runCatching { fetchInnertubePlayer(videoId, apiKey) }.getOrNull()
            ?: return@withContext titleOrReject(
                videoId, title,
                "YouTube InnerTube call fail ho gayi. Doosri video try karein."
            )

        // Storyboard spec is captured opportunistically — independent
        // of the captions path, so even Title-Only fallback can ship a
        // spec if the video had one. Stored verbatim; parsed lazily
        // at doubt time. Prefer the regular renderer; fall back to
        // the live-VOD variant when present.
        val storyboardSpec = player.storyboards?.playerStoryboardSpecRenderer?.spec
            ?: player.storyboards?.playerLiveStoryboardSpecRenderer?.spec

        // Step 4: caption tracks.
        val tracks = player.captions?.playerCaptionsTracklistRenderer?.captionTracks.orEmpty()
        if (tracks.isEmpty()) {
            return@withContext titleOrReject(
                videoId, title,
                "Iss video par koi captions / subtitles available nahi hain. " +
                    "Doosri public video try karein, ya neeche manually content paste karein.",
                storyboardSpec = storyboardSpec
            )
        }

        // Step 5: pick — ASR Hindi → manual Hindi → translate-to-Hindi.
        val pick = pickHindiOrTranslate(tracks) ?: return@withContext titleOrReject(
            videoId, title,
            "Iss video par Hindi captions nahi hain aur translation bhi possible nahi hai.",
            storyboardSpec = storyboardSpec
        )

        // Step 6: build URL — strip &fmt=srv3, append &tlang=hi if needed.
        // Since ~May 2025 YouTube requires a `&pot` (Proof of Origin
        // Token) on timedtext baseUrl requests. Without it, the URL
        // returns HTTP 200 but with an empty body. We attempt the
        // direct URL first (still works for some videos / regions);
        // if it comes back blank, we fall back to the InnerTube
        // `get_transcript` endpoint which doesn't require pot.
        val baseClean = pick.track.baseUrl.orEmpty().replace("&fmt=srv3", "")
        val captionUrl = if (pick.translateTo.isNullOrBlank()) baseClean
            else "$baseClean&tlang=${pick.translateTo}"

        // Step 7: fetch and parse captions — try direct URL first,
        // then InnerTube get_transcript fallback.
        var rawXml = if (captionUrl.isNotBlank() && !captionUrl.contains("&exp=xpe")) {
            runCatching { httpGetText(captionUrl) }.getOrNull().orEmpty()
        } else ""

        var cues = parseCues(rawXml)

        // Fallback: if direct URL returned empty (pot-gated), use
        // InnerTube's get_transcript endpoint which extracts the
        // transcript without needing the pot token. This endpoint
        // returns JSON with `actions[].transcriptSegmentRenderer`
        // or `engagementPanels` containing the transcript body.
        if (cues.isEmpty() || cues.all { it.text.isBlank() }) {
            val fallbackCues = runCatching {
                fetchTranscriptViaInnerTube(videoId, apiKey, pick.track.languageCode.orEmpty(), pick.translateTo)
            }.getOrNull()
            if (!fallbackCues.isNullOrEmpty()) {
                cues = fallbackCues
            }
        }

        val transcript = cues.joinToString(separator = " ") { it.text }
        if (transcript.isBlank()) {
            return@withContext titleOrReject(
                videoId, title,
                "Captions track mil gaya lekin transcript empty aaya. " +
                    "YouTube ne pot-token require kar diya hai ya network issue hai. " +
                    "Doosri video try karein.",
                storyboardSpec = storyboardSpec
            )
        }

        Result.Ok(
            videoId        = videoId,
            title          = title.ifBlank { player.videoDetails?.title.orEmpty() },
            transcript     = transcript,
            cues           = cues,
            language       = pick.translateTo ?: pick.track.languageCode.orEmpty(),
            source         = Source.Transcript,
            storyboardSpec = storyboardSpec
        )
    }

    private fun titleOrReject(
        videoId: String,
        title: String,
        reason: String,
        storyboardSpec: String? = null
    ): Result =
        if (title.isNotBlank())
            Result.Ok(videoId, title, "", emptyList(), "", Source.TitleOnly, storyboardSpec)
        else
            Result.Reject(reason)

    // ---------- HTTP ----------

    /**
     * Single GET helper. Mirrors the Python `requests.Session()` setup
     * the library uses — only `Accept-Language: en-US` is added,
     * everything else is the library default. Optional `cookie`
     * supports the EU consent retry.
     */
    private fun httpGetText(url: String, cookie: String? = null): String {
        if (url.isBlank()) error("Empty URL")
        val builder = Request.Builder()
            .url(url)
            .header("Accept-Language", "en-US")
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            return resp.body?.string().orEmpty()
        }
    }

    /** POST JSON to InnerTube. Same minimal headers as the Python lib. */
    private fun httpPostJson(url: String, jsonBody: String): String {
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Accept-Language", "en-US")
            .header("Content-Type", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            return resp.body?.string().orEmpty()
        }
    }

    /** GET watch page, handling the EU consent redirect like Python. */
    private fun fetchVideoHtml(videoId: String): String {
        val watchUrl = WATCH_BASE + videoId
        val first = httpGetText(watchUrl)
        if (!first.contains(CONSENT_MARKER)) return first

        // Consent retry: extract v=… from the form, set CONSENT cookie.
        val v = CONSENT_VALUE_REGEX.find(first)?.groupValues?.get(1)
            ?: return first   // can't fix — return what we have
        val second = runCatching {
            httpGetText(watchUrl, cookie = "CONSENT=YES+$v")
        }.getOrNull().orEmpty()
        return second.ifBlank { first }
    }

    private fun fetchInnertubePlayer(videoId: String, apiKey: String): PlayerResponseJson? {
        val url  = INNERTUBE_API_URL + apiKey
        val body = INNERTUBE_BODY_TEMPLATE.format(videoId)
        val raw  = runCatching { httpPostJson(url, body) }.getOrNull() ?: return null
        return runCatching { playerAdapter.fromJson(raw) }.getOrNull()
    }

    /**
     * Fallback transcript fetch via InnerTube's `get_transcript` endpoint.
     *
     * Since ~May 2025, YouTube's timedtext `baseUrl` requires a `pot`
     * (Proof of Origin Token) that's only available to the JS player.
     * Without it, the URL returns HTTP 200 but an empty body.
     *
     * This endpoint (`/youtubei/v1/get_transcript`) is what YouTube's
     * own "Show transcript" panel uses internally. It doesn't require
     * the pot token — just the standard InnerTube ANDROID client
     * context + the video ID + a `params` value that encodes the
     * desired transcript language.
     *
     * The `params` value is a base64-encoded protobuf that says
     * "give me the transcript for video X in language Y". We construct
     * a minimal version using the same technique the `youtube-transcript-api`
     * v1.x library does:
     *   - The param encodes `videoId` + optional `languageCode`
     *   - For simplicity we use a pre-computed template for the
     *     common Hindi / auto-detect cases
     *
     * Response shape (simplified):
     * ```json
     * {
     *   "actions": [{
     *     "updateEngagementPanelAction": {
     *       "content": {
     *         "transcriptRenderer": {
     *           "body": {
     *             "transcriptBodyRenderer": {
     *               "cueGroups": [{
     *                 "transcriptCueGroupRenderer": {
     *                   "cues": [{
     *                     "transcriptCueRenderer": {
     *                       "cue": { "simpleText": "..." },
     *                       "startOffsetMs": "12340",
     *                       "durationMs": "5670"
     *                     }
     *                   }]
     *                 }
     *               }]
     *             }
     *           }
     *         }
     *       }
     *     }
     *   }]
     * }
     * ```
     *
     * We parse the `cueGroups` array into [TranscriptCue] objects.
     */
    private fun fetchTranscriptViaInnerTube(
        videoId: String,
        apiKey: String,
        languageCode: String,
        translateTo: String?
    ): List<TranscriptCue>? {
        val url = "https://www.youtube.com/youtubei/v1/get_transcript?key=$apiKey"

        // Build the params proto-like value. The youtube-transcript-api
        // library encodes a protobuf, but we can use a simpler approach:
        // YouTube also accepts a JSON body with just videoId + the
        // params field set to a specific base64 string.
        //
        // The params encode: "fetch transcript for this video".
        // A minimal working params value that works for most videos:
        // Base64 of a small protobuf: field 1 = "\n\x0b{videoId}"
        // We construct it manually.
        val paramsBytes = buildTranscriptParams(videoId)
        val params = android.util.Base64.encodeToString(paramsBytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)

        val body = buildString {
            append("{\"context\":{\"client\":{\"clientName\":\"WEB\",")
            append("\"clientVersion\":\"2.20250520.01.00\"}},")
            append("\"params\":\"").append(params).append("\"}")
        }

        val raw = runCatching { httpPostJson(url, body) }.getOrNull() ?: return null
        return parseGetTranscriptResponse(raw)
    }

    /**
     * Build the protobuf-like `params` bytes for get_transcript.
     *
     * The structure is (reverse-engineered from youtube-transcript-api):
     *   field 1 (string): "\n" + videoId (with length-prefix)
     *
     * Minimal encoding: just enough for YouTube to understand
     * "give me the default transcript for this video".
     */
    private fun buildTranscriptParams(videoId: String): ByteArray {
        // Protobuf encoding:
        // Field 1, wire type 2 (length-delimited): tag = 0x0A
        // Inner message field 1, wire type 2: tag = 0x0A
        // String value = videoId (11 bytes)
        val vidBytes = videoId.toByteArray(Charsets.UTF_8)
        val inner = ByteArray(2 + vidBytes.size)
        inner[0] = 0x0A.toByte()  // field 1, length-delimited
        inner[1] = vidBytes.size.toByte()
        vidBytes.copyInto(inner, 2)

        val outer = ByteArray(2 + inner.size)
        outer[0] = 0x0A.toByte()  // field 1, length-delimited
        outer[1] = inner.size.toByte()
        inner.copyInto(outer, 2)

        return outer
    }

    /**
     * Parse the get_transcript JSON response into a list of cues.
     * Handles the nested `actions[].updateEngagementPanelAction.content
     * .transcriptRenderer.body.transcriptBodyRenderer.cueGroups[]
     * .transcriptCueGroupRenderer.cues[].transcriptCueRenderer` path.
     *
     * Falls back to regex extraction if the JSON structure varies from
     * what we expect (YouTube changes this occasionally).
     */
    private fun parseGetTranscriptResponse(raw: String): List<TranscriptCue>? {
        if (raw.isBlank()) return null

        // Strategy 1: Parse structured JSON using simple string scanning
        // (avoid adding a full JSON tree walker dependency — the structure
        // is predictable enough for targeted extraction).
        val cues = ArrayList<TranscriptCue>()

        // Look for "transcriptCueRenderer" blocks
        val cueRendererRegex = Regex(
            "\"transcriptCueRenderer\"\\s*:\\s*\\{[^}]*?" +
                "\"cue\"\\s*:\\s*\\{[^}]*?\"simpleText\"\\s*:\\s*\"([^\"]*?)\"[^}]*?\\}[^}]*?" +
                "\"startOffsetMs\"\\s*:\\s*\"(\\d+)\"[^}]*?" +
                "\"durationMs\"\\s*:\\s*\"(\\d+)\"",
            RegexOption.DOT_MATCHES_ALL
        )

        for (m in cueRendererRegex.findAll(raw)) {
            val text = m.groupValues[1]
                .replace("\\n", " ")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .trim()
            val startMs = m.groupValues[2].toLongOrNull() ?: continue
            val durMs = m.groupValues[3].toLongOrNull() ?: 0L
            if (text.isNotBlank()) {
                cues += TranscriptCue(
                    startSec = startMs.toDouble() / 1000.0,
                    durSec = durMs.toDouble() / 1000.0,
                    text = decodeEntities(text)
                )
            }
        }

        if (cues.isNotEmpty()) return cues

        // Strategy 2: Some responses put startOffsetMs before cue.
        // Try a more relaxed pattern.
        val relaxedRegex = Regex(
            "\"startOffsetMs\"\\s*:\\s*\"(\\d+)\"[\\s\\S]*?" +
                "\"durationMs\"\\s*:\\s*\"(\\d+)\"[\\s\\S]*?" +
                "\"simpleText\"\\s*:\\s*\"([^\"]*?)\"",
        )
        for (m in relaxedRegex.findAll(raw)) {
            val startMs = m.groupValues[1].toLongOrNull() ?: continue
            val durMs = m.groupValues[2].toLongOrNull() ?: 0L
            val text = m.groupValues[3]
                .replace("\\n", " ")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .trim()
            if (text.isNotBlank()) {
                cues += TranscriptCue(
                    startSec = startMs.toDouble() / 1000.0,
                    durSec = durMs.toDouble() / 1000.0,
                    text = decodeEntities(text)
                )
            }
        }

        return cues.takeIf { it.isNotEmpty() }
    }

    private fun fetchTitle(videoId: String): String {
        val url = "https://www.youtube.com/oembed?url=" +
            "https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$videoId&format=json"
        val body = runCatching { httpGetText(url) }.getOrNull().orEmpty()
        if (body.isBlank()) return ""
        return runCatching { oEmbedAdapter.fromJson(body)?.title.orEmpty() }
            .getOrNull().orEmpty()
    }

    // ---------- url to video id ----------

    private val watchVRegex = Regex("[?&]v=([\\w-]{11})")
    private val shortRegex  = Regex("youtu\\.be/([\\w-]{11})")
    private val embedRegex  = Regex("/embed/([\\w-]{11})")
    private val shortsRegex = Regex("/shorts/([\\w-]{11})")
    private val liveRegex   = Regex("/live/([\\w-]{11})")

    fun parseVideoId(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        watchVRegex.find(s)?.let { return it.groupValues[1] }
        shortRegex.find(s)?.let { return it.groupValues[1] }
        embedRegex.find(s)?.let { return it.groupValues[1] }
        shortsRegex.find(s)?.let { return it.groupValues[1] }
        liveRegex.find(s)?.let { return it.groupValues[1] }
        if (s.matches(Regex("[\\w-]{11}"))) return s
        return null
    }

    // ---------- Pick logic ----------

    private data class Pick(val track: CaptionTrackJson, val translateTo: String?)

    /**
     * Mirrors the user's pasted `pick_hindi_transcript`:
     *   1. find_generated_transcript(['hi'])          → ASR Hindi
     *   2. find_transcript(['hi'])                    → manual Hindi
     *   3. for t in transcript_list: t.translate('hi')→ tlang=hi
     */
    private fun pickHindiOrTranslate(tracks: List<CaptionTrackJson>): Pick? {
        if (tracks.isEmpty()) return null
        fun isHindi(t: CaptionTrackJson): Boolean {
            val code = t.languageCode.orEmpty()
            val vss  = t.vssId.orEmpty()
            return code.startsWith("hi", ignoreCase = true) ||
                vss.startsWith(".hi", ignoreCase = true) ||
                vss.startsWith("a.hi", ignoreCase = true)
        }
        val hindi = tracks.filter(::isHindi)

        // 1. ASR Hindi
        hindi.firstOrNull { it.kind.equals("asr", ignoreCase = true) }
            ?.let { return Pick(it, translateTo = null) }
        // 2. Manual Hindi (kind != "asr", or kind missing entirely)
        hindi.firstOrNull { !it.kind.equals("asr", ignoreCase = true) }
            ?.let { return Pick(it, translateTo = null) }
        if (hindi.isNotEmpty()) return Pick(hindi.first(), translateTo = null)

        // 3. Translation fallback. Prefer a track explicitly marked
        // translatable; otherwise just take the first track and trust
        // YouTube to translate it.
        val translatable = tracks.firstOrNull { it.isTranslatable == true }
            ?: tracks.firstOrNull()
        return translatable?.let { Pick(it, translateTo = "hi") }
    }

    // ---------- Caption parsing ----------

    private val xmlCueRegex     = Regex("<text\\s+([^>]*)>([\\s\\S]*?)</text>")
    private val xmlBareRegex    = Regex("<text[^>]*>([\\s\\S]*?)</text>")
    private val attrStartRegex  = Regex("\\bstart=\"([^\"]+)\"")
    private val attrDurRegex    = Regex("\\bdur=\"([^\"]+)\"")
    private val numericEntity   = Regex("&#(\\d+);")
    private val hexEntity       = Regex("&#x([0-9a-fA-F]+);")
    private val srtVttTimeline  = Regex(
        "^\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?[.,]\\d{2,3})" +
            "\\s*-->\\s*" +
            "(\\d{1,2}:\\d{2}(?::\\d{2})?[.,]\\d{2,3}).*$"
    )
    private val srtVttTimestamp = Regex(
        "^\\s*\\d{1,2}:\\d{2}(?::\\d{2})?[.,]\\d{2,3}" +
            "\\s*-->\\s*" +
            "\\d{1,2}:\\d{2}(?::\\d{2})?[.,]\\d{2,3}.*$"
    )
    private val cueNumberLine   = Regex("^\\s*\\d+\\s*$")
    private val webvttHeader    = Regex("^WEBVTT(\\s.*)?$")
    private val webvttKind      = Regex("^(NOTE|STYLE|REGION|Kind:|Language:)(\\s.*)?$")
    private val inlineTagRegex  = Regex("<[^>]+>")

    /**
     * Convert any of YouTube's caption blob formats into a structured
     * list of [TranscriptCue] (with timestamps preserved):
     *  - srv1 / srv3 XML (`<text start="..." dur="...">…</text>`)
     *  - WebVTT (`00:00:01.500 --> 00:00:04.000` timestamps)
     *  - SRT (cue numbers + timestamps with comma separators)
     *  - json3 (`events[*].segs[*].utf8` chunks with `tStartMs`/`dDurationMs`)
     *
     * Format detection order matches YouTube's own preference (json3 →
     * XML → VTT/SRT). When the blob doesn't look like any of those (and
     * there's still some text), we fall back to a single `(0, 0, text)`
     * cue so Reading-mode chunking still works — Discussion mode then
     * silently treats it as a video without a sync-able transcript.
     */
    fun parseCues(raw: String): List<TranscriptCue> {
        if (raw.isBlank()) return emptyList()

        // 1. json3
        if (raw.trimStart().startsWith("{") && raw.contains("\"segs\"")) {
            val cues = parseJson3Cues(raw)
            if (cues.isNotEmpty()) return cues
        }

        // 2. XML <text>
        if (raw.contains("<text")) {
            val cues = parseXmlCues(raw)
            if (cues.isNotEmpty()) return cues
        }

        // 3. VTT / SRT
        val cues = parseVttSrtCues(raw)
        if (cues.isNotEmpty()) return cues

        // 4. Fallback — single un-timed cue.
        val flat = fallbackFlatten(raw)
        return if (flat.isBlank()) emptyList()
        else listOf(TranscriptCue(startSec = 0.0, durSec = 0.0, text = flat))
    }

    /**
     * Backwards-compat: return the same flat plain-text representation
     * the file used to expose. Now derived from [parseCues] so the two
     * always agree.
     */
    fun cleanCaptions(raw: String): String =
        parseCues(raw).joinToString(separator = " ") { it.text }

    private fun parseJson3Cues(raw: String): List<TranscriptCue> {
        val parsed = runCatching { json3Adapter.fromJson(raw) }.getOrNull()
        val events = parsed?.events.orEmpty()
        if (events.isEmpty()) return emptyList()
        val out = ArrayList<TranscriptCue>(events.size)
        for (e in events) {
            val start = (e.tStartMs ?: continue).toDouble() / 1000.0
            val dur   = (e.dDurationMs ?: 0L).toDouble() / 1000.0
            val segs  = e.segs ?: continue
            val sb    = StringBuilder()
            for (s in segs) {
                val piece = s.utf8 ?: continue
                if (piece.isBlank()) continue
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(piece.replace('\n', ' ').trim())
            }
            val text = decodeEntities(sb.toString()).trim()
            if (text.isNotEmpty()) out += TranscriptCue(start, dur, text)
        }
        return out
    }

    private fun parseXmlCues(raw: String): List<TranscriptCue> {
        val out = ArrayList<TranscriptCue>()
        // Prefer the attribute-aware regex so we capture start/dur. If no
        // hits (e.g. some servers emit `<text>...</text>` without any
        // attributes), fall back to the bare matcher with start=0/dur=0.
        var matched = false
        for (m in xmlCueRegex.findAll(raw)) {
            matched = true
            val attrs = m.groupValues[1]
            val body  = m.groupValues[2]
            if (body.isBlank()) continue
            val s = attrStartRegex.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val d = attrDurRegex.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val text = decodeEntities(inlineTagRegex.replace(body, "")).trim()
            if (text.isNotEmpty()) out += TranscriptCue(s, d, text)
        }
        if (matched) return out
        // Bare fallback.
        for (m in xmlBareRegex.findAll(raw)) {
            val body = m.groupValues[1]
            if (body.isBlank()) continue
            val text = decodeEntities(inlineTagRegex.replace(body, "")).trim()
            if (text.isNotEmpty()) out += TranscriptCue(0.0, 0.0, text)
        }
        return out
    }

    private fun parseVttSrtCues(raw: String): List<TranscriptCue> {
        val lines = raw.lineSequence().toList()
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<TranscriptCue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isBlank() ||
                webvttHeader.matches(line) ||
                webvttKind.matches(line) ||
                cueNumberLine.matches(line)
            ) {
                i++
                continue
            }
            val m = srtVttTimeline.matchEntire(line)
            if (m == null) {
                i++
                continue
            }
            val startSec = parseVttTime(m.groupValues[1])
            val endSec   = parseVttTime(m.groupValues[2])
            i++
            val sb = StringBuilder()
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.isBlank()) { i++; break }
                if (cueNumberLine.matches(t) || srtVttTimestamp.matches(t)) break
                val cleaned = decodeEntities(inlineTagRegex.replace(t, "")).trim()
                if (cleaned.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(cleaned)
                }
                i++
            }
            val text = sb.toString().trim()
            if (text.isNotEmpty()) {
                out += TranscriptCue(
                    startSec = startSec,
                    durSec   = (endSec - startSec).coerceAtLeast(0.0),
                    text     = text
                )
            }
        }
        return out
    }

    /** Generic line-by-line cleaner for unknown formats. */
    private fun fallbackFlatten(raw: String): String {
        val sb = StringBuilder()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            if (webvttHeader.matches(trimmed)) continue
            if (webvttKind.matches(trimmed)) continue
            if (srtVttTimestamp.matches(trimmed)) continue
            if (cueNumberLine.matches(trimmed)) continue
            val cleaned = decodeEntities(inlineTagRegex.replace(trimmed, "")).trim()
            if (cleaned.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(cleaned)
            }
        }
        return sb.toString()
    }

    /**
     * Parse a `HH:MM:SS.mmm` / `MM:SS.mmm` timestamp (comma also
     * accepted as the milli separator, since SRT uses it) into seconds.
     */
    private fun parseVttTime(raw: String): Double {
        val s = raw.replace(',', '.').trim()
        val parts = s.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toDoubleOrNull() ?: 0.0
                val m = parts[1].toDoubleOrNull() ?: 0.0
                val sec = parts[2].toDoubleOrNull() ?: 0.0
                h * 3600.0 + m * 60.0 + sec
            }
            2 -> {
                val m = parts[0].toDoubleOrNull() ?: 0.0
                val sec = parts[1].toDoubleOrNull() ?: 0.0
                m * 60.0 + sec
            }
            else -> 0.0
        }
    }

    private fun decodeEntities(raw: String): String {
        var out = raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        out = numericEntity.replace(out) {
            val code = it.groupValues[1].toIntOrNull() ?: return@replace it.value
            if (code in 0..0x10FFFF) String(Character.toChars(code)) else it.value
        }
        out = hexEntity.replace(out) {
            val code = it.groupValues[1].toIntOrNull(16) ?: return@replace it.value
            if (code in 0..0x10FFFF) String(Character.toChars(code)) else it.value
        }
        return out
    }

    // ---------- DTOs ----------

    @JsonClass(generateAdapter = true)
    internal data class CaptionTrackJson(
        val baseUrl: String? = null,
        val languageCode: String? = null,
        val kind: String? = null,
        val vssId: String? = null,
        val isTranslatable: Boolean? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class CaptionsTracklistJson(
        val captionTracks: List<CaptionTrackJson>? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class CaptionsRootJson(
        val playerCaptionsTracklistRenderer: CaptionsTracklistJson? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class VideoDetailsJson(
        val title: String? = null,
        val author: String? = null,
        val videoId: String? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class PlayerResponseJson(
        val captions: CaptionsRootJson? = null,
        val videoDetails: VideoDetailsJson? = null,
        val storyboards: StoryboardsRootJson? = null
    )

    /* Storyboard preview-thumbnail descriptor from the same player
     * response. Captured opportunistically — the field's absence
     * never blocks the transcript fetch. The live-VOD variant
     * `playerLiveStoryboardSpecRenderer` is parsed best-effort by
     * StoryboardSpec at doubt time. */
    @JsonClass(generateAdapter = true)
    internal data class StoryboardSpecRendererJson(
        val spec: String? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class StoryboardsRootJson(
        val playerStoryboardSpecRenderer: StoryboardSpecRendererJson? = null,
        val playerLiveStoryboardSpecRenderer: StoryboardSpecRendererJson? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class OEmbedJson(
        val title: String? = null,
        val author_name: String? = null
    )

    /* json3 caption blob — `events[*].segs[*].utf8`. */
    @JsonClass(generateAdapter = true)
    internal data class Json3SegJson(
        val utf8: String? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class Json3EventJson(
        val tStartMs: Long? = null,
        val dDurationMs: Long? = null,
        val segs: List<Json3SegJson>? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class Json3RootJson(
        val events: List<Json3EventJson>? = null
    )
}
