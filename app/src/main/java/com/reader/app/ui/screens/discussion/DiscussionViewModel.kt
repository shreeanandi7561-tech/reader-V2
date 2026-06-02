package com.reader.app.ui.screens.discussion

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.repository.ConfigRepository
import com.reader.app.data.repository.DocumentRepository
import com.reader.app.data.repository.LlmRepository
import com.reader.app.data.repository.TtsPreferencesRepository
import com.reader.app.data.repository.coalesceTokensForUi
import com.reader.app.di.ServiceLocator
import com.reader.app.domain.model.AppMode
import com.reader.app.domain.model.LlmProvider
import com.reader.app.domain.rag.PromptBuilder
import com.reader.app.domain.stt.SpeechRecognizerController
import com.reader.app.domain.text.MarkdownStripper
import com.reader.app.domain.tts.TtsController
import com.reader.app.domain.youtube.AnswerLocator
import com.reader.app.domain.youtube.CompositeVideoFrameSource
import com.reader.app.domain.youtube.FrameTimestampSampler
import com.reader.app.domain.youtube.QuestionWindowDetector
import com.reader.app.domain.youtube.StoryboardFrameSource
import com.reader.app.domain.youtube.StoryboardSpec
import com.reader.app.domain.youtube.TranscriptCue
import com.reader.app.domain.youtube.VideoFrameSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Mode 2 — math step / approach discussion. Now a real multi-turn chat:
 *
 *   - Each question + answer is appended as a pair of [ChatMessage]s
 *     and stays visible in the chat history.
 *   - Every new question is sent to the LLM along with **all previous
 *     Q&A pairs** in the session so follow-ups like "pichhle sawal ka
 *     jawab dijiye" or "us ke baad waala step samjhao" work.
 *   - The document text is loaded eagerly in `init`, but `ask()` re-
 *     loads it on the fly if a fast tap arrives before the init job has
 *     finished — the document is GUARANTEED to be in the prompt.
 *   - LLM output is markdown-stripped before display + TTS.
 *   - TTS is awaited Ready before the answer is spoken.
 *   - **Mic** (since v2): the composer also has a tap-to-toggle mic.
 *     Tapping mic stops any in-progress TTS, opens a Hindi-locked
 *     `SpeechRecognizer` session, and as soon as the transcript comes
 *     back the question is asked automatically — same flow as Mode 1.
 *     Partial results stream into [UiState.partialTranscript] so the
 *     screen can show a live "Sun raha hoon…" chip.
 */
/**
 * Hard cap on screenshots attached to a single multimodal doubt
 * payload. Sized for Gemini Flash's image-input cost: at WebView
 * capture's typical ~80 KB JPEG → ~107 KB base64 per frame, six
 * frames is ~640 KB on the wire — well below Gemini's per-call
 * limit and well above the 2–3 frame point where the model has
 * enough visual context to mirror the teacher's notation.
 *
 * Distributed across the segments returned by [AnswerLocator]: each
 * segment gets at most `ceil(MAX_TOTAL_FRAMES / segmentCount)`
 * picks, so a 5-segment retrieval produces 1 frame per segment, a
 * 2-segment retrieval produces 3 each, and a 1-segment fallback
 * gets all 6.
 */
private const val MAX_TOTAL_FRAMES: Int = 5

/**
 * Minimum distance (seconds) the pause moment must sit from either
 * edge of an answer-segment for the pause-anchored 5-frame sampler
 * to engage. Below this, the segment is too short to host a
 * 2-before / pause / 2-after layout, so we fall back to the standard
 * per-segment sampler.
 */
private const val PAUSE_CENTRED_MARGIN_SEC: Double = 3.0

