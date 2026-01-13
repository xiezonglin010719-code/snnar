package com.example.senar.breath

import android.content.Context
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.exp
import kotlin.math.ln

class BreathInferenceEngine(
    private val ctx: Context,
    private val realtimeAsset: String = "uosas_mobile.ts",
    private val offlineAsset: String = "mobile_uosas_distill.ts",
) {
    companion object {
        // ---------- Realtime model spec ----------
        const val RT_K = 10
        const val RT_C = 2
        const val RT_H = 64
        const val RT_W = 64

        // ---------- Offline model spec ----------
        const val OFF_K = 128
        const val OFF_C = 2
        const val OFF_H = 80
        const val OFF_W = 80

        // same policy
        const val DEFERRAL_U_THR = 0.25f

        private const val EVI_EPS = 1e-6f
    }

    private var rtModule: Module? = null
    private var offModule: Module? = null

    private fun loadRealtimeIfNeeded() {
        if (rtModule != null) return
        rtModule = Module.load(assetFilePath(ctx, realtimeAsset))
    }

    private fun loadOfflineIfNeeded() {
        if (offModule != null) return
        offModule = Module.load(assetFilePath(ctx, offlineAsset))
    }

    data class InferResult(
        val sevIdx: Int,
        val p: FloatArray,     // 4
        val u: Float,
        val defer: Boolean,
        val bin15Prob: Float,
        val bin30Prob: Float,
        val debug: String
    )

    /**
     * 实时：输入 xFlat shape = [1, 10, 2, 64, 64]
     */
    fun inferRealtime10x64(xFlat: FloatArray): InferResult {
        loadRealtimeIfNeeded()
        val expect = 1 * RT_K * RT_C * RT_H * RT_W
        require(xFlat.size == expect) {
            "Bad input size=${xFlat.size}, expect=$expect (1x$RT_K x$RT_C x$RT_H x$RT_W)"
        }

        val input = Tensor.fromBlob(
            xFlat,
            longArrayOf(1L, RT_K.toLong(), RT_C.toLong(), RT_H.toLong(), RT_W.toLong())
        )

        val tuple = rtModule!!.forward(IValue.from(input)).toTuple()
        return parseModelTuple(tuple, tag = "RT")
    }

    /**
     * 离线：输入 xFlat shape = [1, 128, 2, 80, 80]
     */
    fun inferOffline128x80(xFlat: FloatArray): InferResult {
        loadOfflineIfNeeded()
        val expect = 1 * OFF_K * OFF_C * OFF_H * OFF_W
        require(xFlat.size == expect) {
            "Bad input size=${xFlat.size}, expect=$expect (1x$OFF_K x$OFF_C x$OFF_H x$OFF_W)"
        }

        val input = Tensor.fromBlob(
            xFlat,
            longArrayOf(1L, OFF_K.toLong(), OFF_C.toLong(), OFF_H.toLong(), OFF_W.toLong())
        )

        val tuple = offModule!!.forward(IValue.from(input)).toTuple()
        return parseModelTuple(tuple, tag = "OFF")
    }

    /**
     * 兼容 tuple 长度：
     * - 4 outputs: (sev_logits_evi, b15, b30, u)
     * - 5 outputs: (sev_logits_evi, b15, b30, event_logit, u)
     */
    private fun parseModelTuple(out: Array<IValue>, tag: String): InferResult {
        require(out.size == 4 || out.size == 5) {
            "Unexpected output tuple size=${out.size}, expect 4 or 5"
        }

        val sevLogitsEvi = out[0].toTensor().dataAsFloatArray
        val b15Logit = out[1].toTensor().dataAsFloatArray[0]
        val b30Logit = out[2].toTensor().dataAsFloatArray[0]
        val u = if (out.size == 4) {
            out[3].toTensor().dataAsFloatArray[0]
        } else {
            out[4].toTensor().dataAsFloatArray[0]
        }

        // sev logits: [1,4] or flat=4; 稳妥取最后 4 个
        val sev4 = if (sevLogitsEvi.size >= 4) sevLogitsEvi.takeLast(4).toFloatArray() else sevLogitsEvi
        require(sev4.size == 4) { "sev logits invalid size=${sev4.size}" }

        // ✅ 关键：Evidential -> Dirichlet mean probability (NOT softmax)
        val (p, uFromAlpha) = evidentialToProbAndU(sev4)

        val sevIdx = argmax4(p)
        val bin15Prob = sigmoid(b15Logit)
        val bin30Prob = sigmoid(b30Logit)
        val defer = u >= DEFERRAL_U_THR

        val dbg = buildString {
            append("[$tag] sev_evi_logits=")
            append(sev4.joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) })
            append(" p=")
            append(p.joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) })
            append(" u=").append("%.3f".format(u))
            append(" u(alpha)=").append("%.3f".format(uFromAlpha))
            append(" b15=").append("%.3f".format(bin15Prob))
            append(" b30=").append("%.3f".format(bin30Prob))
            append(" tuple=").append(out.size)
        }

        return InferResult(
            sevIdx = sevIdx,
            p = p,
            u = u,
            defer = defer,
            bin15Prob = bin15Prob,
            bin30Prob = bin30Prob,
            debug = dbg
        )
    }

    /**
     * evidential_dirichlet_loss 里用的：
     * e = softplus(logits) + eps
     * alpha = e + 1
     * p = alpha / sum(alpha)
     * u = K / sum(alpha)
     */
    private fun evidentialToProbAndU(logitsEvi4: FloatArray): Pair<FloatArray, Float> {
        val alpha = FloatArray(4)
        var sumAlpha = 0f
        for (i in 0 until 4) {
            val e = softplus(logitsEvi4[i]) + EVI_EPS
            val a = e + 1f
            alpha[i] = a
            sumAlpha += a
        }
        val p = FloatArray(4) { alpha[it] / (sumAlpha.coerceAtLeast(1e-6f)) }
        val u = 4f / (sumAlpha.coerceAtLeast(1e-6f))
        return p to u
    }

    // ---------- math helpers ----------
    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x)))

    private fun softplus(x: Float): Float {
        // stable softplus: log(1+exp(x))
        return if (x > 20f) x else ln(1f + exp(x))
    }

    private fun argmax4(p: FloatArray): Int {
        var best = 0
        var bv = p[0]
        for (i in 1 until 4) {
            if (p[i] > bv) { bv = p[i]; best = i }
        }
        return best
    }

    // ---------- asset -> file path ----------
    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) return file.absolutePath

        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                }
                output.flush()
            }
        }
        return file.absolutePath
    }
}
