package com.reader.app.domain.stt

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Wrapper around Android's native [SpeechRecognizer].
 *
 * Design choices made to fix the user-reported "mic doesn't work" bug:
 *
 *  - **Online ASR is preferred.** `EXTRA_PREFER_OFFLINE` is intentionally
 *    not set — the on-device model is English-only on most phones and was
 *    transcribing Hindi/Hinglish badly.
 *
 *  - **Patience timeouts** allow up to 12 s of speaking and only finalise
 *    after ~2 s of silence, so the engine doesn't cut the user off after
 *    the first word.
 *
 *  - **Tap-to-toggle compatible.** [start] is safe to call from any state;
 *    [stop] asks the engine to finalise immediately. If the user taps mic
 *    to start and then taps again before the recognizer is even
 *    instantiated (i.e. inside the small post-delay), [stop] cancels the
 *    pending session via a generation counter so we never end up with a
 *    runaway recognizer.
 *
 *  - **Pre-created recognizer.** We instantiate `SpeechRecognizer` and
 *    fire `startListening` on the same main-thread tick. Earlier code
 *    used `postDelayed(150ms)` which raced with double-taps; now we use
 *    a 0 ms post (just for thread-correctness) so the engine starts as
 *    quickly as possible after the user taps.
 *
 *  - **`stop()` calls `stopListening()`** so the engine finalises and
 *    delivers `onResults` instead of waiting the full silence timeout.
 *    For hard abort use [cancel] instead.
 */
class SpeechRecognizerController(
    private val appContext: Context
) {

    sealed interface State {
        data object Idle      : State
        data object Listening : State
        data class  Error(val code: Int, val message: String) : State
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state          = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _partial          = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var pendingResult: ((String?) -> Unit)? = null

    /**
     * Increments on every [start] / [stop] / [cancel] / [release]. The
     * post-to-main-thread runnable inside [start] only proceeds if its
     * captured generation still matches — that's how we cancel a pending
     * session safely.
     */
    private var generation = 0L

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Begin a recognition session.
     *
     * [languageTag]: BCP-47 language tag (e.g. "hi-IN", "en-IN"). If null,
     *   uses the device's default locale.
     *
     * [onResult]:
     *   non-blank String → recognised transcript
     *   blank String     → nothing heard (no-match / timeout / cancelled)
     *   null             → hard hardware / permission error
     */
    fun start(languageTag: String? = null, onResult: (String?) -> Unit) {
        mainHandler.post {
            // Bump generation FIRST so any in-flight runnable from a previous
            // start() bails out below.
            val myGen = ++generation

            // Quick gates that don't need the recognizer.
            if (!hasPermission()) {
                _state.value = State.Error(
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    "Microphone permission not granted. Grant RECORD_AUDIO in app Settings."
                )
                onResult(null)
                return@post
            }
            if (!isAvailable()) {
                _state.value = State.Error(ERR_NOT_AVAILABLE,
                    "Speech recognition not available on this device. Install the Google app from Play Store.")
                onResult(null)
                return@post
            }

            // Tear down any previous instance.
            recognizer?.destroy()
            recognizer = null
            pendingResult = onResult
            _partial.value = ""
            _state.value   = State.Listening

            // If a stop/cancel runs before the next handler tick it'll bump
            // `generation` again — we check on the next tick.
            mainHandler.post {
                if (myGen != generation) {
                    // Superseded — most likely the user tapped mic to stop
                    // before the engine even came up. Deliver "" so the
                    // ViewModel resumes reading instead of hanging.
                    pendingResult?.invoke("")
                    pendingResult = null
                    _state.value = State.Idle
                    return@post
                }

                val sr: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                sr.setRecognitionListener(makeListener(myGen))
                recognizer = sr

                val lang = languageTag ?: Locale.getDefault().toLanguageTag()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)

                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        MIN_SPEECH_LENGTH_MS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        COMPLETE_SILENCE_MS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        POSSIBLY_COMPLETE_SILENCE_MS)
                }
                runCatching { sr.startListening(intent) }
                    .onFailure { e ->
                        _state.value = State.Error(
                            SpeechRecognizer.ERROR_CLIENT,
                            e.message ?: "Failed to start mic"
                        )
                        pendingResult?.invoke(null)
                        pendingResult = null
                    }
            }
        }
    }

    /**
     * The student is done speaking. Tell the engine to finalise so it
     * delivers `onResults` immediately instead of waiting for the silence
     * timeout. Also handles the "tap-to-stop before recognizer was even
     * built" case via the generation counter.
     */
    fun stop() {
        mainHandler.post {
            generation++ // cancels any pending start runnable
            val r = recognizer
            if (r != null) {
                runCatching { r.stopListening() }
            } else {
                // start() never reached the point of creating an engine —
                // deliver an empty result so callers don't hang.
                val cb = pendingResult
                pendingResult = null
                _state.value = State.Idle
                cb?.invoke("")
            }
        }
    }

    /** Abort without delivering a result (use when navigating away). */
    fun cancel() {
        mainHandler.post {
            generation++
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            pendingResult = null
            _state.value = State.Idle
            _partial.value = ""
        }
    }

    /** Destroy engine. Call from ViewModel.onCleared(). */
    fun release() {
        mainHandler.post {
            generation++
            recognizer?.destroy()
            recognizer = null
            pendingResult = null
            _state.value = State.Idle
            _partial.value = ""
        }
    }

    private fun makeListener(sessionGen: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (sessionGen != generation) return
            val alternatives = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return
            val best = alternatives.maxByOrNull { it.length }.orEmpty()
            if (best.isNotEmpty()) _partial.value = best
        }

        override fun onResults(results: Bundle?) {
            if (sessionGen != generation) return
            val alternatives = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: emptyList()
            val best = alternatives.maxByOrNull { it.length }.orEmpty()
            _state.value = State.Idle
            deliver(best)
        }

        override fun onError(error: Int) {
            if (sessionGen != generation) return
            _state.value = State.Error(error, errorMessage(error))
            val result: String? = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""    // nothing heard
                else                                  -> null  // real error
            }
            deliver(result)
        }

        private fun deliver(value: String?) {
            val cb = pendingResult
            pendingResult = null
            recognizer?.destroy()
            recognizer = null
            cb?.invoke(value)
        }

        private fun errorMessage(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO                    -> "Mic audio error — try again"
            SpeechRecognizer.ERROR_CLIENT                   -> "Recognizer client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing — grant it in system Settings"
            SpeechRecognizer.ERROR_NETWORK                  -> "Network error — check your internet connection"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT          -> "Network timeout — check your internet connection"
            SpeechRecognizer.ERROR_NO_MATCH                 -> "Could not understand — speak clearly"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY          -> "Recognizer busy — wait a moment"
            SpeechRecognizer.ERROR_SERVER                   -> "Recognizer server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT           -> "No speech detected — try again"
            else                                            -> "Recognizer error $code"
        }
    }

    companion object {
        const val ERR_NOT_AVAILABLE = -1

        /**
         * Maximum length of a single doubt session. The student may speak
         * for up to this long before the engine starts considering silence;
         * `stop()` (called by the tap-to-stop) finalises earlier.
         */
        private const val MIN_SPEECH_LENGTH_MS = 12_000L

        /**
         * Silence treated as "speech complete" — final result is delivered.
         * Tap-to-stop short-circuits this via stopListening().
         */
        private const val COMPLETE_SILENCE_MS = 2_000L

        /** Silence treated as "speech possibly complete". */
        private const val POSSIBLY_COMPLETE_SILENCE_MS = 1_500L
    }
}
