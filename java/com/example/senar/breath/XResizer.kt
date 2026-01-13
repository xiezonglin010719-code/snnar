package com.example.senar.breath

/**
 * 把模型输入 xFlat 从 [1,K,C,H,W] resize 到 [1,K,C,H2,W2]
 * 仅做推理前的尺寸适配（在线/离线统一输入给同一个模型）。
 *
 * 默认用双线性插值（bilinear），足够稳定。
 */
object XResizer {

    fun resizeXFlatBilinear(
        xFlat: FloatArray,
        K: Int,
        C: Int,
        H: Int,
        W: Int,
        H2: Int,
        W2: Int
    ): FloatArray {
        require(xFlat.size == 1 * K * C * H * W) {
            "Bad xFlat size=${xFlat.size}, expect=${1 * K * C * H * W}"
        }
        if (H == H2 && W == W2) return xFlat

        val out = FloatArray(1 * K * C * H2 * W2)
        val inStride = H * W
        val outStride = H2 * W2

        var inBase = 0
        var outBase = 0
        for (k in 0 until K) {
            for (c in 0 until C) {
                // src: xFlat[inBase .. inBase+H*W)
                // dst: out[outBase .. outBase+H2*W2)
                resize2DBilinear(
                    src = xFlat,
                    srcOff = inBase,
                    srcH = H,
                    srcW = W,
                    dst = out,
                    dstOff = outBase,
                    dstH = H2,
                    dstW = W2
                )
                inBase += inStride
                outBase += outStride
            }
        }
        return out
    }

    private fun resize2DBilinear(
        src: FloatArray,
        srcOff: Int,
        srcH: Int,
        srcW: Int,
        dst: FloatArray,
        dstOff: Int,
        dstH: Int,
        dstW: Int
    ) {
        // align_corners=false 风格
        for (y2 in 0 until dstH) {
            val fy = ((y2 + 0.5f) * srcH / dstH) - 0.5f
            val y0 = fy.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceIn(0, srcH - 1)
            val wy = fy - y0

            for (x2 in 0 until dstW) {
                val fx = ((x2 + 0.5f) * srcW / dstW) - 0.5f
                val x0 = fx.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                val wx = fx - x0

                val v00 = src[srcOff + y0 * srcW + x0]
                val v01 = src[srcOff + y0 * srcW + x1]
                val v10 = src[srcOff + y1 * srcW + x0]
                val v11 = src[srcOff + y1 * srcW + x1]

                val v0 = v00 + (v01 - v00) * wx
                val v1 = v10 + (v11 - v10) * wx
                val v = v0 + (v1 - v0) * wy

                dst[dstOff + y2 * dstW + x2] = v
            }
        }
    }
}
