package com.example.senar.ai

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DayRange(val startDayKey: String, val endDayKey: String)

object DateRangeResolver {

    // 兼容 yyyyMMdd / yyyy-MM-dd（你项目 dayKey 用哪个就保哪个）
    private const val DEFAULT_DAYKEY = "yyyyMMdd"
    private val reIso = Regex("""\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b""")
    private val reMd = Regex("""\b(\d{1,2})月(\d{1,2})[日号]?\b""")
    private val reLastN = Regex("""(近|过去|最近)\s*(\d{1,3})\s*天""")
    private val reRange = Regex("""(\d{1,2})月(\d{1,2})[日号]?\s*(到|至|~|-)\s*(\d{1,2})月(\d{1,2})[日号]?""")

    fun resolve(text: String, now: Date = Date(), dayKeyPattern: String = DEFAULT_DAYKEY): DayRange? {
        val t = text.replace(" ", "")

        // 1) 近N天 / 过去N天
        reLastN.find(t)?.let { m ->
            val n = m.groupValues[2].toInt().coerceIn(1, 365)
            val end = dayKey(now, dayKeyPattern)
            val start = dayKey(daysAgo(now, n - 1), dayKeyPattern)
            return DayRange(start, end)
        }

        // 2) 3月3日-3月10日（跨月也支持）
        reRange.find(t)?.let { m ->
            val (m1, d1, _, m2, d2) = listOf(
                m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5]
            )
            val year = yearOf(now)
            val a = dateOf(year, m1.toInt(), d1.toInt())
            val b = dateOf(year, m2.toInt(), d2.toInt())
            val (startDate, endDate) = if (a.after(b)) b to a else a to b
            return DayRange(dayKey(startDate, dayKeyPattern), dayKey(endDate, dayKeyPattern))
        }

        // 3) 单日：3月3日
        reMd.find(t)?.let { m ->
            val year = yearOf(now)
            val one = dateOf(year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            val k = dayKey(one, dayKeyPattern)
            return DayRange(k, k)
        }

        // 4) 单日：2026-03-03 / 2026/3/3
        reIso.find(t)?.let { m ->
            val one = dateOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            val k = dayKey(one, dayKeyPattern)
            return DayRange(k, k)
        }

        return null
    }

    private fun yearOf(d: Date): Int {
        val c = Calendar.getInstance()
        c.time = d
        return c.get(Calendar.YEAR)
    }

    private fun dateOf(y: Int, m: Int, d: Int): Date {
        val c = Calendar.getInstance()
        c.set(Calendar.YEAR, y)
        c.set(Calendar.MONTH, (m - 1).coerceIn(0, 11))
        c.set(Calendar.DAY_OF_MONTH, d.coerceIn(1, 31))
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.time
    }

    private fun daysAgo(now: Date, days: Int): Date {
        val c = Calendar.getInstance()
        c.time = now
        c.add(Calendar.DAY_OF_YEAR, -days)
        return c.time
    }

    private fun dayKey(d: Date, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(d)
}
