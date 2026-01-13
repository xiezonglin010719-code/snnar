package com.example.senar.breath.offline

import android.content.Context
import android.net.Uri
import java.util.zip.ZipInputStream
import kotlin.math.min

/**
 * ✅ NPZ = ZIP of NPY
 * 期望读到 key: "X.npy"（或 "X" 对应的 npy）。
 * 你训练用的 X shape：通常是 [N,C,H,W] 或 [K,C,H,W]
 * 我这里最终统一成模型输入 [1,96,2,64,64] 扁平 FloatArray
 */
object NpzReader {

    // 必须与你 TorchScript 模型一致
    private const val K = 96
    private const val C = 2
    private const val H = 64
    private const val W = 64

    fun readXFromNpz(ctx: Context, uri: Uri): FloatArray {
        val entries = HashMap<String, Pair<LongArray, FloatArray>>() // name -> (shape, data)
        ctx.contentResolver.openInputStream(uri).use { ins ->
            requireNotNull(ins) { "openInputStream failed" }
            ZipInputStream(ins).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val name = e.name
                    if (!name.endsWith(".npy")) continue
                    val bytes = zis.readBytes()
                    val (shape, data) = NpyReader.readFloat32Npy(bytes)
                    val key = name.substringAfterLast('/').removeSuffix(".npy")
                    entries[key] = shape to data
                }
            }
        }

        val xPair = entries["X"] ?: error("NPZ 中找不到 X.npy")
        val shape = xPair.first
        val data = xPair.second

        // 支持 [K,C,H,W] 或 [N,C,H,W] 或 [N,H,W] 或 [N,1,H,W]
        val x4 = reshapeTo4D(shape, data) // FloatArray + dims
        return toModelInput1KCHW(x4.dims, x4.data)
    }

    data class X4(val dims: IntArray, val data: FloatArray)

    private fun reshapeTo4D(shape: LongArray, data: FloatArray): X4 {
        val dims = shape.map { it.toInt() }.toIntArray()
        return when (dims.size) {
            4 -> X4(dims, data) // [N,C,H,W]
            3 -> { // [N,H,W] -> [N,1,H,W]
                val n = dims[0]; val h = dims[1]; val w = dims[2]
                X4(intArrayOf(n, 1, h, w), data)
            }
            else -> error("不支持的 X 维度：${dims.joinToString()}（只支持 3D/4D）")
        }
    }

    private fun toModelInput1KCHW(dims: IntArray, flat: FloatArray): FloatArray {
        val n = dims[0]
        val c = dims[1]
        val h = dims[2]
        val w = dims[3]

        // 输出：1*K*C*H*W
        val out = FloatArray(1 * K * C * H * W)

        fun idx4(iN: Int, iC: Int, iH: Int, iW: Int): Int {
            return ((iN * c + iC) * h + iH) * w + iW
        }

        // 取 K 个窗口：均匀采样 + pad
        val sel = IntArray(K) { k ->
            if (n <= 1) 0 else ((k.toDouble() / (K - 1).coerceAtLeast(1)) * (n - 1)).toInt()
        }

        for (k in 0 until K) {
            val srcN = sel[k].coerceIn(0, n - 1)

            for (cc in 0 until C) {
                val srcC = when {
                    c == 1 -> 0
                    cc < c -> cc
                    else -> c - 1
                }

                // center crop/pad 到 64
                for (yy in 0 until H) {
                    val sy = mapCenter(yy, H, h)
                    for (xx in 0 until W) {
                        val sx = mapCenter(xx, W, w)
                        val v = if (sy in 0 until h && sx in 0 until w) {
                            flat[idx4(srcN, srcC, sy, sx)]
                        } else 0f
                        val outIdx = ((((0 * K + k) * C + cc) * H + yy) * W + xx)
                        out[outIdx] = v
                    }
                }
            }
        }
        return out
    }

    private fun mapCenter(dst: Int, dstSize: Int, srcSize: Int): Int {
        // 把 dst 中心对齐到 src：中心裁剪/补零
        val srcStart = (srcSize - dstSize) / 2
        return dst + srcStart
    }

    /**
     * ✅ MP4 的 mel 只有一段 30s（[128,T]），你训练是 [K,C,64,64]
     * 这里给“占位但能跑”的拼装：把 mel 缩放到 64x64，当做 ch0；
     * ch1 先全 0（你说辅助图落盘形式 room，后面你接“ch1事件图”再填）
     */
    fun packMelToModelInput(mel128xT: Array<FloatArray>): FloatArray {
        require(mel128xT.size == 128) { "mel must be [128,T]" }
        val T = mel128xT[0].size
        val out = FloatArray(1 * K * C * H * W)

        // 先做一个 64x64 的缩放图
        val img0 = FloatArray(H * W)
        for (yy in 0 until H) {
            val sy = (yy.toDouble() / (H - 1).coerceAtLeast(1) * 127.0).toInt().coerceIn(0, 127)
            for (xx in 0 until W) {
                val sx = (xx.toDouble() / (W - 1).coerceAtLeast(1) * (T - 1).toDouble()).toInt().coerceIn(0, T - 1)
                img0[yy * W + xx] = mel128xT[sy][sx]
            }
        }

        // 复制到 K 个窗口：先全部用同一张（离线 mp4 先跑通）
        for (k in 0 until K) {
            for (yy in 0 until H) {
                for (xx in 0 until W) {
                    val base = ((((0 * K + k) * C + 0) * H + yy) * W + xx)
                    out[base] = img0[yy * W + xx]     // ch0
                    out[base + H * W] = 0f            // ch1
                }
            }
        }
        return out
    }
}
