package com.reader.app.domain.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.reader.app.data.repository.SpeakerEnrollmentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.sqrt

/**
 * Always-on **barge-in trigger**. Continuously reads 250 ms PCM frames from
 * the mic; when the energy VAD fires, it embeds the ~500 ms of pre-trigger
 * audio (lookback ring) and runs [SpeakerVerifier] against the enrolled
 * template. If the verdict is the enrolled student, [Event.VerifiedTrigger]
 * is emitted and the listener stops itself, releasing the mic so the caller
 * can hand off to [com.reader.app.domain.stt.SpeechRecognizerController]
 * for actual transcription.
 *
 * Why hand off to SpeechRecognizer: it's the free, on-device path the user
 * requested. The mic is exclusive between AudioRecord and SpeechRecognizer,
 * so we keep AudioRecord up only long enough to do speaker gating, then
 * release it.
 *
 * AEC: VOICE_COMMUNICATION engages the hardware echo canceller on most
 * modern devices, plus we attach the software AEC/NS/AGC effects when the
 * platform reports them available. This stops the TTS playback bleeding
 * back into the mic and re-triggering the VAD on every word.
 *
 * Cooldown: after a rejected utterance we ignore voiced frames for ~1 s so
 * the same chunk of someone-else's-voice doesn't keep re-firing.
 */
class BargeInListener(
    private val enrollment: SpeakerEnrollmentRepository,
    private val sampleRate: Int = SpeakerEmbedder.SAMPLE_RATE
) {

    sealed interface Event {
        data object Listening                                 : Event
        /** A voiced frame matched the enrolled student — hand off to STT. */
        data object VerifiedTrigger                           : Event
        /** A voiced frame didn't match (TTS bleed / stranger). */
        data class  Rejected(val similarity: Float)           : Event
        data class  Error(val message: String)                : Event
    }

    private var job: Job? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns:  NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile private var enrolledEmbedding: FloatArray? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            enrolledEmbedding = enrollment.get()?.embedding
            if (enrolledEmbedding == null) {
                _events.emit(Event.Error("Voice not enrolled"))
                return@launch
            }
            runLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        val frameSize = sampleRate / 4   // 250 ms
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            _events.emit(Event.Error("AudioRecord buffer size unavailable"))
            return
        }
        val bufSize = minBuf.coerceAtLeast(frameSize * 4)

        val rec = try {
            @SuppressLint("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (t: Throwable) {
            _events.emit(Event.Error(t.message ?: "AudioRecord init failed"))
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            _events.emit(Event.Error("AudioRecord not initialized"))
            return
        }

        if (AcousticEchoCanceler.isAvailable())
            aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
        if (NoiseSuppressor.isAvailable())
            ns = runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
        if (AutomaticGainControl.isAvailable())
            agc = runCatching { AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()

        rec.startRecording()
        _events.emit(Event.Listening)

        val frame = ShortArray(frameSize)
        val lookback = ArrayDeque<ShortArray>()
        var cooldownFrames = 0
        val enrolled = enrolledEmbedding!!

        try {
            while (true) {
                yield()
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                val chunk = frame.copyOf(n)
                val isVoice = rms(chunk) > VAD_THRESHOLD

                lookback.addLast(chunk)
                if (lookback.size > LOOKBACK_FRAMES) lookback.removeFirst()

                if (cooldownFrames > 0) { cooldownFrames--; continue }
                if (!isVoice) continue

                // Voiced frame: try to verify on the lookback window.
                val pcm = merge(lookback)
                val emb = SpeakerEmbedder.embed(pcm)
                if (emb == null) continue
                val verdict = SpeakerVerifier.verify(enrolled, emb)
                if (verdict.accepted) {
                    _events.emit(Event.VerifiedTrigger)
                    break  // hand mic off to caller
                } else {
                    _events.emit(Event.Rejected(verdict.similarity))
                    cooldownFrames = COOLDOWN_FRAMES
                }
            }
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
            aec?.release(); aec = null
            ns?.release();  ns  = null
            agc?.release(); agc = null
        }
    }

    private fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) {
            val v = s / 32768.0
            sum += v * v
        }
        return sqrt(sum / pcm.size)
    }

    private fun merge(frames: Collection<ShortArray>): ShortArray {
        val total = frames.sumOf { it.size }
        val out = ShortArray(total)
        var off = 0
        for (f in frames) { f.copyInto(out, off); off += f.size }
        return out
    }

    companion object {
        /** RMS in [-1, 1] domain that we treat as "speech present". */
        private const val VAD_THRESHOLD = 0.012

        /** ~500 ms of pre-trigger audio used for speaker fingerprinting. */
        private const val LOOKBACK_FRAMES = 2

        /** ~1 s of suppression after a rejected voiced frame. */
        private const val COOLDOWN_FRAMES = 4
    }
}
