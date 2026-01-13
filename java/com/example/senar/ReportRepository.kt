package com.example.senar

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReportSession(
    val streamId: String,
    val lastNpz: File,
    val lastTsSec: Long,
    val dayKey: String, // yyyy-MM-dd
)

object ReportRepository {

    private val sdfDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // 你 AudioProcessingService 里传给 Python 的 saveDir = filesDir/sonar_spec
    private fun sonarSpecDir(ctx: Context): File =
        File(ctx.filesDir, "sonar_spec").apply { if (!exists()) mkdirs() }

    /**
     * 扫描 rt_{streamId}_{ts}.npz
     * 返回：每个 streamId 只保留“最后一次”的 npz
     */
    fun loadAllSessions(ctx: Context): List<ReportSession> {
        val dir = sonarSpecDir(ctx)
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("rt_") && f.name.endsWith(".npz")
        }?.toList().orEmpty()

        // rt_{streamId}_{ts}.npz
        val map = HashMap<String, ReportSession>()

        for (f in files) {
            val name = f.name.removeSuffix(".npz")
            val parts = name.split("_")
            if (parts.size < 3) continue

            val ts = parts.last().toLongOrNull() ?: continue
            val streamId = parts.subList(1, parts.size - 1).joinToString("_")

            val dayKey = sdfDay.format(Date(ts * 1000L))
            val cur = map[streamId]
            if (cur == null || ts > cur.lastTsSec) {
                map[streamId] = ReportSession(streamId, f, ts, dayKey)
            }
        }

        return map.values.sortedByDescending { it.lastTsSec }
    }

    fun loadSessionsByDay(ctx: Context, dayKey: String): List<ReportSession> {
        return loadAllSessions(ctx).filter { it.dayKey == dayKey }
    }
}
