package com.example.senar


enum class QualityLevel {
    NONE,      // 没有检测到人
    POOR,      // 质量差，不估计 AHI
    FAIR,      // 一般，可以显示呼吸频率，但 AHI 可能不稳定
    GOOD       // 质量好，可稳定估计 AHI
}

data class QualityState(
    val level: QualityLevel,
    val qualityIndex: Float,
    val breathBpm: Float,
    val conf: Float,
    val personPresent: Boolean,
    val allowEvents: Boolean
)