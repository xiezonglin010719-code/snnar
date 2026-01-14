package com.example.senar.ai

import android.content.Context
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File

data class NpzSummary(
    val ahi: Double? = null,
    val eventCount: Int? = null,
    val eventDurMeanSec: Double? = null,
    val eventDurP95Sec: Double? = null,
    val eventDurMaxSec: Double? = null,
    val validSleepMin: Double? = null,
    val keys: List<String> = emptyList()
)

object NpzAnalyzer {
    fun summarize(context: Context, npz: File): NpzSummary {
        val py = Python.getInstance()
        val mod = py.getModule("npz_insights")
        val jsonStr = mod.callAttr("summarize_npz", npz.absolutePath).toString()
        val o = JSONObject(jsonStr)

        fun optD(k: String): Double? = if (o.has(k)) o.optDouble(k) else null
        fun optI(k: String): Int? = if (o.has(k)) o.optInt(k) else null

        val keys = mutableListOf<String>()
        if (o.has("keys")) {
            val arr = o.getJSONArray("keys")
            for (i in 0 until arr.length()) keys += arr.getString(i)
        }

        return NpzSummary(
            ahi = optD("ahi"),
            eventCount = optI("event_count"),
            eventDurMeanSec = optD("event_dur_mean_sec"),
            eventDurP95Sec = optD("event_dur_p95_sec"),
            eventDurMaxSec = optD("event_dur_max_sec"),
            validSleepMin = optD("valid_sleep_min"),
            keys = keys
        )
    }
}
