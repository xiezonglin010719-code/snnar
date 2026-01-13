package com.example.senar.core.mapper

import com.example.senar.core.model.DiagMode
import com.example.senar.core.model.DiagnosisFrame
import com.example.senar.core.model.SleepEvent

object FrameMapper {

    private fun n2f(v: Any?): Float? = (v as? Number)?.toFloat()

    fun anyToFloatArray(any: Any?): FloatArray {
        return when (any) {
            is FloatArray -> any
            is DoubleArray -> any.map { it.toFloat() }.toFloatArray()
            is IntArray -> any.map { it.toFloat() }.toFloatArray()
            is List<*> -> any.mapNotNull { (it as? Number)?.toFloat() }.toFloatArray()
            is Array<*> -> any.mapNotNull { (it as? Number)?.toFloat() }.toFloatArray()
            else -> FloatArray(0)
        }
    }

    fun mapToFrame(
        features: Map<String, Any?>,
        tsMs: Long,
        mode: DiagMode
    ): DiagnosisFrame {

        val breath = n2f(features["breath_freq"]) ?: 0f

        val qIdx = n2f(features["quality_index"])
        val qGrade = (features["quality_grade"] as? Number)?.toInt()
        val qLabel = features["quality_label"]?.toString()

        val snrDb = n2f(features["snr_db"])
        val coverage = n2f(features["coverage"])
        val usable = (features["usable_for_ahi"] as? Boolean)

        val ahiTotal = (n2f(features["ahi_total"]) ?: n2f(features["ahi_est"]) ?: 0f)
        val ahiC = n2f(features["ahi_central"]) ?: 0f
        val ahiO = n2f(features["ahi_obstructive"]) ?: 0f
        val ahiH = n2f(features["ahi_hypopnea"]) ?: 0f

        val diagnosis = features["diagnosis"]?.toString()

        val envAny = features["resp_env_plot"]
            ?: features["envelope_plot"]
            ?: features["envelope"]

        val env = anyToFloatArray(envAny)

        return DiagnosisFrame(
            tsMs = tsMs,
            mode = mode,
            breathBpm = breath,
            qualityIndex = qIdx,
            qualityGrade = qGrade,
            qualityLabel = qLabel,
            snrDb = snrDb,
            coverage = coverage,
            usableForAhi = usable,
            ahiTotal = ahiTotal,
            ahiCentral = ahiC,
            ahiObstructive = ahiO,
            ahiHypopnea = ahiH,
            diagnosis = diagnosis,
            respEnv = env
        )
    }

    fun mapToEvents(events: List<Map<String, Any?>>): List<SleepEvent> {
        val out = ArrayList<SleepEvent>(events.size)
        for (ev in events) {
            val type = ev["type"]?.toString() ?: continue
            val start = n2f(ev["start_sec"]) ?: continue
            val end = n2f(ev["end_sec"]) ?: continue
            val conf = n2f(ev["confidence"])
            out.add(SleepEvent(type = type, startSec = start, endSec = end, confidence = conf))
        }
        return out
    }
}
