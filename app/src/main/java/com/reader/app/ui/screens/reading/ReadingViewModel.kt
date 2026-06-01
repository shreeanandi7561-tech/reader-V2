package com.reader.app.ui.screens.reading

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
import com.reader.app.domain.rag.PromptBuilder
import com.reader.app.domain.stt.SpeechRecognizerController
import com.reader.app.domain.text.MarkdownStripper
import com.reader.app.domain.tts.TtsController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reading-mode flow. The screen is a chat-style timeline:
 *
 *   - Each sentence of the document is appended as a `Doc` message and
 *     spoken aloud one at a time. The word being spoken is highlighted
 *     in real time via [TtsController.currentWordRange].
 *   - When the student taps the mic, reading pauses, the question is
 *     transcribed and added as a `User` message, the LLM answers
 *     (streaming) into an `Assistant` message, the answer is read
 *     aloud, and reading resumes from the saved cursor.
 *   - LLM answers are passed through [MarkdownStripper] before display
 *     and TTS so the engine never literally pronounces "asterisk".
 */
class ReadingViewModel(
    private val documentId: Long,
    private val docs: DocumentRepository,
    private val configs: ConfigRepository,
    private val llm: LlmRepository,
    private val ttsPrefs: TtsPreferencesRepository,
    val tts: TtsController,
    val stt: SpeechRecognizerController
) : ViewModel() {

    enum class MsgType { Doc, User, Assistant }

    data class ChatMessage(
        val type: MsgType,
        val text: String,
        val isStreaming: Boolean = false
    )

    enum class Phase { Idle, Reading, Capturing, Thinking, Speaking }

    data class UiState(
        val title: String = "",                 // kept for back-stack only, not displayed
        val messages: List<ChatMessage> = emptyList(),
        val phase: Phase = Phase.Idle,
        val partialTranscript: String = "",
        val cursorIndex: Int = 0,               // current sentence index in the document
        val totalSentences: Int = 0,
        val nowSpeakingIndex: Int? = null,      // index in [messages] of the message being spoken
        val nowSpokenRange: IntRange? = null,   // char range inside that message (for word-level highlight)
        val ttsReady: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Sentence-level chunks loaded from Room. */
    private var sentences: List<String> = emptyList()

    /** Full flattened document text — used as Reference A in the prompt. */
    private var fullDocText: String = ""

    private var readingJob: Job? = null

    init {
        viewModelScope.launch { loadDocument() }
        viewModelScope.launch {
            stt.partial.collect { p -> _state.update { it.copy(partialTranscript = p) } }
        }
        // Bridge the TTS engine's word range into UI state so the chat can
        // highlight the exact word currently being pronounced.
        viewModelScope.launch {
            tts.currentWordRange.collect { range ->
                _state.update { it.copy(nowSpokenRange = range) }
            }
        }
    }

    private suspend fun loadDocument() {
        val doc = docs.get(documentId) ?: return
        sentences = docs.loadChunks(documentId)
        fullDocText = sentences.joinToString(separator = " ")
        _state.update {
            it.copy(
                title          = doc.title,
                cursorIndex    = doc.lastIndex.coerceIn(0, sentences.size),
                totalSentences = sentences.size
            )
        }
    }

    /* -------- TTS readiness -------- */

    fun ensureTtsReady(@Suppress("UNUSED_PARAMETER") ctx: Context) {
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
            // leave whatever voice was previously applied still active on
            // this TtsController instance.
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

    /* -------- Reading playback -------- */

    fun togglePlayPause() {
        when (_state.value.phase) {
            Phase.Reading -> pauseReading()
            Phase.Idle    -> startReading()
            else          -> Unit
        }
    }

    /** Skip the current sentence and restart at the next one. */
    fun goForward() {
        val target = (_state.value.cursorIndex + 1).coerceAtMost(sentences.size)
        moveCursor(target)
    }

    /** Step back to the previous sentence and re-read it. */
    fun goBack() {
        val target = (_state.value.cursorIndex - 1).coerceAtLeast(0)
        moveCursor(target)
    }

    /** Clear the chat and restart from the very beginning of the document. */
    fun reset() {
        readingJob?.cancel()
        readingJob = null
        tts.pause()
        _state.update {
            it.copy(
                messages         = emptyList(),
                cursorIndex      = 0,
                phase            = Phase.Idle,
                nowSpeakingIndex = null,
                nowSpokenRange   = null,
                error            = null
            )
        }
        viewModelScope.launch { docs.saveProgress(documentId, 0) }
        startReading()
    }

    private fun moveCursor(targetIndex: Int) {
        readingJob?.cancel()
        readingJob = null
        tts.pause()
        _state.update {
            it.copy(
                cursorIndex      = targetIndex.coerceIn(0, sentences.size),
                phase            = Phase.Idle,
                nowSpeakingIndex = null,
                nowSpokenRange   = null,
                error            = null
            )
        }
        viewModelScope.launch { docs.saveProgress(documentId, _state.value.cursorIndex) }
        if (targetIndex < sentences.size) startReading()
    }

    private fun startReading() {
        if (sentences.isEmpty()) return
        val startIdx = _state.value.cursorIndex
        if (startIdx >= sentences.size) return

        _state.update { it.copy(phase = Phase.Reading, error = null) }

        readingJob = viewModelScope.launch {
            applyTtsPrefs()

            // Avoid duplicating the just-paused sentence in the chat.
            val lastDoc = _state.value.messages.lastOrNull { it.type == MsgType.Doc }
            val firstSentence = sentences.getOrNull(startIdx)
            var resumeReuse = (lastDoc != null && lastDoc.text == firstSentence)

            for (i in startIdx until sentences.size) {
                if (_state.value.phase != Phase.Reading) break
                val text = sentences[i]

                val msgIdx = if (resumeReuse) {
                    resumeReuse = false
                    _state.value.messages.indexOfLast {
                        it.type == MsgType.Doc && it.text == text
                    }
                } else {
                    addMessage(ChatMessage(MsgType.Doc, text))
                }

                _state.update { it.copy(cursorIndex = i, nowSpeakingIndex = msgIdx) }
                val spoken = speakAndWait(text)
                if (!spoken) break

                val nextIdx = i + 1
                _state.update {
                    it.copy(
                        cursorIndex      = nextIdx,
                        nowSpeakingIndex = null,
                        nowSpokenRange   = null
                    )
                }
                docs.saveProgress(documentId, nextIdx)
            }
            if (_state.value.phase == Phase.Reading) {
                _state.update {
                    it.copy(
                        phase            = Phase.Idle,
                        nowSpeakingIndex = null,
                        nowSpokenRange   = null
                    )
                }
            }
        }
    }

    private fun pauseReading() {
        readingJob?.cancel()
        readingJob = null
        tts.pause()
        _state.update {
            it.copy(
                phase            = Phase.Idle,
                nowSpeakingIndex = null,
                nowSpokenRange   = null
            )
        }
    }

    private suspend fun speakAndWait(text: String): Boolean {
        applyTtsPrefs()
        return try {
            suspendCancellableCoroutine<Boolean> { cont ->
                tts.speakOneShot(text) {
                    if (cont.isActive) cont.resume(true) {}
                }
                cont.invokeOnCancellation { tts.pause() }
            }
        } catch (_: Exception) {
            false
        }
    }

    /* -------- Tap-to-toggle mic -------- */

    fun toggleMic() {
        when (_state.value.phase) {
            Phase.Capturing -> stt.stop()
            Phase.Thinking,
            Phase.Speaking  -> Unit
            Phase.Reading,
            Phase.Idle      -> startCapture()
        }
    }

    private fun startCapture() {
        pauseReading()
        _state.update {
            it.copy(
                phase             = Phase.Capturing,
                error             = null,
                partialTranscript = ""
            )
        }
        // Force Hindi recognition. Per user spec the whole app is Hindi-
        // only, so we pass the BCP-47 tag explicitly instead of leaning
        // on the device's default locale (which on many phones is
        // English-IN or English-US).
        stt.start(languageTag = "hi-IN") { transcript -> handleTranscript(transcript) }
    }

    private fun handleTranscript(transcript: String?) {
        when {
            transcript == null -> {
                val msg = (stt.state.value as? SpeechRecognizerController.State.Error)
                    ?.message ?: "Awaaz capture nahi ho saki — phir try karein."
                _state.update { it.copy(phase = Phase.Idle, error = msg) }
                startReading()
            }
            transcript.isBlank() -> {
                _state.update { it.copy(phase = Phase.Idle) }
                startReading()
            }
            else -> {
                addMessage(ChatMessage(MsgType.User, transcript))
                _state.update { it.copy(phase = Phase.Thinking) }
                viewModelScope.launch { runDoubtFlow(transcript) }
            }
        }
    }

    /* -------- LLM doubt flow -------- */

    private suspend fun runDoubtFlow(question: String) {
        val config = configs.get(AppMode.Reading)
        if (config == null || !config.isComplete()) {
            speakError("Mode 1 ki settings configure karein. API key zaroori hai.")
            return
        }

        val s = _state.value
        val spokenSoFar = if (sentences.isEmpty()) "" else
            sentences.subList(0, s.cursorIndex.coerceIn(0, sentences.size))
                .joinToString(" ")

        // Pull every prior User/Assistant pair out of the chat so the AI
        // sees the entire conversation, not just the last reply. The just-
        // appended User message for THIS turn is also in messages, but
        // extractTurns ignores trailing unmatched User entries.
        val history: List<PromptBuilder.Turn> = extractTurnsFromMessages(s.messages)

        val (system, user) = PromptBuilder.build(
            mode         = AppMode.Reading,
            fullDocument = fullDocText,
            spokenSoFar  = spokenSoFar,
            history      = history,
            userQuery    = question
        )

        val assistantIdx = addMessage(ChatMessage(MsgType.Assistant, "", isStreaming = true))

        // Stream the answer with UI-rate throttling. The previous code
        // updated the chat row on every single SSE token, which made
        // RichTextRenderer.render(accumulated) run O(N²) total times for
        // an N-token answer and stuttered around the 1000-token mark.
        // coalesceTokensForUi caps that at ~20 fps without changing the
        // streaming wire-protocol, and always flushes the final
        // accumulator on completion so the answer is never truncated.
        var latestText = ""
        try {
            llm.askStreaming(config, system, user)
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
            "AI ne abhi response nahi diya — network ya API key check karein, phir dobara try karein."
        }
        // Store the answer with markdown intact — the chat row renders
        // it via RichTextRenderer.
        updateMessage(assistantIdx, answer, isStreaming = false)

        _state.update { it.copy(phase = Phase.Speaking, nowSpeakingIndex = assistantIdx) }
        delay(150)
        // Strip markdown only at the TTS boundary so the engine doesn't
        // literally pronounce "asterisk asterisk". The stripped char
        // stream is what `RichTextRenderer.render(answer).plain` also
        // produces, so the highlight char ranges TtsController emits
        // line up with the displayed AnnotatedString.
        speakAndWait(MarkdownStripper.strip(answer))

        _state.update {
            it.copy(
                phase            = Phase.Idle,
                nowSpeakingIndex = null,
                nowSpokenRange   = null
            )
        }
        startReading()
    }

    private suspend fun speakError(message: String) {
        val idx = addMessage(ChatMessage(MsgType.Assistant, message))
        _state.update { it.copy(phase = Phase.Speaking, nowSpeakingIndex = idx) }
        speakAndWait(message)
        _state.update {
            it.copy(
                phase            = Phase.Idle,
                nowSpeakingIndex = null,
                nowSpokenRange   = null
            )
        }
        startReading()
    }

    /* -------- Chat helpers -------- */

    /**
     * Walks the chat in order and pairs each User message with the first
     * non-streaming Assistant message that follows it. Doc-type messages
     * and trailing unmatched User entries (i.e. the question being asked
     * right now) are skipped — they don't belong in the history block.
     */
    private fun extractTurnsFromMessages(
        messages: List<ChatMessage>
    ): List<PromptBuilder.Turn> {
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
                MsgType.Doc -> Unit  // document narration, not a Q&A pair
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

    fun consumeError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        readingJob?.cancel()
        stt.release()
        tts.shutdown()
        super.onCleared()
    }

    companion object {
        fun factory(documentId: Long, appContext: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = appContext.applicationContext
                return ReadingViewModel(
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
