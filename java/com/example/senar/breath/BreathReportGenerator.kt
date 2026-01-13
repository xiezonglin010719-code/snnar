package com.example.senar.breath

import kotlin.math.max

data class NightBreathReport(
    val startTsMs: Long,
    val endTsMs: Long,
    val overallSevIdx: Int,
    val overallP: FloatArray,     // 4
    val deferSegments: List<Segment>,
    val timeline: List<TimelineItem>,
    val summaryText: String,
)

data class Segment(val startTsMs: Long, val endTsMs: Long, val reason: String)

data class TimelineItem(
    val tsMs: Long,
    val sevIdx: Int,
    val p: FloatArray,
    val uEff: Float,     // ✅ 用融合后不确定度
    val defer: Boolean,
    val q: Float,        // ✅ 可选：报告显示质量
    val weight: Float,   // ✅ 可选：统计权重
    val reason: String,  // ✅ 可选：可解释原因
)

object BreathReportGenerator {

    fun generate(timeline: List<FiveMinResult>): NightBreathReport {
        if (timeline.isEmpty()) {
            return NightBreathReport(
                startTsMs = 0L, endTsMs = 0L,
                overallSevIdx = 0,
                overallP = floatArrayOf(1f, 0f, 0f, 0f),
                deferSegments = emptyList(),
                timeline = emptyList(),
                summaryText = "暂无呼吸监测数据"
            )
        }

        val start = timeline.first().tsMs
        val end = timeline.last().tsMs

        // 1) overall：按权重做加权平均
        // 推荐用统一模块给你的 weight（weight = (1-uEff)*q），比单纯 (1-u) 更稳
        val sum = FloatArray(4)
        var wSum = 0f
        for (r in timeline) {
            val w = r.weight.coerceIn(0f, 1f)
            wSum += w
            for (i in 0 until 4) sum[i] += w * r.p[i]
        }
        val overallP = FloatArray(4)
        val denom = max(1e-6f, wSum)
        for (i in 0 until 4) overallP[i] = sum[i] / denom
        val overallSev = argmax(overallP)

        // 2) defer 段落合并（连续 defer 合成 segment）
        val segs = mutableListOf<Segment>()
        var segStart: Long? = null
        var segReason: String = ""

        for (i in timeline.indices) {
            val r = timeline[i]
            if (r.defer) {
                if (segStart == null) {
                    segStart = r.tsMs
                    segReason = r.reason.ifBlank { "高不确定性/低质量（建议复测/调整放置）" }
                }
            } else {
                if (segStart != null) {
                    segs.add(Segment(segStart!!, timeline[i - 1].tsMs, segReason))
                    segStart = null
                    segReason = ""
                }
            }
        }
        if (segStart != null) {
            segs.add(Segment(segStart!!, timeline.last().tsMs, segReason.ifBlank { "高不确定性/低质量（建议复测/调整放置）" }))
        }

        // 3) timeline item（给 UI/报告展示）
        val items = timeline.map { r ->
            TimelineItem(
                tsMs = r.tsMs,
                sevIdx = r.sevIdx,
                p = r.p,
                uEff = r.uEff,         // ✅ 融合不确定度
                defer = r.defer,
                q = r.q,
                weight = r.weight,
                reason = r.reason
            )
        }

        val summary = buildString {
            append("整晚呼吸诊断（5min 间隔）\n")
            append("overall_sev=").append(overallSev).append('\n')
            append("overall_p=").append(overallP.joinToString(prefix="[", postfix="]"){ "%.2f".format(it) }).append('\n')
            append("usable_segments=").append(timeline.count { !it.defer }).append('/').append(timeline.size).append('\n')
            append("defer_segments=").append(segs.size).append('\n')
            append("weight_sum=").append("%.2f".format(wSum)).append('\n')
        }

        return NightBreathReport(
            startTsMs = start,
            endTsMs = end,
            overallSevIdx = overallSev,
            overallP = overallP,
            deferSegments = segs,
            timeline = items,
            summaryText = summary
        )
    }

    private fun argmax(p: FloatArray): Int {
        var bi = 0
        var bv = p[0]
        for (i in 1 until p.size) {
            if (p[i] > bv) { bv = p[i]; bi = i }
        }
        return bi
    }
}
