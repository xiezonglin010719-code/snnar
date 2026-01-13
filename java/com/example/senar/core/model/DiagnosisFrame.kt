package com.example.senar.core.model

data class DiagnosisFrame(
    val tsMs: Long,
    val mode: DiagMode,

    // 实时可展示
    val breathBpm: Float,
    val qualityIndex: Float?,        // quality_index
    val qualityGrade: Int?,          // quality_grade (如果有)
    val qualityLabel: String?,       // quality_label (如果有)

    val snrDb: Float?,
    val coverage: Float?,
    val usableForAhi: Boolean?,      // usable_for_ahi

    // AHI（你的 features 里已有 ahi_total/ahi_est/ahi_central/ahi_obstructive/ahi_hypopnea）
    val ahiTotal: Float,
    val ahiCentral: Float,
    val ahiObstructive: Float,
    val ahiHypopnea: Float,

    // 诊断文本（diagnosis）
    val diagnosis: String?,

    // 用于画图：优先 resp_env_plot，其次 envelope_plot/envelope
    val respEnv: FloatArray
)
