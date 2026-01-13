package com.example.senar.breath.offline

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

// OfflineConfig.kt（或直接写在 NpzBridge 里）
object OfflineConfig {
    const val MODEL_K = 96        // 模型固定
    const val MODEL_C = 2
    const val MODEL_H = 64
    const val MODEL_W = 64

    const val SOURCE_H = 224      // NPZ 原始
    const val SOURCE_W = 224

    const val STRIDE = 96         // 不重叠（测试期最稳）
    // 想更细：48 / 32 都可以
}



object NpzBridge {

    private const val PY_MOD = "breath_npz_bridge"

    private fun ensurePy(ctx: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(ctx))
        }
    }

    fun copyUriToCache(ctx: Context, uri: android.net.Uri, outName: String): File {
        val out = File(ctx.cacheDir, outName)
        ctx.contentResolver.openInputStream(uri).use { ins ->
            requireNotNull(ins) { "openInputStream=null" }
            FileOutputStream(out).use { fos -> ins.copyTo(fos) }
        }
        return out
    }

    /** 打开 npz（会把 X.npy 解压到 cache，并 mmap），返回 handleId */
    fun open(ctx: Context, npzPath: String, xKey: String = "X"): String {
        ensurePy(ctx)
        val py = Python.getInstance()
        val m = py.getModule(PY_MOD)
        // open_npz(android_ctx, npz_path, x_key)
        return m.callAttr("open_npz", ctx, npzPath, xKey).toString()
    }

    /** 段数 = X 的第 0 维长度（N）。只传 handleId -> 不会再 TypeError */
    fun count(ctx: Context, handleId: String): Int {
        ensurePy(ctx)
        val py = Python.getInstance()
        val m = py.getModule(PY_MOD)
        return m.callAttr("segmentcount", handleId).toInt()
    }

    fun readSegmentFloat32Flat(
        ctx: Context,
        handleId: String,
        segIdx: Int,
        K: Int, C: Int, H: Int, W: Int,
    ): FloatArray {
        ensurePy(ctx)
        val py = Python.getInstance()
        val m = py.getModule(PY_MOD)
        val obj = m.callAttr("read_segment_float32_flat", handleId, segIdx, K, C, H, W)
        @Suppress("UNCHECKED_CAST")
        return obj.toJava(FloatArray::class.java) as FloatArray
    }

    fun close(ctx: Context, handleId: String) {
        runCatching {
            ensurePy(ctx)
            val py = Python.getInstance()
            val m = py.getModule(PY_MOD)
            m.callAttr("close", handleId)
        }
    }
}
