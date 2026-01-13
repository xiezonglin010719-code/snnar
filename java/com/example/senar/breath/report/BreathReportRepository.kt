package com.example.senar.breath.reports

import android.content.Context
import com.example.senar.breath.FiveMinResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 呼吸报告：轻量本地存储（JSON 文件）
 * - 不依赖你 sonar 的 SessionRepository / Room
 * - ONLINE/OFFLINE 都统一写入
 */
class BreathReportRepository(private val ctx: Context) {

    companion object {
        private const val DIR = "breath_reports"
        private const val INDEX = "index.json"

        fun from(ctx: Context) = BreathReportRepository(ctx)
    }

    enum class Source { ONLINE, OFFLINE }

    data class BreathSession(
        val id: String,
        val dayKey: String,            // yyyy-MM-dd（用于今晚/历史分组）
        val createdAtMs: Long,
        val source: Source,
        val label: String,             // e.g. "离线NPZ:xxx.npz" / "在线实时"
        val timeline: List<FiveMinResult>
    )

    private fun dir(): File {
        val d = File(ctx.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun indexFile(): File = File(dir(), INDEX)

    private fun dayKeyOf(ts: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(ts))
    }

    /**
     * 保存一次 session（在线/离线都走这里）
     */
    fun saveSession(
        source: Source,
        label: String,
        timeline: List<FiveMinResult>,
        dayKey: String? = null,
        createdAtMs: Long = System.currentTimeMillis()
    ): String {
        val id = UUID.randomUUID().toString()
        val dk = dayKey ?: dayKeyOf(createdAtMs)

        val obj = JSONObject().apply {
            put("id", id)
            put("dayKey", dk)
            put("createdAtMs", createdAtMs)
            put("source", source.name)
            put("label", label)
            put("timeline", JSONArray().apply {
                timeline.forEach { r ->
                    put(JSONObject().apply {
                        put("tsMs", r.tsMs)
                        put("sevIdx", r.sevIdx)
                        put("p", JSONArray().apply { r.p.forEach { put(it) } })
                        put("q", r.q)
                        put("uModel", r.uModel)
                        put("uEff", r.uEff)
                        put("weight", r.weight)
                        put("defer", r.defer)
                        put("action", r.action.name)
                        put("reason", r.reason)
                        put("debug", r.debug)
                    })
                }
            })
        }

        // session 文件
        File(dir(), "session_$id.json").writeText(obj.toString())

        // index 追加
        val idx = loadIndexMutable()
        idx.put(JSONObject().apply {
            put("id", id)
            put("dayKey", dk)
            put("createdAtMs", createdAtMs)
            put("source", source.name)
            put("label", label)
        })
        indexFile().writeText(idx.toString())

        return id
    }

    fun latestSessionForDay(dayKey: String): BreathSession? {
        val metas = loadIndexMetas()
            .filter { it.optString("dayKey") == dayKey }
            .sortedByDescending { it.optLong("createdAtMs") }

        for (m in metas) {
            val id = m.optString("id")
            val s = loadSession(id)
            if (s != null) return s
        }
        return null
    }

    fun latestSession(): BreathSession? {
        val metas = loadIndexMetas()
            .sortedByDescending { it.optLong("createdAtMs") }

        for (m in metas) {
            val id = m.optString("id")
            val s = loadSession(id)
            if (s != null) return s
        }
        return null
    }

    /**
     * 历史：按天分组，返回 (dayKey -> list sessions)
     */
    fun listGroupedByDay(limitDays: Int = 60): Map<String, List<BreathSession>> {
        val metas = loadIndexMetas()
            .sortedByDescending { it.optLong("createdAtMs") }

        val map = linkedMapOf<String, MutableList<BreathSession>>()
        for (m in metas) {
            val id = m.optString("id")
            val s = loadSession(id) ?: continue
            map.getOrPut(s.dayKey) { mutableListOf() }.add(s)
            if (map.size >= limitDays) break
        }
        return map
    }

    fun loadSession(id: String): BreathSession? {
        val f = File(dir(), "session_$id.json")
        if (!f.exists()) return null
        val obj = JSONObject(f.readText())

        val timelineArr = obj.getJSONArray("timeline")
        val timeline = ArrayList<FiveMinResult>(timelineArr.length())
        for (i in 0 until timelineArr.length()) {
            val r = timelineArr.getJSONObject(i)
            val pArr = r.getJSONArray("p")
            val p = FloatArray(pArr.length())
            for (k in 0 until pArr.length()) p[k] = pArr.getDouble(k).toFloat()

            // action / reason 需要可用：这里用 name 反查
            val actionName = r.optString("action", "OK")
            val action = try {
                com.example.senar.breath.CredibilityController.Action.valueOf(actionName)
            } catch (_: Throwable) {
                com.example.senar.breath.CredibilityController.Action.OK
            }

            timeline.add(
                FiveMinResult(
                    tsMs = r.optLong("tsMs"),
                    sevIdx = r.optInt("sevIdx"),
                    p = p,
                    q = r.optDouble("q").toFloat(),
                    uModel = r.optDouble("uModel").toFloat(),
                    uEff = r.optDouble("uEff").toFloat(),
                    weight = r.optDouble("weight").toFloat(),
                    defer = r.optBoolean("defer"),
                    action = action,
                    reason = r.optString("reason"),
                    debug = r.optString("debug")
                )
            )
        }

        return BreathSession(
            id = obj.optString("id"),
            dayKey = obj.optString("dayKey"),
            createdAtMs = obj.optLong("createdAtMs"),
            source = Source.valueOf(obj.optString("source", Source.ONLINE.name)),
            label = obj.optString("label"),
            timeline = timeline
        )
    }

    private fun loadIndexMutable(): JSONArray {
        val f = indexFile()
        if (!f.exists()) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (_: Throwable) {
            JSONArray()
        }
    }

    private fun loadIndexMetas(): List<JSONObject> {
        val arr = loadIndexMutable()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o)
        }
        return out
    }

    fun todayKey(): String = dayKeyOf(System.currentTimeMillis())
}
