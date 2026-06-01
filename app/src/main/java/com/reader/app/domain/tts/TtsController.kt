package com.reader.app.domain.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps Android's native TextToSpeech with full pitch / speed / language /
 * voice control plus a strict one-shot speak helper.
 *
 * **Word-level highlighting** is exposed through [currentWordRange]. When
 * the TTS engine starts speaking a word it fires [UtteranceProgressListener.onRangeStart]
 * with the `[start, end)` character range inside the spoken utterance —
 * we forward that as a `IntRange` and the UI uses it to draw a
 * one-word-at-a-time highlight inside the active chat message.
 */
class TtsController(
    private val appContext: Context
) {

    sealed interface State {
        data object Idle : State
        data object Initializing : State
        data object Ready : State
        data class Error(val message: String) : State
    }

    private val _state          = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _isSpeaking          = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentIndex          = MutableStateFlow(0)
    val currentIndex: StateFlow<Int>   = _currentIndex.asStateFlow()

    /**
     * The character range inside the *currently-being-spoken utterance*
     * that the TTS engine is on right now. `null` when nothing is being
     * spoken or before the first onRangeStart event arrives.
     *
     * Only available on API 26+ (we target minSdk 26).
     */
    private val _currentWordRange          = MutableStateFlow<IntRange?>(null)
    val currentWordRange: StateFlow<IntRange?> = _currentWordRange.asStateFlow()

    private val _pitch          = MutableStateFlow(DEFAULT_PITCH)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _speechRate          = MutableStateFlow(DEFAULT_RATE)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _languageTag          = MutableStateFlow(DEFAULT_LANG)
    val languageTag: StateFlow<String> = _languageTag.asStateFlow()

    private val _voiceName          = MutableStateFlow<String?>(null)
    val voiceName: StateFlow<String?> = _voiceName.asStateFlow()

    private val _availableVoices          = MutableStateFlow<List<Voice>>(emptyList())
    val availableVoices: StateFlow<List<Voice>> = _availableVoices.asStateFlow()

    private var chunks: List<String> = emptyList()
    private var tts: TextToSpeech? = null

    var onCursorAdvanced: ((Int) -> Unit)? = null

    /** Map of unique one-shot utterance IDs to their onDone callbacks. */
    private val pendingOneShots = ConcurrentHashMap<String, () -> Unit>()
    private val oneShotCounter = AtomicInteger(0)

    fun init(
        languageTag: String = DEFAULT_LANG,
        pitch: Float = DEFAULT_PITCH,
        speechRate: Float = DEFAULT_RATE,
        voiceName: String? = null,
        onReady: (() -> Unit)? = null
    ) {
        if (_state.value == State.Ready) {
            applyPreferences(languageTag, pitch, speechRate, voiceName)
            onReady?.invoke()
            return
        }
        _state.value = State.Initializing
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(progressListener)
                applyPreferences(languageTag, pitch, speechRate, voiceName)
                _state.value = State.Ready
                onReady?.invoke()
            } else {
                _state.value = State.Error("TTS init failed: $status")
            }
        }
    }

    fun setLanguage(tag: String) {
        tts?.language = Locale.forLanguageTag(tag)
        _languageTag.value = tag
        refreshVoiceList()
    }

    fun setPitch(value: Float) {
        val clamped = value.coerceIn(MIN_PITCH, MAX_PITCH)
        tts?.setPitch(clamped)
        _pitch.value = clamped
    }

    fun setSpeechRate(value: Float) {
        val clamped = value.coerceIn(MIN_RATE, MAX_RATE)
        tts?.setSpeechRate(clamped)
        _speechRate.value = clamped
    }

    fun setVoice(name: String?) {
        val engine = tts ?: return
        if (name == null) {
            engine.voice = engine.defaultVoice
            _voiceName.value = null
            return
        }
        val voice = engine.voices?.firstOrNull { it.name == name }
        if (voice != null) {
            engine.voice = voice
            _voiceName.value = name
        }
    }

    private fun applyPreferences(
        languageTag: String,
        pitch: Float,
        speechRate: Float,
        voiceName: String?
    ) {
        val engine = tts ?: return
        engine.language = Locale.forLanguageTag(languageTag)
        engine.setPitch(pitch.coerceIn(MIN_PITCH, MAX_PITCH))
        engine.setSpeechRate(speechRate.coerceIn(MIN_RATE, MAX_RATE))
        _languageTag.value = languageTag
        _pitch.value       = pitch
        _speechRate.value  = speechRate
        refreshVoiceList()
        if (voiceName != null) setVoice(voiceName)
    }

    private fun refreshVoiceList() {
        val engine = tts ?: return
        val locale = Locale.forLanguageTag(_languageTag.value)
        _availableVoices.value = engine.voices?.filter { it.locale == locale }
            ?.sortedBy { it.name } ?: emptyList()
    }

    fun setChunks(newChunks: List<String>, startIndex: Int = 0) {
        chunks = newChunks
        _currentIndex.value = startIndex.coerceIn(0, newChunks.size)
    }

    fun start() {
        val engine = tts ?: return
        if (chunks.isEmpty()) return
        if (_currentIndex.value >= chunks.size) return
        _isSpeaking.value = true
        speakIndex(_currentIndex.value, engine)
    }

    fun pause() {
        tts?.stop()
        _isSpeaking.value = false
        _currentWordRange.value = null
    }

    fun reset() {
        tts?.stop()
        _isSpeaking.value = false
        _currentIndex.value = 0
        _currentWordRange.value = null
    }

    /**
     * Speak a single ad-hoc string. Each call gets a unique utteranceId so
     * back-to-back calls don't collide and silently drop in the engine queue.
     *
     * STRICT GUARANTEE: onDone fires for every terminal state.
     */
    fun speakOneShot(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts ?: run { onDone?.invoke(); return }
        if (text.isBlank()) { onDone?.invoke(); return }

        val id = "${ONE_SHOT_PREFIX}${oneShotCounter.incrementAndGet()}"
        if (onDone != null) pendingOneShots[id] = onDone

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        _isSpeaking.value = true
        _currentWordRange.value = null
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _state.value = State.Idle
        _isSpeaking.value = false
        _currentWordRange.value = null
        // Drain any pending callbacks so coroutines don't hang.
        pendingOneShots.values.forEach { runCatching { it.invoke() } }
        pendingOneShots.clear()
    }

    private fun speakIndex(index: Int, engine: TextToSpeech) {
        val text = chunks.getOrNull(index) ?: return
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceIdFor(index))
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceIdFor(index))
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _currentWordRange.value = null
        }

        /**
         * API 26+: fires once for each "speaking unit" — usually a word —
         * with the character range inside the utterance text. Drives the
         * one-word-at-a-time highlight in the chat.
         */
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (utteranceId == null) return
            if (start < 0 || end <= start) return
            _currentWordRange.value = start until end
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == null) return
            _currentWordRange.value = null
            if (utteranceId.startsWith(ONE_SHOT_PREFIX)) {
                _isSpeaking.value = false
                val cb = pendingOneShots.remove(utteranceId)
                cb?.invoke()
                return
            }
            val justSpoken = parseIndex(utteranceId) ?: return
            val next = justSpoken + 1
            _currentIndex.value = next
            onCursorAdvanced?.invoke(next)
            val engine = tts
            if (_isSpeaking.value && engine != null && next < chunks.size) {
                speakIndex(next, engine)
            } else {
                _isSpeaking.value = false
            }
        }

        @Deprecated("Deprecated, but still required.")
        override fun onError(utteranceId: String?) {
            _isSpeaking.value = false
            _currentWordRange.value = null
            if (utteranceId != null && utteranceId.startsWith(ONE_SHOT_PREFIX)) {
                val cb = pendingOneShots.remove(utteranceId)
                cb?.invoke()
                return
            }
            _state.value = State.Error("TTS error on $utteranceId")
        }
    }

    private fun utteranceIdFor(index: Int) = "$CHUNK_ID_PREFIX$index"
    private fun parseIndex(id: String): Int? =
        id.removePrefix(CHUNK_ID_PREFIX).toIntOrNull()

    companion object {
        private const val CHUNK_ID_PREFIX  = "chunk_"
        private const val ONE_SHOT_PREFIX  = "one_shot_"

        const val DEFAULT_LANG  = "hi-IN"
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_RATE  = 1.0f

        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
        const val MIN_RATE  = 0.5f
        const val MAX_RATE  = 2.0f
    }
}
