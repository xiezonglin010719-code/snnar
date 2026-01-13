package com.example.senar.ui.live

data class LiveUiState(
    val isRunning: Boolean = false,
    val statusText: String = "未开始",
    val guidanceText: String = "点击开始进行监测",
    val breathBpm: Float = 0f,
    val qualityPercent: Int = 0,         // 0..100
    val snrDb: Float? = null,
    val ahiTotal: Float = 0f,
    val eventCountRecent: Int = 0,
    val chart: FloatArray = FloatArray(0),   // 用 resp_env_plot/envelope_plot
    val debugText: String = ""               // 你原来那大段 debug（开发者模式才显示）
)
