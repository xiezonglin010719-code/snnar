package com.example.senar.ai

interface ReportSummaryProvider {
    /** 返回最近一次整夜报告的摘要文本（越结构化越好） */
    suspend fun latestNightSummary(): String

    /** 返回最近 N 天的趋势摘要（可选） */
    suspend fun trendSummary(days: Int = 7): String = ""
}
