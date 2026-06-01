package com.reader.app.ui.screens.upload

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.repository.ConfigRepository
import com.reader.app.data.repository.DocumentRepository
import com.reader.app.data.repository.LlmRepository
import com.reader.app.di.ServiceLocator
import com.reader.app.di.SharedYouTubeUrlBus
import com.reader.app.domain.chunk.TextDocumentValidator
import com.reader.app.domain.model.AppMode
import com.reader.app.domain.text.DocumentExtractor
import com.reader.app.domain.youtube.ToneProfileExtractor
import com.reader.app.domain.youtube.TranscriptCue
import com.reader.app.domain.youtube.YouTubeTranscriptFetcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Upload screen.
 *
 * Has two import paths:
 *
 *  - **Plain text / file pick.** Goes through [DocumentRepository.import]
 *    unchanged: validate → chunk → persist as a regular text document.
 *
 *  - **YouTube URL.** [fetchFromYouTube] hits
 *    [YouTubeTranscriptFetcher] which now returns BOTH a flat
 *    transcript (for the editor + Reading-mode chunking) AND a
 *    structured list of [TranscriptCue] with timestamps. We stash the
 *    `videoId` + `cues` in [UiState.pendingVideoId] / [UiState.pendingCues].
 *    On [import], if those are set we route through
 *    [DocumentRepository.importYoutube] instead — which persists the
 *    video id on the document AND saves every cue with its
 *    `(startSec, durSec)` so Discussion mode can sync the AI to the
 *    exact moment in the video the student paused at. Right after
 *    persistence, we kick off [ToneProfileExtractor] in the background
 *    (using the user's existing Discussion-mode BYOK config) to derive
 *    a 1–2 paragraph teaching-style profile from the transcript and
 *    cache it on the document. Done as a fire-and-forget coroutine on
 *    [NonCancellable] so the user can navigate away from this screen
 *    without aborting the call — the result lands in the DB whenever
 *    the LLM responds.
 *
 * The pending video metadata is **cleared** when the user picks a file
 * (a file pick supersedes any earlier YouTube fetch) so we never end
 * up persisting cues that don't match the actual document text the
 * user is uploading.
 */
class UploadViewModel(
    private val docs: DocumentRepository,
    private val configs: ConfigRepository,
    private val llm: LlmRepository
) : ViewModel() {

    private val toneExtractor = ToneProfileExtractor(llm)

    /**
     * Set true by [init] when a YouTube URL came in via the share-
     * intent bus. The fetch path then auto-fires [import] as soon as
     * the transcript fetch succeeds, so a "Share from YouTube"
     * lands the user directly in Discussion mode without ever having
     * to tap Save. Reset to false the first time we observe it
     * (one-shot), so a manual fetch on the same screen later doesn't
     * also auto-import.
     */
    private var autoImportRequested: Boolean = false

    data class UiState(
        val title: String = "",
        val rawText: String = "",
        val isImporting: Boolean = false,
        val isLoadingFile: Boolean = false,
        val isFetchingYouTube: Boolean = false,
        val youTubeUrl: String = "",
        val youTubeStatus: String? = null,
        val createdId: Long? = null,
        /**
         * True when [createdId] points at a YouTube-imported document.
         * Lets the upload-flow caller route the user directly to
         * Discussion mode (where the video player + chat live)
         * instead of Reading mode — for plain-text docs we still
         * default to Reading.
         */
        val createdAsVideoDoc: Boolean = false,
        val error: String? = null,
        /**
         * 11-char video id captured by the most recent successful
         * [fetchFromYouTube] call. Drives the YouTube branch of
         * [import] — null means "treat as plain text".
         */
        val pendingVideoId: String? = null,
        /**
         * Per-cue captions captured alongside [pendingVideoId]. Empty
         * when YouTube returned title-only (no captions available),
         * which still lets the user import the doc as a video — the
         * Discussion screen renders the player but the AI prompt
         * builder gracefully falls back to flat-document context.
         */
        val pendingCues: List<TranscriptCue> = emptyList(),
        /**
         * Raw `playerStoryboardSpecRenderer.spec` string captured
         * alongside [pendingVideoId]. Persisted on import; drives the
         * storyboard fallback in the multimodal video-frame doubt
         * pipeline (frames pulled at doubt-time from
         * `i.ytimg.com/sb/...` whenever the WebView capture path
         * returns mostly-black bitmaps). Null when YouTube didn't
         * expose a storyboard for this video (extremely rare) or
         * when the InnerTube call fell back to title-only without a
         * player response.
         */
        val pendingStoryboardSpec: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Share-intent handoff: if MainActivity dropped a URL into
        // SharedYouTubeUrlBus before navigating us here, prefill the
        // YouTube URL field and immediately kick off the existing
        // fetch flow. This makes "Share from YouTube → Reader" a
        // one-tap import; the user lands on the Upload screen and
        // sees "Transcript fetch ho raha hai…" without having to
        // paste anything.
        //
        // We consume (i.e. clear) the bus value so a later manual
        // visit to Upload doesn't accidentally re-prefill an old
        // share. Per-VM-instance, runs exactly once.
        val pendingShareUrl = SharedYouTubeUrlBus.consume()
        if (!pendingShareUrl.isNullOrBlank()) {
            autoImportRequested = true
            _state.update { it.copy(youTubeUrl = pendingShareUrl) }
            fetchFromYouTube()
        }
    }

    fun setTitle(t: String) = _state.update { it.copy(title = t) }
    fun setRawText(t: String) = _state.update { it.copy(rawText = t) }
    fun setYouTubeUrl(u: String) = _state.update { it.copy(youTubeUrl = u, error = null) }

    /**
     * Decode any supported document (.txt, .md, .pdf, .pptx, .docx, …) via
     * [DocumentExtractor], then run the result through
     * [TextDocumentValidator] before populating the editor.
     *
     * A file pick OVERRIDES any earlier YouTube fetch — we clear
     * [UiState.pendingVideoId] / [UiState.pendingCues] so the new
     * (text-only) document doesn't accidentally end up persisted with
     * the previous video's cues.
     */
    fun loadFromUri(context: Context, uri: Uri) {
        _state.update {
            it.copy(
                isLoadingFile  = true,
                error          = null,
                pendingVideoId = null,
                pendingCues    = emptyList(),
                pendingStoryboardSpec = null,
                youTubeStatus  = null
            )
        }
        viewModelScope.launch {
            when (val r = DocumentExtractor.extract(context, uri)) {
                is DocumentExtractor.Result.Ok -> {
                    when (val v = TextDocumentValidator.validate(r.text)) {
                        is TextDocumentValidator.Result.Ok -> {
                            _state.update { ui ->
                                ui.copy(
                                    isLoadingFile = false,
                                    rawText       = r.text,
                                    title         = ui.title.ifBlank { r.suggestedTitle.orEmpty() },
                                    error         = null
                                )
                            }
                        }
                        is TextDocumentValidator.Result.Reject -> {
                            _state.update { it.copy(isLoadingFile = false, error = v.reason) }
                        }
                    }
                }
                is DocumentExtractor.Result.Reject -> {
                    _state.update { it.copy(isLoadingFile = false, error = r.reason) }
                }
            }
        }
    }

    /**
     * Fetch transcript / title for a YouTube URL and put the result into
     * the editor. Surfaces the same screen state as a file pick.
     *
     * Also stashes the resolved `videoId` and the per-cue transcript in
     * [UiState.pendingVideoId] / [UiState.pendingCues] so [import] can
     * persist them — the video player + timestamp-window AI both depend
     * on those being available at run time.
     */
    fun fetchFromYouTube() {
        val url = _state.value.youTubeUrl.trim()
        if (url.isBlank()) {
            _state.update { it.copy(error = "YouTube URL daalein.") }
            return
        }
        _state.update {
            it.copy(
                isFetchingYouTube = true,
                youTubeStatus     = "Transcript fetch ho raha hai…",
                error             = null
            )
        }
        viewModelScope.launch {
            when (val r = YouTubeTranscriptFetcher.fetch(url)) {
                is YouTubeTranscriptFetcher.Result.Ok -> {
                    val combined = buildString {
                        if (r.title.isNotBlank()) {
                            append(r.title)
                            append(". ")
                        }
                        append(r.transcript)
                    }.trim()

                    val statusMsg = when (r.source) {
                        YouTubeTranscriptFetcher.Source.Transcript ->
                            "Hindi captions / subtitles mil gaye " +
                                "(${r.language.ifBlank { "hi" }}). Video player " +
                                "Discussion mein dikhega. Edit karke save karein."
                        YouTubeTranscriptFetcher.Source.TitleOnly ->
                            "Iss video par Hindi captions / subtitles available " +
                                "nahi the. Video player phir bhi dikhega — neeche " +
                                "manually content add kar sakte hain."
                    }

                    _state.update { ui ->
                        ui.copy(
                            isFetchingYouTube = false,
                            youTubeStatus     = statusMsg,
                            rawText           = combined,
                            title             = ui.title.ifBlank { r.title },
                            error             = null,
                            pendingVideoId    = r.videoId,
                            pendingCues       = r.cues,
                            pendingStoryboardSpec = r.storyboardSpec
                        )
                    }
                    // Share-intent path: the user explicitly chose to
                    // hand this URL to us; they want the doc on screen
                    // ASAP, not parked in an editor waiting for a Save
                    // tap. Auto-fire import() now that we have a
                    // title + transcript, then reset the flag so a
                    // later manual fetch doesn't also auto-import.
                    if (autoImportRequested) {
                        autoImportRequested = false
                        import()
                    }
                }
                is YouTubeTranscriptFetcher.Result.Reject -> {
                    _state.update {
                        it.copy(
                            isFetchingYouTube = false,
                            youTubeStatus     = null,
                            error             = r.reason
                        )
                    }
                }
            }
        }
    }

    fun import() {
        val s = _state.value
        if (s.title.isBlank() || s.rawText.isBlank()) {
            _state.update { it.copy(error = "Title aur text dono zaroori hain.") }
            return
        }
        _state.update { it.copy(isImporting = true, error = null) }
        viewModelScope.launch {
            val title = s.title.trim()
            val rawText = s.rawText
            val videoId = s.pendingVideoId
            val cues    = s.pendingCues
            val storyboardSpec = s.pendingStoryboardSpec
            val transcriptForToneExtraction = rawText

            val result = runCatching {
                if (videoId != null) {
                    docs.importYoutube(
                        title          = title,
                        rawText        = rawText,
                        videoId        = videoId,
                        cues           = cues,
                        storyboardSpec = storyboardSpec
                    )
                } else {
                    docs.import(title = title, rawText = rawText)
                }
            }
            result
                .onSuccess { id ->
                    _state.update {
                        it.copy(
                            isImporting       = false,
                            createdId         = id,
                            createdAsVideoDoc = (videoId != null)
                        )
                    }
                    if (videoId != null) {
                        kickOffToneExtraction(documentId = id, transcript = transcriptForToneExtraction)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(isImporting = false, error = e.message ?: "Import failed")
                    }
                }
        }
    }

    /**
     * Fire-and-forget tone-profile extraction.
     *
     * Runs on a [NonCancellable] block so that navigating away from the
     * Upload screen (which cancels [viewModelScope]) does NOT abort the
     * LLM call mid-way. The profile lands in the DB whenever the LLM
     * actually responds; if anything goes wrong we silently leave the
     * column null and the prompt builder falls back to its generic
     * style instruction.
     *
     * No UI surface here — the user already saw "import done", the
     * profile is a backstage enrichment.
     */
    private fun kickOffToneExtraction(documentId: Long, transcript: String) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                val cfg = runCatching { configs.get(AppMode.Discussion) }.getOrNull()
                if (cfg == null || !cfg.isComplete()) return@withContext
                val profile = runCatching { toneExtractor.extract(transcript, cfg) }
                    .getOrNull()
                if (!profile.isNullOrBlank()) {
                    runCatching { docs.saveToneProfile(documentId, profile) }
                }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
    fun consumeYouTubeStatus() = _state.update { it.copy(youTubeStatus = null) }

    companion object {
        /** MIME types accepted by the file picker. */
        val ACCEPTED_MIMES: Array<String> = DocumentExtractor.ACCEPTED_MIMES

        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                UploadViewModel(
                    docs    = ServiceLocator.documentRepository,
                    configs = ServiceLocator.configRepository,
                    llm     = ServiceLocator.llmRepository
                ) as T
        }
    }
}