class DiscussionViewModel(
    private val documentId: Long,
    private val docs: DocumentRepository,
    private val configs: ConfigRepository,
    private val llm: LlmRepository,
    private val ttsPrefs: TtsPreferencesRepository,
    val tts: TtsController,
    val stt: SpeechRecognizerController
) : ViewModel() {

    enum class MsgType { User, Assistant }

    data class ChatMessage(
        val type: MsgType,
        val text: String,
        val isStreaming: Boolean = false,
        /**
         * Timestamps (seconds, in playback order) of the video
         * screenshots that were attached to this assistant message
         * when the multimodal path produced it.
         *
         * Empty list ([]) means the answer came from the text-only
         * path — either the doc has no video, or the active model
         * doesn't support image input, or the player wasn't ready,
         * or every frame-capture strategy returned blank. The chat
         * row reads this and (when non-empty) renders a small
         * "Grounded on N video frames · mm:ss …" footer under the
         * message so the student / developer can VISUALLY confirm a
         * doubt was answered with screenshot grounding rather than
         * from transcript text alone.
         *
         * Always empty for [MsgType.User] messages.
         */
        val frameTimestampsSec: List<Double> = emptyList()
    )

    /**
     * Lifecycle of one tutoring turn.
     *
     *   Idle → (mic tapped)        → Capturing
     *        → (typed + Send)      → Asking
     *   Capturing → (transcript)   → Asking
     *   Asking    → (answer ready) → Speaking
     *   Speaking  → (TTS done)     → Idle
     *
     * Send button is disabled while Capturing / Asking; mic is disabled
     * while Asking / Speaking (TTS holds the audio focus).
     */
    enum class Phase { Idle, Capturing, Asking, Speaking }

    data class UiState(
        val title: String = "",
        val documentText: String = "",
        val query: String = "",
        val phase: Phase = Phase.Idle,
        val messages: List<ChatMessage> = emptyList(),
        /**
         * Live STT partial result. Drives the streaming "you" chat row
         * shown at the bottom of the chat while the mic is open.
         */
        val partialTranscript: String = "",
        val nowSpeakingIndex: Int? = null,
        /**
         * Char range inside the message at [nowSpeakingIndex] that the
         * TTS engine is pronouncing right now. Drives the one-word-at-a-
         * time highlight in the chat. `null` when nothing is being
         * spoken or before the first onRangeStart event arrives.
         */
        val nowSpokenRange: IntRange? = null,
        val error: String? = null,
        val ttsReady: Boolean = false,
        /**
         * Non-null when this document was imported from a YouTube URL.
         * Drives the screen's decision to render the video player above
         * the chat (and to pass timestamp context to the AI). `null`
         * for paste / file-pick docs — the screen falls back to the
         * existing chat-only layout.
         */
        val youtubeVideoId: String? = null,
        /**
         * Cached teaching-style profile for [youtubeVideoId], extracted
         * once on import via [ToneProfileExtractor]. Fed into the prompt
         * so the AI mimics the actual tutor's voice. Null when extraction
         * failed or hasn't completed yet — the prompt builder gracefully
         * falls back to a generic "match the source's tone" instruction.
         */
        val toneProfile: String? = null,
        /**
         * Latest playhead position pushed up from the video player
         * (`YouTubeVideoArea` calls [setVideoCurrentSec] from its
         * `onCurrentSecond` callback). Persisted in state so:
         *   1. it's stashed when [startCapture] fires (mic flow), and
         *      handed to [ask] after the transcript arrives, and
         *   2. it's available for the typed-message `ask()` path.
         * Always `0.0` for non-YouTube docs.
         */
        val videoCurrentSec: Double = 0.0,
        /**
         * Timestamp captured at the moment [startCapture] was called —
         * the actual "where the student was when they raised the doubt"
         * value. Stashed across the async STT round-trip so by the time
         * the transcript comes back and we run [ask], we still know the
         * pause moment even if the (now-stopped) video is reporting a
         * stale `videoCurrentSec`. Null for non-YouTube docs / non-mic
         * flows.
         */
        val pendingVideoSec: Double? = null,
        /**
         * True once the screen has reported that the YouTube IFrame
         * player itself is in an error state — embedding refused by
         * the owner (codes 150 / 152), video taken down (100), etc.
         *
         * When this is true we deliberately DO NOT pass a timestamp
         * to the prompt builder: the player never started, so any
         * `videoCurrentSec` we have is a stale 0.0 that would point
         * the AI at the wrong place. The prompt still goes through
         * the video-aware path (so the cached tone profile is
         * injected and the AI keeps mimicking the tutor's voice) — it
         * just falls back to "use the full transcript + the doubt"
         * instead of "use ±60 s around the paused moment". Student
         * can watch the actual video in the YouTube app (or in the
         * system PiP overlay) and ask doubts here; AI grounds its
         * answer on the cached caption transcript.
         */
        val videoUnplayable: Boolean = false,
        /**
         * Raw `playerStoryboardSpecRenderer.spec` string captured at
         * import time for YouTube docs. When present (non-null,
         * parseable), the multimodal frame pipeline wraps the
         * registered WebView frame source in a
         * [CompositeVideoFrameSource] that falls back per-frame to
         * `i.ytimg.com/sb/...` storyboard cells whenever the WebView
         * capture returned a mostly-black bitmap (hardware-protected
         * video surface). Null on pre-v8 docs / non-YouTube docs /
         * videos without storyboard data — the WebView path then
         * runs as before, no fallback.
         */
        val storyboardSpec: String? = null
    )

    private val _state          = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Frame source registered by the screen when the YouTube player
     * view is composed. Defaults to [VideoFrameSource.NoOp] so the
     * multimodal path is automatically skipped (and the existing
     * text-only path runs) when:
     *  - the doc has no video (text doc), or
     *  - the player view hasn't laid out yet, or
     *  - the screen has been disposed and the source was unregistered.
     *
     * Mutated only from the main thread (the screen calls
     * [setVideoFrameSource] from a `LaunchedEffect`); read from the
     * `viewModelScope` coroutine inside [ask]. The single-writer
     * convention plus the `@Volatile` keeps reads consistent without
     * needing extra synchronisation.
     */
    @Volatile
    private var videoFrameSource: VideoFrameSource = VideoFrameSource.NoOp
    private var lastMultimodalPausedAtSec: Double? = null
    private var lastMultimodalImages: List<com.reader.app.domain.model.ImageData>? = null

    init {
        viewModelScope.launch {
            val doc  = docs.get(documentId) ?: return@launch
            val text = docs.loadFullText(documentId)
            _state.update {
                it.copy(
                    title          = doc.title,
                    documentText   = text,
                    youtubeVideoId = doc.youtubeVideoId,
                    toneProfile    = doc.toneProfile,
                    storyboardSpec = doc.storyboardSpec
                )
            }
        }
        // Bridge the TTS engine's word range into UI state so the chat
        // can highlight the exact word currently being pronounced — same
        // mechanic as Reading mode.
        viewModelScope.launch {
            tts.currentWordRange.collect { range ->
                _state.update { it.copy(nowSpokenRange = range) }
            }
        }
        // Live STT partial results — drives the "Sun raha hoon…" chip.
        viewModelScope.launch {
            stt.partial.collect { p ->
                _state.update { it.copy(partialTranscript = p) }
            }
        }
    }

    fun ensureTtsReady() {
        viewModelScope.launch { applyTtsPrefs() }
    }

    private suspend fun applyTtsPrefs() {
        val prefs = ttsPrefs.get()
        if (tts.state.value is TtsController.State.Ready) {
            tts.setLanguage(prefs.languageTag)
            tts.setPitch(prefs.pitch)
            tts.setSpeechRate(prefs.speechRate)
            // ALWAYS set the voice — including null. Without this, picking
            // "System default" in Voice settings (voiceName = null) would
            // leave whatever voice was previously applied still active.
            tts.setVoice(prefs.voiceName)
            _state.update { it.copy(ttsReady = true) }
        } else {
            suspendCancellableCoroutine<Unit> { cont ->
                tts.init(
                    languageTag = prefs.languageTag,
                    pitch       = prefs.pitch,
                    speechRate  = prefs.speechRate,
                    voiceName   = prefs.voiceName
                ) {
                    if (cont.isActive) cont.resume(Unit) {}
                }
            }
            _state.update { it.copy(ttsReady = true) }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /**
     * Pushed up from the YouTube video player (~once per second from
     * `onCurrentSecond`). Stored in state so [ask] / [startCapture]
     * can read the latest playhead position without the screen having
     * to thread it through every call site.
     *
     * No-op (and unused) for non-YouTube docs — the screen never
     * renders the player in that case so this method is never called.
     */
    fun setVideoCurrentSec(sec: Double) {
        _state.update { it.copy(videoCurrentSec = sec) }
    }

    /**
     * Pushed up from the screen when the YouTube IFrame player
     * reports an error (or recovers). Drives the video-context branch
     * of [ask] so a non-functional player doesn't anchor the AI's
     * answer at the wrong (stale 0.0) timestamp.
     *
     * No-op for non-YouTube docs.
     */
    fun setVideoUnplayable(unplayable: Boolean) {
        _state.update { it.copy(videoUnplayable = unplayable) }
    }

    /**
     * Register / unregister the frame-capture source for the active
     * YouTube player.
     *
     * The screen calls this with a [com.reader.app.ui.video.WebViewFrameSource]
     * whenever the IFrame player view is ready, and with `null` when
     * the view is disposed. While a real source is registered AND the
     * Discussion-mode config is using the Gemini provider, [ask] will
     * sample a few frames from the question window and send them to
     * Gemini as `inline_data` parts so the AI sees what is actually
     * on the board / slide and can replicate the tutor's exact
     * notation, ordering, and visual layout.
     *
     * When this is null / [VideoFrameSource.NoOp], or when the active
     * provider is anything other than Gemini, [ask] silently falls
     * back to the existing text-only video-context path. The student
     * never sees an error from a missing / failed frame capture.
     */
    fun setVideoFrameSource(source: VideoFrameSource?) {
        videoFrameSource = source ?: VideoFrameSource.NoOp
    }

    /** Clears the entire chat and starts a fresh session. */
    fun newSession() {
        // Tear down any in-flight mic / TTS so the new session starts clean.
        if (_state.value.phase == Phase.Capturing) stt.cancel()
        tts.pause()
        _state.update {
            it.copy(
                messages          = emptyList(),
                nowSpeakingIndex  = null,
                nowSpokenRange    = null,
                error             = null,
                query             = "",
                partialTranscript = "",
                phase             = Phase.Idle
            )
        }
    }

    /* -------- Tap-to-toggle mic -------- */

    /**
     * Tap-to-toggle mic. Same UX contract as Reading mode:
     *   - Idle / Speaking → start capturing (Speaking is interrupted so
     *     the student doesn't have to wait for TTS to finish before
     *     asking a follow-up).
     *   - Capturing → finalise (engine emits onResults; transcript flows
     *     into [handleTranscript] which auto-asks).
     *   - Asking → ignored. The LLM call is in flight; UI hides the mic.
     */
    fun toggleMic() {
        when (_state.value.phase) {
            Phase.Capturing -> stt.stop()
            Phase.Asking    -> Unit
            Phase.Speaking,
            Phase.Idle      -> startCapture()
        }
    }

    private fun startCapture() {
        // Stop any current TTS so the mic doesn't pick up the engine's
        // own voice and so the student isn't fighting two sounds.
        tts.pause()
        // Snapshot the video timestamp NOW (before STT runs and possibly
        // takes a few seconds to produce a transcript). We re-attach
        // this stashed value to [handleTranscript] → [ask] so the AI
        // sees the moment the student actually paused on, not whatever
        // position the (now-paused) player is reporting after the round
        // trip.
        val s = _state.value
        val pendingSec = if (s.youtubeVideoId != null) s.videoCurrentSec else null
        _state.update {
            it.copy(
                phase             = Phase.Capturing,
                error             = null,
                partialTranscript = "",
                nowSpeakingIndex  = null,
                nowSpokenRange    = null,
                pendingVideoSec   = pendingSec
            )
        }
        // Hindi-locked recogniser — the app is Hindi-only per spec.
        stt.start(languageTag = "hi-IN") { transcript -> handleTranscript(transcript) }
    }

    private fun handleTranscript(transcript: String?) {
        when {
            transcript == null -> {
                val msg = (stt.state.value as? SpeechRecognizerController.State.Error)
                    ?.message ?: "Awaaz capture nahi ho saki — phir try karein."
                _state.update {
                    it.copy(
                        phase             = Phase.Idle,
                        error             = msg,
                        partialTranscript = "",
                        pendingVideoSec   = null
                    )
                }
            }
            transcript.isBlank() -> {
                _state.update {
                    it.copy(phase = Phase.Idle, partialTranscript = "", pendingVideoSec = null)
                }
            }
            else -> {
                // Drop the transcript into [query] and immediately fire
                // ask() so the user gets the same one-tap voice flow as
                // Mode 1. ask() owns the Idle → Asking transition; we
                // pre-clear partialTranscript so the live chip vanishes.
                val pendingSec = _state.value.pendingVideoSec
                _state.update {
                    it.copy(
                        query             = transcript,
                        phase             = Phase.Idle,
                        partialTranscript = "",
                        pendingVideoSec   = null
                    )
                }
                ask(videoSecOverride = pendingSec)
            }
        }
    }

    fun ask(videoSecOverride: Double? = null) {
        val s = _state.value
        if (s.query.isBlank() || s.phase == Phase.Asking) return
        val question = s.query.trim()
        // Resolve the timestamp the AI should anchor to. Mic flow passes
        // the [startCapture]-stash via [videoSecOverride]; typed flow
        // falls through to the live `videoCurrentSec` (the screen has
        // already paused the player by the time it called us, so this
        // is stable). When the player is in an error state
        // (`videoUnplayable`), we deliberately drop the timestamp
        // entirely — the player never started, the value would just
        // be a stale 0.0, and the prompt builder gracefully degrades
        // to the full-transcript path while still injecting the
        // tone profile.
        val pausedAtSec: Double? = when {
            s.youtubeVideoId == null -> null
            s.videoUnplayable        -> null
            videoSecOverride != null -> videoSecOverride
            else                     -> s.videoCurrentSec
        }

        // Optimistically append the user message + an empty assistant
        // placeholder so the chat reflects the new turn immediately.
        val userIdx      = addMessage(ChatMessage(MsgType.User, question))
        val assistantIdx = addMessage(ChatMessage(MsgType.Assistant, "", isStreaming = true))
        _state.update {
            it.copy(query = "", phase = Phase.Asking, error = null)
        }

        viewModelScope.launch {
            val cfg = configs.get(AppMode.Discussion)
            if (cfg == null || !cfg.isComplete()) {
                val errMsg = "Mode 2 ki settings configure karein. " +
                    "Settings → Mode 2 mein API key aur model name daalein."
                updateMessage(assistantIdx, errMsg, isStreaming = false)
                _state.update { it.copy(phase = Phase.Idle, error = errMsg) }
                return@launch
            }

            // Document load race-fix: if init hasn't populated documentText
            // yet (e.g. user hit Send the moment the screen opened), pull it
            // synchronously here so the prompt is never sent without the
            // document. This is what was making the LLM say "I don't have
            // your first question".
            var docText = _state.value.documentText
            if (docText.isBlank()) {
                docText = runCatching { docs.loadFullText(documentId) }.getOrNull().orEmpty()
                _state.update { it.copy(documentText = docText) }
            }

            // Make sure TTS is ready BEFORE we try to speak the answer.
            applyTtsPrefs()

            // Build multi-turn history from the chat so far. Drop the just-
            // appended User+empty-Assistant pair (assistantIdx) — it is the
            // current turn, not history.
            val history: List<PromptBuilder.Turn> = _state.value.messages
                .take(assistantIdx)                        // everything before this turn
                .let { extractTurns(it) }

            // For YouTube-backed docs we ALWAYS go through the video-
            // aware prompt builder so the cached tone profile + the
            // YouTube-specific directive (stay-in-video, mimic-tutor,
            // explain-approach) are honoured. The transcript window
            // around the paused moment is added when we have a real
            // timestamp; when the player is unplayable / hasn't
            // started, we just send the full transcript + question
            // and the prompt builder gracefully degrades. This is
            // what lets the student keep clearing doubts even when
            // YouTube refused to embed the video — they watch it in
            // the YouTube app / a PiP window, ask the doubt here,
            // and the AI grounds the answer on the cached caption
            // text.
            //
            // For YouTube docs running on the Gemini provider with a
            // real paused timestamp AND a registered frame source, we
            // first attempt the **multimodal** path: detect the
            // question's start/end via [QuestionWindowDetector],
            // sample 1-5 representative timestamps with
            // [FrameTimestampSampler], capture frames from the
            // IFrame player's WebView, and send them to Gemini as
            // `inline_data` parts so the AI literally sees what's on
            // the board. Any failure along that path (capture
            // returns empty, Gemini call fails, etc.) silently falls
            // back to the existing text-only video-context flow —
            // the student never sees a multimodal-specific error.
            //
            // For text docs the existing buildDiscussion call is
            // used — 1:1 with the previous behaviour.
            val multimodalReply: MultimodalAnswer? =
                if (s.youtubeVideoId != null && pausedAtSec != null &&
                    LlmProvider.supportsImageContent(cfg.provider, cfg.modelName)) {
                    val isSameAsLast = lastMultimodalPausedAtSec != null &&
                        kotlin.math.abs(lastMultimodalPausedAtSec!! - pausedAtSec) < 1.0

                    val anchor: Double = pausedAtSec
                    try {
                        val reply = tryMultimodalReply(
                            cfg              = cfg,
                            documentId       = documentId,
                            docText          = docText,
                            question         = question,
                            history          = history,
                            toneProfile      = _state.value.toneProfile,
                            pausedAtSec      = anchor,
                            useCachedFrames  = isSameAsLast
                        )
                        if (reply != null) {
                            lastMultimodalPausedAtSec = pausedAtSec
                        }
                        reply
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                } else null

            if (multimodalReply != null) {
                // Multimodal path produced an answer. Skip streaming
                // entirely — Gemini's response is already a single
                // string — and jump straight to the "speak it" stage
                // by tail-calling the same finalisation block. The
                // frame timestamps are forwarded so the chat row can
                // render its "Grounded on N video frames" visual-
                // confirmation footer underneath the answer text.
                onAnswerReady(
                    answer             = multimodalReply.text,
                    assistantIdx       = assistantIdx,
                    userIdx            = userIdx,
                    frameTimestampsSec = multimodalReply.frameTimestampsSec,
                )
                return@launch
            }

            val (system, user) =
                if (s.youtubeVideoId != null) {
                    val windowText = if (pausedAtSec != null) {
                        runCatching {
                            docs.loadCuesAround(documentId, pausedAtSec)
                        }.getOrNull().orEmpty()
                            .joinToString(separator = " ") { it.text }
                    } else ""
                    PromptBuilder.buildDiscussionWithVideoContext(
                        fullDocument     = docText,
                        history          = history,
                        userQuery        = question,
                        toneProfile      = _state.value.toneProfile,
                        transcriptWindow = windowText,
                        pausedAtSec      = pausedAtSec
                    )
                } else {
                    PromptBuilder.buildDiscussion(
                        fullDocument = docText,
                        history      = history,
                        userQuery    = question
                    )
                }

            // Stream the answer with UI-rate throttling. The previous code
            // updated the chat row on every single SSE token, which made
            // RichTextRenderer.render(accumulated) run O(N²) total times
            // for an N-token answer and stuttered around the 1000-token
            // mark. coalesceTokensForUi caps that at ~20 fps without
            // changing the streaming wire-protocol, and always flushes
            // the final accumulator on completion so the answer is never
            // truncated.
            var latestText = ""
            try {
                llm.askStreaming(cfg, system, user)
                    .coalesceTokensForUi()
                    .collect { snapshot ->
                        latestText = snapshot
                        updateMessage(assistantIdx, snapshot, isStreaming = true)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errSuffix = "[Error: ${e.message ?: "stream failed"}]"
                latestText = if (latestText.isEmpty()) errSuffix
                             else "$latestText\n$errSuffix"
            }

            val cleaned = latestText.trim()
            val answer = cleaned.ifBlank {
                "AI ne abhi response nahi diya — network ya API key check karein, " +
                    "phir dobara try karein."
            }
            onAnswerReady(answer = answer, assistantIdx = assistantIdx, userIdx = userIdx)
        }
    }

    /**
     * Result of a successful multimodal turn — the answer text plus
     * the playback timestamps of every screenshot that was attached
     * to the LLM call.
     *
     * Threaded back to the chat row via [setMessageFrameTimestamps]
     * so the assistant message can render its "Grounded on N video
     * frames · mm:ss" footer.
     */
    private data class MultimodalAnswer(
        val text: String,
        val frameTimestampsSec: List<Double>,
    )

    /**
     * Try the **multimodal** answer path: detect the question window
     * from the transcript, sample a few timestamps, capture frames
     * via the registered [VideoFrameSource], and call
     * [LlmRepository.askMultimodal] with the screenshots inline.
     *
     * Works on any provider whose model name passes
     * [LlmProvider.supportsImageContent] — Gemini (always), plus
     * vision-capable Groq + NIM models (`*-vision-preview`,
     * `*-vision-instruct`, LLaMA-4 Scout / Maverick, Pixtral,
     * Qwen2-VL, etc.). The caller pre-filters this on the provider +
     * model name pair, so this method assumes the upstream API can
     * actually accept images.
     *
     * Returns the answer string on success, or `null` when the path
     * isn't usable (no cues, no frames, frame source declined, the
     * provider returned blank, etc.). The caller then falls back to
     * the existing text-only streaming path.
     *
     * Throws on programmer errors only — every "expected" failure
     * (network blip, provider 4xx, frame capture timeout, …) is
     * caught at the call site and converted to `null` so the caller
     * can degrade silently. CancellationException is rethrown so
     * structured concurrency is respected.
     */
    private suspend fun tryMultimodalReply(
        cfg: com.reader.app.domain.model.ApiConfig,
        documentId: Long,
        docText: String,
        question: String,
        history: List<PromptBuilder.Turn>,
        toneProfile: String?,
        pausedAtSec: Double,
        useCachedFrames: Boolean = false,
    ): MultimodalAnswer? {
        // Build the effective frame source for THIS doubt by combining
        // whatever the screen registered (typically a WebViewFrameSource)
        // with a storyboard fallback if the doc carries a parseable
        // [DocumentEntity.storyboardSpec]. When neither leg is
        // available we end up with [VideoFrameSource.NoOp] and bail
        // immediately — same observable behaviour as before.
        val source = effectiveFrameSource()
        if (source === VideoFrameSource.NoOp) return null

        // Pull the FULL caption transcript once — both the answer
        // locator and the cue-aligned timestamp sampler need every
        // cue, not just the ±60 s slice.
        // Cancellation must propagate; non-cancellation failures
        // (e.g. DB closed during teardown) degrade to "no cues, fall
        // back to text-only path".
        val cues: List<TranscriptCue> = try {
            docs.loadCues(documentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        if (cues.isEmpty()) return null

        // ---- Step 1: Question-aware retrieval -----------------------------
        //
        // Ask the LLM to scan the WHOLE transcript and identify all
        // the places where the ANSWER for this question is actually
        // discussed. Could be at the pause moment, could be far
        // earlier (the concept being asked about), could be split
        // across multiple parts of the video.
        //
        // Cancellation propagates; any other failure (locator
        // unavailable, malformed JSON, empty result) → empty list,
        // and we fall through to the existing pause-anchored
        // window detector below. The student still gets an answer
        // either way.
        val locatedSegments: List<AnswerLocator.Segment> = try {
            AnswerLocator.locate(
                llm         = llm,
                config      = cfg,
                cues        = cues,
                pausedAtSec = pausedAtSec,
                question    = question,
                history     = history,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }

        // ---- Step 2: Resolve the segments to actually use -----------------
        //
        // Either the retrieved segments (preferred — semantic match)
        // or a fallback single-window from the legacy
        // QuestionWindowDetector anchored on the pause. The fallback
        // path matches the previous behaviour 1:1 when the locator
        // is unavailable.
        val segments: List<UsableSegment> = if (locatedSegments.isNotEmpty()) {
            locatedSegments.map { seg ->
                UsableSegment(
                    startSec = seg.startSec,
                    endSec   = seg.endSec,
                    reason   = seg.reason,
                    anchor   = pickAnchor(pausedAtSec, seg.startSec, seg.endSec),
                )
            }
        } else {
            val window = QuestionWindowDetector.detect(cues, pausedAtSec = pausedAtSec)
            if (window.endSec <= window.startSec) return null
            listOf(
                UsableSegment(
                    startSec = window.startSec,
                    endSec   = window.endSec,
                    reason   = "around the paused moment",
                    anchor   = pausedAtSec,
                )
            )
        }

        // ---- Step 3: Sample timestamps across all segments ----------------
        //
        // As per new requirements:
        // Situation A: If the student asks a question related to the paused timestamp
        // (i.e. pausedAtSec is within the located segments), we MUST capture exactly
        // AT the paused timestamp, plus 2 frames before it and 2 frames after it.
        //
        // Situation B: If not related to the paused timestamp (or fallback), we capture
        // the final moment (endSec) of top segments and backfill intermediate "last moments"
        // to strictly reach 5 frames.
        
        // "Aaspaas" - consider it related if the paused moment is within or very close (5s) to the segment.
        val isRelatedToPause = segments.any { pausedAtSec in (it.startSec - 5.0)..(it.endSec + 5.0) }

        val timestamps: List<Double> = if (isRelatedToPause) {
            FrameTimestampSampler.sampleAroundPause(
                cues = cues,
                pausedAtSec = pausedAtSec,
                docTotalSec = cues.lastOrNull()?.endSec ?: 0.0
            )
        } else {
            val sortedSegments = segments.sortedByDescending { seg ->
                if (pausedAtSec in seg.startSec..seg.endSec) 1 else 0 // Won't happen now, but safe
            }
            val topSegments = sortedSegments.take(MAX_TOTAL_FRAMES)
            
            val collectedTimestamps = mutableSetOf<Double>()
            
            // Mandatory: add the absolute end of every selected segment.
            for (seg in topSegments) {
                collectedTimestamps.add(seg.endSec)
            }
    
            // Backfill if we need more to reach 5
            val remaining = MAX_TOTAL_FRAMES - collectedTimestamps.size
            if (remaining > 0 && topSegments.isNotEmpty()) {
                val perSegmentAlloc = (remaining / topSegments.size).coerceAtLeast(1)
                for (seg in topSegments) {
                    if (collectedTimestamps.size >= MAX_TOTAL_FRAMES) break
                    val sampled = FrameTimestampSampler.sample(
                        cues       = cues,
                        startSec   = seg.startSec,
                        endSec     = seg.endSec,
                        anchorSec  = seg.anchor,
                        maxFrames  = perSegmentAlloc + 2
                    )
                    for (ts in sampled.reversed()) {
                        if (collectedTimestamps.size >= MAX_TOTAL_FRAMES) break
                        collectedTimestamps.add(ts)
                    }
                }
            }
    
            collectedTimestamps.toList().sorted()
        }

        // Deduplicate timestamps to prevent visually similar screenshots (at least 2.0s apart).
        // Prioritize timestamps closest to the paused moment.
        val distinctTimestamps = mutableListOf<Double>()
        for (ts in timestamps.sortedBy { kotlin.math.abs(it - pausedAtSec) }) {
            if (distinctTimestamps.none { kotlin.math.abs(it - ts) < 2.0 }) {
                distinctTimestamps.add(ts)
            }
        }
        val finalTimestamps = distinctTimestamps.sorted()

        if (finalTimestamps.isEmpty()) return null

        // ---- Step 4: Capture frames via the composite source --------------
        //
        // WebViewFrameSource handles the seek + settle + draw +
        // recycle pipeline; StoryboardFrameSource fills in
        // mostly-black frames per-cell. Cancellation propagates;
        // anything else degrades to text-only.
        val images = if (useCachedFrames && lastMultimodalImages != null) {
            lastMultimodalImages!!
        } else {
            try {
                val captured = source.captureFrames(finalTimestamps)
                if (captured.isNotEmpty()) {
                    lastMultimodalImages = captured
                }
                captured
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }
        if (images.isEmpty()) return null

        // ---- Step 5: Build the multimodal prompt --------------------------
        //
        // Two routes — when we have retrieved segments, use the
        // multi-segment prompt builder so the AI sees explicit labels
        // for each disjoint range. When we fell back to the single-
        // window detector, use the simpler video-frames builder so
        // the existing single-window prompt shape remains the path
        // of record (and any prompt-engineering work the team
        // already did stays in effect).
        val frameTs = images.mapNotNull { it.captionTimestampSec }
        val (system, user) = if (locatedSegments.isNotEmpty()) {
            val segmentsForPrompt = segments.map { seg ->
                PromptBuilder.AnswerSegment(
                    startSec = seg.startSec,
                    endSec   = seg.endSec,
                    reason   = seg.reason,
                    text     = cues
                        .asSequence()
                        .filter { it.startSec >= seg.startSec && it.startSec < seg.endSec }
                        .joinToString(separator = " ") { it.text },
                )
            }
            PromptBuilder.buildDiscussionWithAnswerSegments(
                fullDocument        = docText,
                history             = history,
                userQuery           = question,
                toneProfile         = toneProfile,
                pausedAtSec         = pausedAtSec,
                segments            = segmentsForPrompt,
                frameTimestampsSec  = frameTs,
            )
        } else {
            // Single-window fallback path (unchanged from before).
            val seg = segments.first()
            val windowText = cues
                .asSequence()
                .filter { it.startSec >= seg.startSec && it.startSec < seg.endSec }
                .joinToString(separator = " ") { it.text }
            PromptBuilder.buildDiscussionWithVideoFrames(
                fullDocument        = docText,
                history             = history,
                userQuery           = question,
                toneProfile         = toneProfile,
                transcriptWindow    = windowText,
                pausedAtSec         = pausedAtSec,
                frameTimestampsSec  = frameTs,
                windowStartSec      = seg.startSec,
                windowEndSec        = seg.endSec,
            )
        }

        // ---- Step 6: Multimodal answer call ------------------------------
        val result = llm.askMultimodal(
            config       = cfg,
            systemPrompt = system,
            userPrompt   = user,
            images       = images,
        )
        val text = result.getOrNull()?.trim().orEmpty()
        if (text.isBlank()) return null
        return MultimodalAnswer(text = text, frameTimestampsSec = frameTs)
    }

    /**
     * Internal-only segment shape used while assembling the
     * multimodal payload. Slimmer than [PromptBuilder.AnswerSegment]
     * because we don't need the verbatim text yet — that's joined in
     * a single later pass once we know the segments are final.
     */
    private data class UsableSegment(
        val startSec: Double,
        val endSec: Double,
        val reason: String,
        /** Where the FrameTimestampSampler should focus its picks. */
        val anchor: Double,
    )

    /**
     * Decide where the frame sampler should cluster its picks for a
     * given segment. If the student's pause moment falls inside the
     * segment, anchor on the pause (most likely to capture the
     * specific frame the student was looking at when they got stuck).
     * Otherwise anchor on the midpoint of the segment.
     */
    private fun pickAnchor(pausedAtSec: Double, segStart: Double, segEnd: Double): Double {
        return if (pausedAtSec in segStart..segEnd) pausedAtSec
        else (segStart + segEnd) / 2.0
    }

    /**
     * Predicate for the pause-anchored 5-frame mode.
     *
     * Returns true when [pausedAtSec] falls inside [segment] AND
     * the pause is not at the extreme start / end of the segment —
     * i.e. the student really did pause mid-explanation, where the
     * answer to their doubt is right at the paused moment rather
     * than spread across a wider span.
     *
     * "Reasonably centred" = the pause is at least
     * [PAUSE_CENTRED_MARGIN_SEC] (default 3 s) from either edge of
     * the segment. Below that the segment is too short to do the
     * 2-before / 2-after layout justice anyway, so we fall back to
     * the standard per-segment sampler which handles short windows
     * gracefully.
     */
    private fun isPauseCentredInSegment(
        pausedAtSec: Double,
        segment: UsableSegment,
    ): Boolean {
        if (pausedAtSec < segment.startSec || pausedAtSec > segment.endSec) return false
        val fromStart = pausedAtSec - segment.startSec
        val fromEnd   = segment.endSec - pausedAtSec
        return fromStart >= PAUSE_CENTRED_MARGIN_SEC && fromEnd >= PAUSE_CENTRED_MARGIN_SEC
    }

    /**
     * Resolve the frame source to use for the current doubt.
     *
     * Three cases:
     *  1. **Both** — screen has registered a WebView source AND the
     *     doc has a parseable storyboard spec → wrap them in a
     *     [CompositeVideoFrameSource] (WebView primary, storyboard
     *     fallback per-frame on mostly-black bitmaps).
     *  2. **WebView only** — the doc has no storyboard spec (pre-v8
     *     docs, very rare videos) → return the WebView source as-is.
     *     Existing behaviour, no fallback engaged.
     *  3. **Storyboard only** — the screen never registered a
     *     WebView source (e.g. player view error / disposed) but we
     *     have a usable spec → return a bare [StoryboardFrameSource].
     *     The student still gets visual context via storyboards even
     *     when the IFrame player itself is non-functional.
     *  4. **Neither** — return [VideoFrameSource.NoOp]. Caller skips
     *     the multimodal path entirely.
     *
     * Built fresh on every call (cheap — just reads two fields and
     * possibly parses the spec once) so changes to either side are
     * picked up immediately.
     */
    private fun effectiveFrameSource(): VideoFrameSource {
        val webView = videoFrameSource.takeIf { it !== VideoFrameSource.NoOp }
        val spec    = StoryboardSpec.parse(_state.value.storyboardSpec)
        val storyboard = spec?.let {
            StoryboardFrameSource(it, ServiceLocator.youTubeStoryboardClient)
        }
        return when {
            webView != null && storyboard != null ->
                CompositeVideoFrameSource(primary = webView, fallback = storyboard)
            webView != null    -> webView
            storyboard != null -> storyboard
            else               -> VideoFrameSource.NoOp
        }
    }

    /**
     * Common finalisation: drop the answer into the assistant slot,
     * flip the phase to Speaking, fire TTS, and clear the streaming
     * flag on the user row.
     *
     * Used by both the text-streaming path and the multimodal path so
     * the post-answer UX is identical — nothing about "the AI just
     * answered" should differ between the two flows from the
     * student's POV.
     *
     * @param frameTimestampsSec timestamps (seconds) of every video
     *   screenshot attached when the answer came from the multimodal
     *   path. Empty list means the text-only path produced this
     *   answer; the chat row's "Grounded on N video frames" footer
     *   stays hidden.
     */
    private fun onAnswerReady(
        answer: String,
        assistantIdx: Int,
        userIdx: Int,
        frameTimestampsSec: List<Double> = emptyList(),
    ) {
        // Store the answer with markdown intact — the chat row
        // renders it via RichTextRenderer.
        updateMessage(assistantIdx, answer, isStreaming = false)
        if (frameTimestampsSec.isNotEmpty()) {
            // Visual confirmation indicator on the message row.
            // Skipped for empty lists so we don't churn the message
            // copy unnecessarily on the much more common text-only
            // path.
            setMessageFrameTimestamps(assistantIdx, frameTimestampsSec)
        }
        _state.update {
            it.copy(
                phase            = Phase.Speaking,
                nowSpeakingIndex = assistantIdx,
                nowSpokenRange   = null
            )
        }

        // Speak the answer aloud. Strip markdown only at the TTS
        // boundary so the engine doesn't pronounce "asterisk
        // asterisk". The stripped char stream is what
        // `RichTextRenderer.render(answer).plain` also produces, so
        // the highlight char ranges TtsController emits line up
        // with the displayed AnnotatedString.
        //
        // The completion callback only flips back to Idle if we're
        // STILL in Speaking phase — otherwise the user has already
        // moved on (e.g. tapped mic to ask a follow-up which set
        // phase = Capturing) and we mustn't yank that out from
        // under them.
        tts.speakOneShot(MarkdownStripper.strip(answer)) {
            _state.update {
                if (it.phase == Phase.Speaking) {
                    it.copy(
                        phase            = Phase.Idle,
                        nowSpeakingIndex = null,
                        nowSpokenRange   = null
                    )
                } else it
            }
        }

        // Touch userIdx so it can't be marked unused — and also clear
        // a possible streaming flag on it if anything went wrong.
        if (userIdx in _state.value.messages.indices) {
            val m = _state.value.messages[userIdx]
            if (m.isStreaming) updateMessage(userIdx, m.text, isStreaming = false)
        }
    }

    fun replayLastAnswer() {
        val last = _state.value.messages.lastOrNull {
            it.type == MsgType.Assistant && !it.isStreaming
        } ?: return
        if (last.text.isBlank()) return
        viewModelScope.launch {
            applyTtsPrefs()
            val idx = _state.value.messages.lastIndexOf(last)
            _state.update {
                it.copy(
                    phase            = Phase.Speaking,
                    nowSpeakingIndex = idx,
                    nowSpokenRange   = null
                )
            }
            tts.speakOneShot(MarkdownStripper.strip(last.text)) {
                _state.update {
                    if (it.phase == Phase.Speaking) {
                        it.copy(
                            phase            = Phase.Idle,
                            nowSpeakingIndex = null,
                            nowSpokenRange   = null
                        )
                    } else it
                }
            }
        }
    }

    fun stopSpeaking() {
        tts.pause()
        _state.update {
            it.copy(
                phase            = Phase.Idle,
                nowSpeakingIndex = null,
                nowSpokenRange   = null
            )
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    /* ---------- helpers ---------- */

    private fun extractTurns(messages: List<ChatMessage>): List<PromptBuilder.Turn> {
        val out = ArrayList<PromptBuilder.Turn>()
        var pendingQ: String? = null
        for (m in messages) {
            when (m.type) {
                MsgType.User -> pendingQ = m.text
                MsgType.Assistant -> {
                    val q = pendingQ
                    if (q != null && m.text.isNotBlank() && !m.isStreaming) {
                        out += PromptBuilder.Turn(question = q, answer = m.text)
                    }
                    pendingQ = null
                }
            }
        }
        return out
    }

    private fun addMessage(msg: ChatMessage): Int {
        val newList = _state.value.messages + msg
        _state.update { it.copy(messages = newList) }
        return newList.size - 1
    }

    private fun updateMessage(index: Int, text: String, isStreaming: Boolean) {
        _state.update { s ->
            val msgs = s.messages.toMutableList()
            if (index in msgs.indices) {
                msgs[index] = msgs[index].copy(text = text, isStreaming = isStreaming)
            }
            s.copy(messages = msgs)
        }
    }

    /**
     * Attach (or clear) the frame-timestamp metadata on an existing
     * assistant message without disturbing its [ChatMessage.text] or
     * [ChatMessage.isStreaming] state.
     *
     * Called once at the end of a multimodal turn, after [onAnswerReady]
     * has dropped the final answer text into the slot. The chat row's
     * footer composable reads this list to render the small
     * `Grounded on N video frames · mm:ss · …` indicator that
     * visually confirms the answer was grounded on screenshots, not
     * just the transcript.
     *
     * No-op when [index] is out of range or the message at that
     * position is a user message (defensive — multimodal metadata
     * should never end up on a user row, but the check keeps the
     * invariant explicit).
     */
    private fun setMessageFrameTimestamps(index: Int, frameTimestampsSec: List<Double>) {
        _state.update { s ->
            val msgs = s.messages.toMutableList()
            if (index in msgs.indices && msgs[index].type == MsgType.Assistant) {
                msgs[index] = msgs[index].copy(frameTimestampsSec = frameTimestampsSec)
            }
            s.copy(messages = msgs)
        }
    }

    override fun onCleared() {
        stt.release()
        tts.shutdown()
        super.onCleared()
    }

    companion object {
        fun factory(documentId: Long, appContext: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = appContext.applicationContext
                return DiscussionViewModel(
                    documentId = documentId,
                    docs       = ServiceLocator.documentRepository,
                    configs    = ServiceLocator.configRepository,
                    llm        = ServiceLocator.llmRepository,
                    ttsPrefs   = ServiceLocator.ttsPreferencesRepository,
                    tts        = TtsController(app),
                    stt        = SpeechRecognizerController(app)
                ) as T
            }
        }
    }
}
