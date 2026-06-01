package com.reader.app.domain.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * Builds a triangular mel filterbank that maps a [fftSize]-point power spectrum
 * (i.e. `fftSize / 2 + 1` bins) into [numFilters] perceptual bands. Each filter
 * is a `FloatArray` of weights, one weight per FFT bin.
 *
 * We use the standard HTK mel scale and log-spaced filter centres between
 * [fMin] and [fMax]. Generated once per session and reused across frames.
 */
internal object MelFilterbank {

    fun build(
        numFilters: Int,
        fftSize: Int,
        sampleRate: Int,
        fMin: Double = 80.0,
        fMax: Double = 7600.0
    ): Array<FloatArray> {
        val numBins = fftSize / 2 + 1
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        val melPoints = DoubleArray(numFilters + 2) { i ->
            melMin + i * (melMax - melMin) / (numFilters + 1)
        }
        val hzPoints = DoubleArray(melPoints.size) { melToHz(melPoints[it]) }
        val binPoints = IntArray(hzPoints.size) { i ->
            (fftSize * hzPoints[i] / sampleRate).toInt().coerceIn(0, numBins - 1)
        }

        return Array(numFilters) { i ->
            val left   = binPoints[i]
            val center = binPoints[i + 1]
            val right  = binPoints[i + 2]
            FloatArray(numBins) { k ->
                when {
                    k <= left || k >= right -> 0f
                    k <= center -> (k - left).toFloat() / (center - left).coerceAtLeast(1)
                    else        -> (right - k).toFloat() / (right - center).coerceAtLeast(1)
                }
            }
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
