package com.reader.app.domain.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny [AudioRecord] wrapper that captures mono 16 kHz PCM into memory and
 * returns the full buffer on [stop]. Used both for voice enrollment and for
 * the doubt-asking step on the reading screen.
 *
 * Caller MUST hold RECORD_AUDIO permission before calling [start].
 */
class AudioRecorder(
    private val sampleRate: Int = SpeakerEmbedder.SAMPLE_RATE
) {

    sealed interface State {
        data object Idle      : State
        data object Recording : State
        data class  Error(val message: String) : State
    }

    private val _state          = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var collecting = false
    private val captured = ArrayList<ShortArray>(64)

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (collecting) return true

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            _state.value = State.Error("AudioRecord buffer size unavailable")
            return false
        }
        val bufSize = minBuf.coerceAtLeast(4096)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (t: Throwable) {
            _state.value = State.Error(t.message ?: "AudioRecord init failed")
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            _state.value = State.Error("AudioRecord not initialized")
            return false
        }

        captured.clear()
        rec.startRecording()
        recorder = rec
        collecting = true
        _state.value = State.Recording

        thread = Thread {
            val tmp = ShortArray(2048)
            try {
                while (collecting) {
                    val n = rec.read(tmp, 0, tmp.size)
                    if (n > 0) captured.add(tmp.copyOf(n))
                }
            } catch (_: Throwable) { /* swallow; stop() handles teardown */ }
        }.also { it.isDaemon = true; it.start() }

        return true
    }

    /** Stop recording and return the captured PCM. Safe to call when idle. */
    fun stop(): ShortArray {
        if (!collecting && recorder == null) return ShortArray(0)
        collecting = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (_: Throwable) {}
        rec?.release()
        _state.value = State.Idle

        val total = captured.sumOf { it.size }
        val out = ShortArray(total)
        var off = 0
        for (chunk in captured) {
            chunk.copyInto(out, off)
            off += chunk.size
        }
        captured.clear()
        return out
    }

    fun release() {
        if (collecting) stop()
        recorder?.release()
        recorder = null
    }
}
