package com.reader.app.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Converts a mono 16 kHz PCM utterance into a fixed-size speaker embedding.
 *
 * Pipeline (all pure-Kotlin, no native deps, no model files):
 *
 *   1. Pre-emphasise.
 *   2. Frame the signal: 32 ms windows, 10 ms hop, Hamming-windowed.
 *   3. Per-frame [Fft] + power spectrum.
 *   4. Apply a [MelFilterbank] of [NUM_MEL] bands → log-mel energies.
 *   5. Drop low-energy frames (silence / breath) before pooling.
 *   6. Mean-pool across surviving frames.
 *   7. L2-normalise so cosine similarity reduces to a dot product.
 *
 * The result is a [NUM_MEL]-dim unit-norm vector that captures the gross
 * spectral envelope of a speaker's voice. Accuracy is well below an ECAPA
 * embedding, but it's enough to distinguish the enrolled student from
 * background voices, which is the only requirement here.
 */
internal object SpeakerEmbedder {

    const val SAMPLE_RATE  = 16_000
    const val NUM_MEL      = 24
    const val FFT_SIZE     = 512                 // 32 ms @ 16 kHz
    const val HOP          = 160                 // 10 ms @ 16 kHz
    const val FRAME_LEN    = FFT_SIZE
    private const val PRE_EMPH = 0.97f
    private const val SILENCE_FLOOR_DB = -50.0   // drop frames below this rel. peak
    private const val EPS = 1e-10

    private val window: FloatArray = hamming(FRAME_LEN)
    private val filterbank: Array<FloatArray> =
        MelFilterbank.build(NUM_MEL, FFT_SIZE, SAMPLE_RATE)

    /**
     * Compute a [NUM_MEL]-dim embedding from a mono 16 kHz PCM array.
     * Returns null if the audio is too short or entirely silent.
     */
    fun embed(pcm: ShortArray): FloatArray? {
        if (pcm.size < FRAME_LEN) return null

        // Convert to float in [-1, 1] and pre-emphasise.
        val x = FloatArray(pcm.size)
        x[0] = pcm[0] / 32768f
        for (i in 1 until pcm.size) {
            x[i] = (pcm[i] / 32768f) - PRE_EMPH * (pcm[i - 1] / 32768f)
        }

        val numFrames = (x.size - FRAME_LEN) / HOP + 1
        if (numFrames <= 0) return null

        // Per-frame log-mel energies.
        val logMelFrames = ArrayList<FloatArray>(numFrames)
        val frameEnergiesDb = FloatArray(numFrames)

        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val power = FloatArray(FFT_SIZE / 2 + 1)

        for (f in 0 until numFrames) {
            val start = f * HOP
            // Window into re; im is zeroed.
            var energy = 0.0
            for (n in 0 until FRAME_LEN) {
                val v = x[start + n] * window[n]
                re[n] = v
                im[n] = 0f
                energy += v * v
            }
            frameEnergiesDb[f] = (10.0 * ln(energy + EPS) / ln(10.0)).toFloat()

            Fft.transform(re, im)
            Fft.powerSpectrum(re, im, power)

            val logMel = FloatArray(NUM_MEL)
            for (m in 0 until NUM_MEL) {
                val filter = filterbank[m]
                var acc = 0.0
                for (k in filter.indices) acc += filter[k] * power[k]
                logMel[m] = ln(acc + EPS).toFloat()
            }
            logMelFrames += logMel
        }

        // Voice-activity gate: keep frames within SILENCE_FLOOR_DB of the peak.
        val peakDb = frameEnergiesDb.max()
        val keep = BooleanArray(numFrames) { i ->
            frameEnergiesDb[i] >= peakDb + SILENCE_FLOOR_DB
        }
        val kept = keep.count { it }
        if (kept == 0) return null

        // Mean-pool across kept frames.
        val mean = FloatArray(NUM_MEL)
        for (f in 0 until numFrames) {
            if (!keep[f]) continue
            val frame = logMelFrames[f]
            for (m in 0 until NUM_MEL) mean[m] += frame[m]
        }
        for (m in 0 until NUM_MEL) mean[m] /= kept

        // L2 normalise.
        var norm = 0.0
        for (v in mean) norm += v * v
        norm = sqrt(norm + EPS)
        for (m in 0 until NUM_MEL) mean[m] = (mean[m] / norm).toFloat()
        return mean
    }

    private fun hamming(n: Int): FloatArray = FloatArray(n) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))).toFloat()
    }
}
