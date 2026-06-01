package com.reader.app.domain.audio

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place radix-2 Cooley–Tukey FFT in pure Kotlin (no native deps).
 *
 * Input arrays must be the same power-of-two length and contain real/imag
 * components respectively. After the call the arrays hold the transformed
 * spectrum, and [magnitude] yields the |X[k]| envelope.
 */
internal object Fft {

    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size) { "re/im length mismatch" }
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be power of two: $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        // Butterflies.
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlRe = cos(ang).toFloat()
            val wlIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val tRe = wRe * re[b] - wIm * im[b]
                    val tIm = wRe * im[b] + wIm * re[b]
                    re[b] = re[a] - tRe; im[b] = im[a] - tIm
                    re[a] = re[a] + tRe; im[a] = im[a] + tIm
                    val nRe = wRe * wlRe - wIm * wlIm
                    val nIm = wRe * wlIm + wIm * wlRe
                    wRe = nRe; wIm = nIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Power spectrum (squared magnitude) of the first half + Nyquist. */
    fun powerSpectrum(re: FloatArray, im: FloatArray, out: FloatArray) {
        val bins = out.size
        for (k in 0 until bins) {
            out[k] = re[k] * re[k] + im[k] * im[k]
        }
    }
}
