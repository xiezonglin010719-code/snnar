package com.example.senar.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.senar.core.repo.SessionRepository
import com.example.senar.diary.SleepDiaryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

data class UiChatMsg(val fromUser: Boolean, val text: String)

class SleepAssistantViewModel(
    private val appContext: Context,
    private val client: QwenClient,
    private val diaryDao: SleepDiaryDao,
    private val personId: String = "default"
) : ViewModel() {

    private val repo by lazy { SessionRepository.from(appContext) }

    private val _msgs = MutableStateFlow<List<UiChatMsg>>(emptyList())
    val msgs: StateFlow<List<UiChatMsg>> = _msgs

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun send(userText: String) {
        _msgs.value = _msgs.value + UiChatMsg(true, userText)

        viewModelScope.launch {
            _loading.value = true
            try {
                val reply = withContext(Dispatchers.IO) {
                    // 1) 解析时间范围（先给你一个稳定版：默认近7天；你后面要“3月/过去14天/3月3-3月10”
                    //    我再把 DateRangeResolver 升级成完整自然语言解析）
                    val days = parseDaysOrNull(userText) ?: 7
                    val range = lastNDaysRange(days)

                    // 2) 从 DB 聚合 “sessions + events”（按 dayKey + personId）
                    val sonarCtx = buildSonarContextFromDb(range, personId)

                    // 3) 生活方式（日记）作为关联因子（这块你已有 dao.latest）
                    val diary = runCatching { diaryDao.latest(minOf(14, days)) }.getOrDefault(emptyList())
                    val diaryText = ContextBuilder.diaryToText(diary)

                    val ctxText = """
【时间范围】${range.startDayKey} ~ ${range.endDayKey}（${range.days} 天）

$sonarCtx

$diaryText
                    """.trimIndent()

                    val messages = buildList {
                        add(SleepAssistantPrompts.system)

                        // ✅ 只有“真的需要个人数据”才塞 context
                        if (needPersonalData(userText)) {
                            add(SleepAssistantPrompts.context(ctxText))
                        }

                        add(SleepAssistantPrompts.user(userText))
                    }

                    client.chat(messages)
                }

                _msgs.value = _msgs.value + UiChatMsg(false, reply)
            } catch (e: Exception) {
                _msgs.value = _msgs.value + UiChatMsg(false, "请求失败：${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    // -------------------- DB 聚合：核心修复点 --------------------

    private suspend fun buildSonarContextFromDb(range: DayRange, personId: String): String {
        val dayKeys = range.dayKeys()
        val allSummaries = mutableListOf<com.example.senar.core.repo.SessionSummary>()

        for (dk in dayKeys) {
            // 你 repo 里就是按 dayKey + personId 查的：listSummariesByDay(dayKey, personId):contentReference[oaicite:4]{index=4}
            val sums = runCatching { repo.listSummariesByDay(dk, personId) }.getOrDefault(emptyList())
            allSummaries += sums
        }

        if (allSummaries.isEmpty()) {
            return "【声纳/鼾声监测（DB）】该范围内未找到 sleep_sessions（personId=$personId）。\n" +
                    "可能原因：① personId 不一致；② dayKey 格式不匹配；③ 这几天确实没跑诊断。"
        }

        // 聚合事件（你今晚报告就是从 sum.events 渲染的，所以它一定存在且可信）:contentReference[oaicite:5]{index=5}
        val allEvents = allSummaries.flatMap { it.events }
        val totalMs = allSummaries.sumOf { s ->
            val end = s.session.endEpochMs ?: s.session.startEpochMs
            (end - s.session.startEpochMs).coerceAtLeast(0L)
        }
        val totalHours = totalMs / 3600000.0

        val durationsSec = allEvents.map { (it.endSec - it.startSec).toDouble().coerceAtLeast(0.0) }
        val durMean = if (durationsSec.isEmpty()) Double.NaN else durationsSec.average()

        val durP95 = percentileOrNaN(durationsSec, 0.95)
        val durMax = durationsSec.maxOrNull() ?: Double.NaN

        // AHI（教育用途）：用 “事件数 / 小时” 做一个粗略估计（先别叫诊断）
        val ahiEst = if (totalHours > 0.2) allEvents.size / totalHours else Double.NaN

        val head = buildString {
            appendLine("【声纳/鼾声监测（来自 Room：sleep_sessions + sleep_events）】")
            appendLine("- sessions=${allSummaries.size}")
            appendLine("- events=${allEvents.size}")
            appendLine("- totalHours=${"%.2f".format(totalHours)}h")
            appendLine("- AHI_est(events/hour)=${ahiEst.formatOrNA(2)}")
            appendLine("- dur_mean=${durMean.formatOrNA(1)}s, dur_p95=${durP95.formatOrNA(1)}s, dur_max=${durMax.formatOrNA(1)}s")
        }

        // 逐日概览（按 dayKey 汇总）
        val byDay = allSummaries.groupBy { it.session.dayKey }
        val lines = byDay.entries.sortedByDescending { it.key }.joinToString("\n") { (dayKey, sums) ->
            val ev = sums.sumOf { it.events.size }
            val ms = sums.sumOf {
                val end = it.session.endEpochMs ?: it.session.startEpochMs
                (end - it.session.startEpochMs).coerceAtLeast(0L)
            }
            val h = ms / 3600000.0
            val ahi = if (h > 0.2) ev / h else Double.NaN
            "- $dayKey: sessions=${sums.size}, events=$ev, hours=${"%.2f".format(h)}, AHI_est=${ahi.formatOrNA(2)}"
        }

        return head + "\n【逐日汇总】\n" + lines
    }

    // -------------------- 时间范围（先做稳定版：近N天） --------------------

    private fun parseDaysOrNull(text: String): Int? {
        // 识别：“近7天/过去14天/最近3天”
        val r = Regex("(近|过去|最近)\\s*(\\d{1,2})\\s*天")
        val m = r.find(text) ?: return null
        return m.groupValues[2].toIntOrNull()
    }

    private fun lastNDaysRange(n: Int): DayRange {
        val days = n.coerceIn(1, 60)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val calEnd = Calendar.getInstance()
        val end = sdf.format(calEnd.time)

        val calStart = Calendar.getInstance()
        calStart.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val start = sdf.format(calStart.time)

        return DayRange(startDayKey = start, endDayKey = end, days = days)
    }

    data class DayRange(val startDayKey: String, val endDayKey: String, val days: Int) {
        fun dayKeys(): List<String> {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val start = sdf.parse(startDayKey) ?: Date()
            val cal = Calendar.getInstance()
            cal.time = start
            val out = ArrayList<String>(days)
            repeat(days) {
                out += sdf.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return out
        }
    }

    // -------------------- 统计工具 --------------------

    private fun Double.formatOrNA(digits: Int): String {
        return if (this.isFinite()) "%.${digits}f".format(this) else "NA"
    }

    private fun averageOrNaN(xs: List<Double>): Double {
        if (xs.isEmpty()) return Double.NaN
        return xs.sum() / xs.size
    }

    private fun needPersonalData(userText: String): Boolean {
        val keys = listOf(
            "近", "最近", "过去", "几天", "多少天",
            "哪天", "当天", "日期", "趋势", "变化",
            "我的", "本人", "这段时间", "对比"
        )
        return keys.any { userText.contains(it) }
    }

    private fun percentileOrNaN(xs: List<Double>, p: Double): Double {
        if (xs.isEmpty()) return Double.NaN
        val sorted = xs.sorted()
        val idx = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[idx]
    }
}
