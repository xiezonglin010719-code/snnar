package com.example.senar.breath.utils

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

object HeatmapBitmap {

    /**
     * 把 64x64 的 FloatArray 转成灰度 Bitmap
     */
    fun from64x64(
        data: FloatArray,
        width: Int = 64,
        height: Int = 64
    ): Bitmap {
        require(data.size == width * height)

        // 1) 找 min/max（你截图里已经在 debug 打印过）
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in data) {
            mn = min(mn, v)
            mx = max(mx, v)
        }
        val range = (mx - mn).takeIf { it > 1e-6f } ?: 1f

        // 2) 创建 Bitmap
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 3) 灰度映射（你也可以换成彩色 colormap）
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = data[y * width + x]
                val norm = ((v - mn) / range).coerceIn(0f, 1f)
                val g = (norm * 255).toInt()
                bmp.setPixel(x, y, Color.rgb(g, g, g))
            }
        }
        return bmp
    }
}
