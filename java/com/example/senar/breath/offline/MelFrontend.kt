package com.example.senar.breath.offline

import kotlin.math.*

object MelFrontend {

    fun computeLogMel30s(
        pcm16: ShortArray,
        sampleRate: Int,
        nMels: Int,
        nFft: Int,
        hopLength: Int,
        winLength: Int,
        fMin: Float = 0f,
        fMax: Float = (sampleRate / 2).toFloat(),
        power: Float = 2.0f
    ): Array<FloatArray> {
        // 取前 30s（不足则补零）
        val need = sampleRate * 30
        val x = FloatArray(need)
        val take = min(need, pcm16.size)
        for (i in 0 until take) x[i] = pcm16[i] / 32768f

        val win = hann(winLength)
        val nFrames = 1 + (x.size - winLength).floorDiv(hopLength).coerceAtLeast(0)
        val nFreq = nFft / 2 + 1

        val spec = Array(nFreq) { FloatArray(nFrames) } // power spec

        // naive DFT（能跑通但不快；你后面实时版我会给你 FFT 优化）
        for (t in 0 until nFrames) {
            val start = t * hopLength
            val frame = FloatArray(nFft)
            for (i in 0 until winLength) frame[i] = x[start + i] * win[i]
            // zero pad already

            for (k in 0 until nFreq) {
                var re = 0.0
                var im = 0.0
                val w = -2.0 * Math.PI * k / nFft
                for (n in 0 until nFft) {
                    val ang = w * n
                    val c = cos(ang)
                    val s = sin(ang)
                    re += frame[n] * c
                    im += frame[n] * s
                }
                val mag2 = (re * re + im * im)
                val v = if (power == 1f) sqrt(mag2) else mag2
                spec[k][t] = v.toFloat()
            }
        }

        val melFb = melFilterBank(
            sampleRate = sampleRate,
            nFft = nFft,
            nMels = nMels,
            fMin = fMin,
            fMax = fMax
        ) // [nMels][nFreq]

        val mel = Array(nMels) { FloatArray(nFrames) }
        for (m in 0 until nMels) {
            for (t in 0 until nFrames) {
                var s = 0.0
                for (k in 0 until nFreq) {
                    s += melFb[m][k] * spec[k][t]
                }
                // log-mel
                mel[m][t] = ln(1.0 + s).toFloat()
            }
        }
        return mel
    }

    private fun hann(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            w[i] = (0.5 - 0.5 * cos(2.0 * Math.PI * i / (n - 1).coerceAtLeast(1))).toFloat()
        }
        return w
    }

    private fun hzToMel(hz: Float): Float = (2595f * log10(1f + hz / 700f))
    private fun melToHz(mel: Float): Float = (700f * (10f.pow(mel / 2595f) - 1f))

    private fun melFilterBank(
        sampleRate: Int,
        nFft: Int,
        nMels: Int,
        fMin: Float,
        fMax: Float
    ): Array<FloatArray> {
        val nFreq = nFft / 2 + 1
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val mels = FloatArray(nMels + 2)
        for (i in 0 until (nMels + 2)) {
            mels[i] = melMin + (melMax - melMin) * i / (nMels + 1).toFloat()
        }
        val hz = FloatArray(nMels + 2) { melToHz(mels[it]) }
        val bins = IntArray(nMels + 2) { ((nFft + 1) * hz[it] / sampleRate).toInt().coerceIn(0, nFreq - 1) }

        val fb = Array(nMels) { FloatArray(nFreq) }
        for (m in 1..nMels) {
            val f0 = bins[m - 1]
            val f1 = bins[m]
            val f2 = bins[m + 1]
            for (k in f0 until f1) {
                fb[m - 1][k] = (k - f0).toFloat() / (f1 - f0).coerceAtLeast(1)
            }
            for (k in f1 until f2) {
                fb[m - 1][k] = (f2 - k).toFloat() / (f2 - f1).coerceAtLeast(1)
            }
        }
        return fb
    }
}
