package com.example.senar

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

object SleepSessionManager {

    private const val TAG = "SleepSessionManager"

    data class EventRecord(
        val type: String,
        val startSec: Float,
        val endSec: Float
    )

    data class SessionStats(
        var startEpochMs: Long = 0L,
        var endEpochMs: Long = 0L,
        var frameCount: Int = 0,

        var breathSum: Double = 0.0,
        var breathMin: Float = Float.POSITIVE_INFINITY,
        var breathMax: Float = Float.NEGATIVE_INFINITY,

        var qualitySum: Double = 0.0,
        var qualityMin: Float = Float.POSITIVE_INFINITY,
        var qualityMax: Float = Float.NEGATIVE_INFINITY,
        var qualityCount: Int = 0,

        var ahiTotal: Float = 0f,
        var ahiCentral: Float = 0f,
        var ahiObstructive: Float = 0f,
        var ahiHypopnea: Float = 0f
    )

    // 当前 Session 状态
    @Volatile
    private var isRunning = AtomicBoolean(false)

    @Volatile
    private var stats: SessionStats? = null

    // 去重用：type|startSecRounded|endSecRounded
    private val eventKeySet = mutableSetOf<String>()
    private val eventList = mutableListOf<EventRecord>()

    // -------------------- 外部 API --------------------

    /** 开启新的整夜 Session，在 startDiagnosis 的时候调用 */
    @Synchronized
    fun startNewSession() {
        val now = System.currentTimeMillis()
        stats = SessionStats(startEpochMs = now, endEpochMs = now)
        eventKeySet.clear()
        eventList.clear()
        isRunning.set(true)
        Log.d(TAG, "startNewSession at $now")
    }

    /** 停止 Session，并将统计信息落盘，返回保存的文件 */
    @Synchronized
    fun endSessionAndPersist(context: Context): File? {
        if (!isRunning.get()) {
            Log.w(TAG, "endSessionAndPersist: no active session")
            return null
        }
        val s = stats ?: return null
        s.endEpochMs = System.currentTimeMillis()
        isRunning.set(false)

        // 构建 JSON
        val json = JSONObject()
        json.put("startEpochMs", s.startEpochMs)
        json.put("endEpochMs", s.endEpochMs)
        val durationSec = (s.endEpochMs - s.startEpochMs) / 1000.0
        json.put("durationSec", durationSec)

        // 呼吸统计
        if (s.frameCount > 0) {
            val meanBreath = s.breathSum / s.frameCount
            json.put("breath_mean", meanBreath)
            json.put("breath_min", if (s.breathMin.isFinite()) s.breathMin else JSONObject.NULL)
            json.put("breath_max", if (s.breathMax.isFinite()) s.breathMax else JSONObject.NULL)
        } else {
            json.put("breath_mean", JSONObject.NULL)
            json.put("breath_min", JSONObject.NULL)
            json.put("breath_max", JSONObject.NULL)
        }

        // 信号质量统计
        if (s.qualityCount > 0) {
            val meanQ = s.qualitySum / s.qualityCount
            json.put("quality_mean", meanQ)
            json.put("quality_min", if (s.qualityMin.isFinite()) s.qualityMin else JSONObject.NULL)
            json.put("quality_max", if (s.qualityMax.isFinite()) s.qualityMax else JSONObject.NULL)
        } else {
            json.put("quality_mean", JSONObject.NULL)
            json.put("quality_min", JSONObject.NULL)
            json.put("quality_max", JSONObject.NULL)
        }

        // AHI（最后一帧的估计）
        json.put("ahi_total", s.ahiTotal.toDouble())
        json.put("ahi_central", s.ahiCentral.toDouble())
        json.put("ahi_obstructive", s.ahiObstructive.toDouble())
        json.put("ahi_hypopnea", s.ahiHypopnea.toDouble())

        // 事件时间线
        val arrEvents = JSONArray()
        for (ev in eventList) {
            val o = JSONObject()
            o.put("type", ev.type)
            o.put("start_sec", ev.startSec.toDouble())
            o.put("end_sec", ev.endSec.toDouble())
            arrEvents.put(o)
        }
        json.put("events", arrEvents)

        // 保存路径：/data/data/包名/files/sessions/session_xxx.json
        val dir = File(context.filesDir, "sessions")
        if (!dir.exists()) dir.mkdirs()

        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val name = "session_${sdf.format(s.startEpochMs)}.json"
        val outFile = File(dir, name)

        outFile.writeText(json.toString(2))
        Log.d(TAG, "Session saved to: ${outFile.absolutePath}")
        return outFile
    }

    /** 每帧调用：记录 AHI / 呼吸 / 质量 / 事件时间线 */
    @Synchronized
    fun recordFrame(features: Map<String, Any?>, events: List<Map<String, Any?>>) {
        if (!isRunning.get()) return
        val s = stats ?: return

        s.endEpochMs = System.currentTimeMillis()
        s.frameCount += 1

        // 呼吸频率
        val breath = (features["breath_freq"] as? Number)?.toFloat() ?: 0f
        if (breath > 0f) {
            s.breathSum += breath.toDouble()
            s.breathMin = min(s.breathMin, breath)
            s.breathMax = max(s.breathMax, breath)
        }

        // 质量
        val q = (features["quality_index"] as? Number)?.toFloat()
        if (q != null && q >= 0f) {
            s.qualitySum += q.toDouble()
            s.qualityCount += 1
            s.qualityMin = min(s.qualityMin, q)
            s.qualityMax = max(s.qualityMax, q)
        }

        // AHI：用最新帧覆盖
        val ahiTotal = (features["ahi_total"] as? Number)?.toFloat()
            ?: (features["ahi_est"] as? Number)?.toFloat()
            ?: 0f
        val ahiCentral = (features["ahi_central"] as? Number)?.toFloat() ?: 0f
        val ahiObst = (features["ahi_obstructive"] as? Number)?.toFloat() ?: 0f
        val ahiHypo = (features["ahi_hypopnea"] as? Number)?.toFloat() ?: 0f

        s.ahiTotal = ahiTotal
        s.ahiCentral = ahiCentral
        s.ahiObstructive = ahiObst
        s.ahiHypopnea = ahiHypo

        // 事件：按 “type|start_round|end_round” 去重
        for (ev in events) {
            val type = ev["type"]?.toString() ?: continue
            val start = (ev["start_sec"] as? Number)?.toFloat() ?: continue
            val end = (ev["end_sec"] as? Number)?.toFloat() ?: continue
            val key = "$type|${start.toInt()}|${end.toInt()}"
            if (!eventKeySet.contains(key)) {
                eventKeySet.add(key)
                eventList.add(EventRecord(type, start, end))
            }
        }
    }

    /** 找到最近一次 Session 文件，用于“本晚报告”页面 */
    fun findLatestSessionFile(context: Context): File? {
        val dir = File(context.filesDir, "sessions")
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("session_") && f.name.endsWith(".json") }
            ?: return null
        if (files.isEmpty()) return null
        return files.maxByOrNull { it.lastModified() }
    }
}
