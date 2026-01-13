package com.example.senar.breath.evidence

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object BreathEvidenceUtils {

    /**
     * 计算简易 envelope：对 |pcm| 做滑动平均，下采样到 points 个点用于画图
     */
    fun envelopeDownsample(pcm: FloatArray, points: Int = 300): FloatArray {
        if (pcm.isEmpty() || points <= 0) return FloatArray(0)
        val n = pcm.size
        val hop = max(1, n / points)
        val out = FloatArray(points)
        var oi = 0
        var i = 0
        while (i < n && oi < points) {
            var s = 0.0
            var c = 0
            val end = minOf(n, i + hop)
            while (i < end) {
                s += abs(pcm[i]).toDouble()
                c++
                i++
            }
            out[oi] = (s / max(1, c)).toFloat()
            oi++
        }
        return out.copyOf(oi)
    }

    /**
     * 你的 feature 是 [2,64,64] flatten：前 4096 是 ch0（log-mel pooled），后 4096 是 ch1
     * 回放 UI 的“热力图证据”优先用 ch0
     */
    fun heat64FromFeatureC2HW64(feature: FloatArray): FloatArray? {
        if (feature.size < 2 * 64 * 64) return null
        return feature.copyOfRange(0, 64 * 64)
    }

    /**
     * 从热力图粗造一个 envelope（可选）：按 freq 方向求均值，得到 64 点，再插值到 points
     * 用于离线没有原始 pcm 时也能显示“证据曲线”
     */
    fun envelopeFromHeat64(heat64: FloatArray, points: Int = 300): FloatArray {
        if (heat64.size != 64 * 64) return FloatArray(0)
        val col = FloatArray(64)
        for (x in 0 until 64) {
            var s = 0.0
            for (y in 0 until 64) s += heat64[y * 64 + x].toDouble()
            col[x] = (s / 64.0).toFloat()
        }
        // linear upsample 64 -> points
        if (points <= 64) return col.copyOf(points)
        val out = FloatArray(points)
        for (i in 0 until points) {
            val t = i * (63f / (points - 1))
            val i0 = t.toInt().coerceIn(0, 63)
            val i1 = (i0 + 1).coerceIn(0, 63)
            val w = t - i0
            out[i] = col[i0] * (1 - w) + col[i1] * w
        }
        return out
    }
}
