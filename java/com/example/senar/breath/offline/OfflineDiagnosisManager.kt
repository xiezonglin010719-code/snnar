package com.example.senar.breath.offline

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.chaquo.python.Python
import com.example.senar.breath.BreathInferenceEngine
import java.io.File
import java.io.FileOutputStream

data class OfflineDiagnosisResult(
    val label: String,
    val sevIdx: Int,
    val p: FloatArray,   // 4
    val u: Float,
    val defer: Boolean,
    val debug: String
)

class OfflineDiagnosisManager(
    private val ctx: Context,
    private val engine: BreathInferenceEngine,
) {

    /** 把 SAF Uri 拷贝到 cache，返回 File */
    fun copyToCache(uri: Uri): File {
        val name = queryName(ctx.contentResolver, uri) ?: "input.bin"
        val out = File(ctx.cacheDir, "offline_$name")
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "openInputStream failed" }
            FileOutputStream(out).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        }
        return out
    }

    /**
     * 离线跑一次：
     * - npz: 直接 python->x_flat
     * - mp4: 这里先不直接 python 读 mp4（太不稳定），建议你先做“抽音频成 wav”的 Kotlin 步骤；
     *        为了你“能跑通”，这里先要求用户上传 npz 或 wav。
     */
    fun runOnce(file: File): OfflineDiagnosisResult {
        val ext = file.extension.lowercase()
        require(ext == "npz" || ext == "wav") {
            "仅支持 .npz 或 .wav（mp4 请先转 wav 或 npz）"
        }

        val py = Python.getInstance()
        val mod = py.getModule("breath_offline")
        val ret = mod.callAttr("prepare_input", file.absolutePath).toJava(Map::class.java) as Map<*, *>

        val xList = ret["x_flat"] as List<*>
        val meta = (ret["meta"] as? String) ?: ""

        val xFlat = FloatArray(xList.size)
        for (i in xList.indices) xFlat[i] = (xList[i] as Number).toFloat()

        val infer = engine.inferOffline128x80(xFlat)


        val label = when (infer.sevIdx) {
            0 -> "Normal(<5)"
            1 -> "Mild(5-15)"
            2 -> "Moderate(15-30)"
            else -> "Severe(>=30)"
        }

        return OfflineDiagnosisResult(
            label = label,
            sevIdx = infer.sevIdx,
            p = infer.p,
            u = infer.u,
            defer = infer.defer,
            debug = "meta=$meta\n" + infer.debug
        )
    }

    private fun queryName(cr: ContentResolver, uri: Uri): String? {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }
}
