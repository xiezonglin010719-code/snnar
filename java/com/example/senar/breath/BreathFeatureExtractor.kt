package com.example.senar.breath

import kotlin.math.*

object BreathFeatureExtractor {
    private const val N_FFT = 2048
    private const val HOP = 512
    private const val WIN = 2048
    private const val N_MELS = 128

    private val hann: FloatArray = FloatArray(WIN) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (WIN - 1))).toFloat()
    }

    /**
     * 主入口：30s PCM -> C2HW64 (flatten)
     * 输出 FloatArray 大小 = 2*64*64
     */
    fun extract30sToC2HW64(pcm: FloatArray, sr: Int): FloatArray {
        // 1) STFT power: [F=1025, T]
        val pow = stftPower(pcm, sr)

        // 2) mel: [M=128, T]
        val mel = powerToMel(pow, sr)

        // 3) log-mel
        val logMel = log10_1p(mel)

        // 4) aux: abs(delta)
        val aux = absDelta(logMel)

        // 5) pool to 64x64
        val ch0 = poolTo64x64(logMel)
        val ch1 = poolTo64x64(aux)

        // 6) per-channel normalize
        normalizeInPlace(ch0)
        normalizeInPlace(ch1)

        // 7) pack [C=2,H=64,W=64] flatten
        val out = FloatArray(2 * 64 * 64)
        System.arraycopy(ch0, 0, out, 0, 64 * 64)
        System.arraycopy(ch1, 0, out, 64 * 64, 64 * 64)
        return out
    }

    /**
     * 质量估计（很粗）：用能量 + clipping 比例做个 0~1 分
     */
    fun estimateQuality(pcm: FloatArray, sr: Int): Float {
        var e = 0.0
        var clip = 0
        for (v in pcm) {
            e += (v * v).toDouble()
            if (abs(v) > 0.98f) clip++
        }
        val rms = sqrt(e / max(1, pcm.size))
        val clipRate = clip.toFloat() / max(1, pcm.size)
        val q = (rms / 0.1).toFloat().coerceIn(0f, 1f) * (1f - clipRate * 5f).coerceIn(0f, 1f)
        return q.coerceIn(0f, 1f)
    }

    // ---------------- STFT (naive FFT for N_FFT=2048) ----------------

    private fun stftPower(x: FloatArray, sr: Int): Array<FloatArray> {
        // center=true + pad reflect
        val pad = N_FFT / 2
        val xp = reflectPad(x, pad)

        val nFrames = 1 + (xp.size - N_FFT) / HOP
        val nFreq = N_FFT / 2 + 1

        val out = Array(nFreq) { FloatArray(nFrames) }

        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)

        var t = 0
        var off = 0
        while (t < nFrames) {
            // windowed frame
            for (i in 0 until N_FFT) {
                val v = xp[off + i] * hann[i]
                re[i] = v.toDouble()
                im[i] = 0.0
            }
            fftRadix2(re, im)

            // power
            for (f in 0 until nFreq) {
                val pr = re[f]
                val pi = im[f]
                out[f][t] = (pr * pr + pi * pi).toFloat()
            }

            off += HOP
            t++
        }
        return out
    }

    // radix-2 FFT in-place (N must be power of 2)
    private fun fftRadix2(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlenR = cos(ang)
            val wlenI = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vI = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr

                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR
                    im[i + k + len / 2] = uI - vI

                    val nwr = wr * wlenR - wi * wlenI
                    wi = wr * wlenI + wi * wlenR
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun reflectPad(x: FloatArray, pad: Int): FloatArray {
        val n = x.size
        val out = FloatArray(n + 2 * pad)
        // center
        System.arraycopy(x, 0, out, pad, n)
        // left reflect
        for (i in 0 until pad) {
            val src = (pad - i).coerceIn(1, n) - 1
            out[i] = x[src]
        }
        // right reflect
        for (i in 0 until pad) {
            val src = (n - 2 - i).coerceIn(0, n - 1)
            out[pad + n + i] = x[src]
        }
        return out
    }

    // ---------------- Mel ----------------

    private fun powerToMel(pow: Array<FloatArray>, sr: Int): Array<FloatArray> {
        val nFreq = pow.size           // 1025
        val nFrames = pow[0].size
        val mel = Array(N_MELS) { FloatArray(nFrames) }

        val fb = melFilterBank(sr, nFreq)

        for (m in 0 until N_MELS) {
            val w = fb[m]
            for (t in 0 until nFrames) {
                var s = 0.0
                for (f in 0 until nFreq) {
                    s += (pow[f][t] * w[f]).toDouble()
                }
                mel[m][t] = s.toFloat()
            }
        }
        return mel
    }

    private fun melFilterBank(sr: Int, nFreq: Int): Array<FloatArray> {
        // fmin=0, fmax=sr/2
        val fMin = 0.0
        val fMax = sr / 2.0
        val mMin = hzToMel(fMin)
        val mMax = hzToMel(fMax)

        val mPts = DoubleArray(N_MELS + 2) { i ->
            mMin + (mMax - mMin) * i / (N_MELS + 1)
        }
        val fPts = DoubleArray(N_MELS + 2) { melToHz(mPts[it]) }
        val bins = IntArray(N_MELS + 2) { i ->
            floor((N_FFT + 1) * fPts[i] / sr).toInt().coerceIn(0, nFreq - 1)
        }

        val fb = Array(N_MELS) { FloatArray(nFreq) }
        for (m in 0 until N_MELS) {
            val left = bins[m]
            val center = bins[m + 1]
            val right = bins[m + 2]
            if (center == left || right == center) continue

            for (f in left until center) {
                fb[m][f] = ((f - left).toFloat() / (center - left).toFloat())
            }
            for (f in center until right) {
                fb[m][f] = ((right - f).toFloat() / (right - center).toFloat())
            }
        }
        return fb
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun log10_1p(x: Array<FloatArray>): Array<FloatArray> {
        val out = Array(x.size) { FloatArray(x[0].size) }
        for (i in x.indices) {
            for (t in 0 until x[0].size) {
                out[i][t] = log10(1.0 + x[i][t].toDouble()).toFloat()
            }
        }
        return out
    }

    private fun absDelta(x: Array<FloatArray>): Array<FloatArray> {
        val m = x.size
        val tN = x[0].size
        val out = Array(m) { FloatArray(tN) }
        for (i in 0 until m) {
            var prev = x[i][0]
            out[i][0] = 0f
            for (t in 1 until tN) {
                val cur = x[i][t]
                out[i][t] = abs(cur - prev)
                prev = cur
            }
        }
        return out
    }

    private fun poolTo64x64(x: Array<FloatArray>): FloatArray {
        // x: [M=128, T]
        val M = x.size
        val T = x[0].size
        val H = 64
        val W = 64

        val out = FloatArray(H * W)

        // freq 128 -> 64: pool by 2
        // time T -> 64: pool by binning
        for (h in 0 until H) {
            val m0 = (h * M) / H
            val m1 = ((h + 1) * M) / H
            for (w in 0 until W) {
                val t0 = (w * T) / W
                val t1 = ((w + 1) * T) / W
                var s = 0.0
                var c = 0
                for (m in m0 until m1) {
                    val row = x[m]
                    for (t in t0 until t1) {
                        s += row[t].toDouble()
                        c++
                    }
                }
                out[h * W + w] = (s / max(1, c)).toFloat()
            }
        }
        return out
    }

    private fun normalizeInPlace(a: FloatArray) {
        var mean = 0.0
        for (v in a) mean += v.toDouble()
        mean /= max(1, a.size)

        var varSum = 0.0
        for (v in a) {
            val d = v.toDouble() - mean
            varSum += d * d
        }
        val std = sqrt(varSum / max(1, a.size)).coerceAtLeast(1e-6)

        for (i in a.indices) {
            a[i] = ((a[i].toDouble() - mean) / std).toFloat().coerceIn(-6f, 6f)
        }
    }
}
