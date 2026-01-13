package com.example.senar.breath

import com.example.senar.breath.evidence.EvidenceClip
import com.example.senar.breath.offline.OfflineDiagnosisResult

/**
 * UI/页面要展示的实时状态（字段名严格对齐你现在 update{ copy(...) } 的用法）
 */
data class BreathUiState(
    // realtime

    val running: Boolean = false,
    val quality: Float = 0f,                 // 最近一次 30s 音频质量 q
    val defer: Boolean = false,              // 最近一次 5min 推理是否 defer（统一控制后）
    val status: String = "未开始",
    val statusText: String = "未开始",
    val lastInferenceText: String = "",
    val debugText: String = "",

    // ===== 统一可信度控制（新增）=====
    val uModel: Float = 0f,                  // 模型/规则 u
    val uEff: Float = 0f,                    // 融合后 uEff
    val weight: Float = 0f,                  // 统计权重
    val credAction: String = "",             // OK / LOW_QUALITY / DEFER
    val credReason: String = "",             // 可解释原因



    val windowsBuffered: Int = 0,
    val timeline: List<FiveMinResult> = emptyList(),

    // offline（给 OfflineDiagnosisCard 用）
    val offlineSelectedName: String = "",
    val offlineStatus: String = "",
    val offlineResult: OfflineDiagnosisResult? = null,




    // offline（给 BreathOfflineRunner 用：保留你原来的 text 版本，避免你另一个 runner 报错）
    val offlineBusy: Boolean = false,
    val offlineStatusText: String = "",
    val offlineResultText: String = "",
    val offlineDebugText: String = "",



    // ✅ 证据链：每个 5min 结果一条 EvidenceClip
    val evidences: List<EvidenceClip> = emptyList(),

    // UI 用：当前选中的 evidenceId（用于弹窗/详情）
    val selectedEvidenceId: String? = null,
)

/** 占位：你后面接 30s 级别实时结果时再补 */
data class BreathRealtimeResult(
    val tsMs: Long = 0L,
    val note: String = ""
)

/** 占位：整晚报告结构，后面再扩展 */
data class BreathNightReport(
    val startTsMs: Long = 0L,
    val endTsMs: Long = 0L,
    val summary: String = ""
)


