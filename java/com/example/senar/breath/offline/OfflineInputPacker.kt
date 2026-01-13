package com.example.senar.breath.offline

object OfflineInputPacker {

    /**
     * 将 windows(每窗=2*80*80) 拼成 [128,2,80,80] flatten
     * - windows 可以是任意长度（>=1）
     * - 如果 <128：左侧补0
     * - 如果 >128：取最后128窗
     */
    fun packTo128x2x80x80(windows: List<FloatArray>): FloatArray {
        require(windows.isNotEmpty()) { "windows empty" }
        val stride = 2 * 80 * 80
        val out = FloatArray(128 * stride) { 0f }

        val take = minOf(128, windows.size)
        val start = windows.size - take
        val kBase = 128 - take

        for (i in 0 until take) {
            val w = windows[start + i]
            require(w.size == stride) { "bad window size=${w.size}, expect=$stride" }
            val dstOff = (kBase + i) * stride
            System.arraycopy(w, 0, out, dstOff, stride)
        }
        return out
    }
}
