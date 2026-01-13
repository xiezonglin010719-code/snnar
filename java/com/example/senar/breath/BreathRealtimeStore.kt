package com.example.senar.breath

import com.example.senar.breath.evidence.EvidenceClip
import com.example.senar.breath.offline.OfflineDiagnosisResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 5min 一次诊断结果（你现在在可信度控制里用到的完整字段版）
 */
data class FiveMinResult(
    val tsMs: Long,
    val sevIdx: Int,
    val p: FloatArray,                      // size=4

    // ✅ 可信度/不确定性统一字段（呼吸实时会填）
    val q: Float,                           // 质量 0..1
    val uModel: Float,                      // 模型原始u（或你自定义的不确定性）
    val uEff: Float,                        // 融合后u
    val weight: Float,                      // 统计权重
    val defer: Boolean,
    val action: CredibilityController.Action,
    val reason: String,

    // ✅ 证据链关联（用于 Reports 点击播放）
    val evidenceId: String? = null,

    val debug: String = ""
)

/**
 * ✅ 证据链：用于“事件回放/证据链展示”
 * - pre30Ds/post30Ds：建议存下采样后的 30s 波形（如 200Hz），避免爆内存
 * - winStartIdx/winEndIdx：用于把这次推理对应到累计窗序号（可用于索引特征/包络等）
 */
data class BreathEvidence(
    val tsMs: Long,                 // 对应 FiveMinResult.tsMs
    val winStartIdx: Int,
    val winEndIdx: Int,
    val q: Float,
    val uModel: Float,
    val pre30Ds: FloatArray?,       // 推理前 30s（下采样）
    val post30Ds: FloatArray?,      // 推理后 30s（下采样，下一窗补齐）
    val note: String = ""
)

object BreathRealtimeStore {

    private val _state = MutableStateFlow(
        BreathUiState(
            status = "未开始",
            statusText = "未开始",
            offlineStatusText = "未开始",
            offlineStatus = "未开始"
        )
    )
    val state: StateFlow<BreathUiState> = _state

    fun update(block: (BreathUiState) -> BreathUiState) {
        _state.update(block)
    }

    // =========================
    // realtime timeline
    // =========================
    fun appendResult(r: FiveMinResult) {
        _state.update { s ->
            val newList = (s.timeline + r).takeLast(2000)
            s.copy(timeline = newList)
        }
    }

    fun clearTimeline() {
        _state.update { it.copy(timeline = emptyList()) }
    }

    // =========================
    // ✅ Evidence
    // =========================


    fun appendEvidence(clip: EvidenceClip) {
        _state.update { s ->
            val newList = (s.evidences + clip).takeLast(2000)
            s.copy(evidences = newList)
        }
    }

    /**
     * ✅ 下一窗补齐 post30
     * - 通过 tsMs 定位最后一条匹配的 EvidenceClip
     */

    /** 下一窗补齐 post30：按 tsMs 找最后一条 */
    fun patchEvidencePost30(tsMs: Long, post30: FloatArray) {
        _state.update { s ->
            val idx = s.evidences.indexOfLast { it.tsMs == tsMs }
            if (idx < 0) return@update s

            val old = s.evidences[idx]
            val patched = old.copy(post30 = post30)  // ✅ 只改这里：post30

            val list = s.evidences.toMutableList()
            list[idx] = patched
            s.copy(evidences = list)
        }
    }

    fun findEvidenceById(id: String): EvidenceClip? = _state.value.evidences.firstOrNull { it.id == id }

    // =========================
    // offline new接口：runner在用
    // =========================
    fun offlineBusy(busy: Boolean, text: String) {
        _state.update { s ->
            s.copy(
                offlineBusy = busy,
                offlineStatusText = text,
                offlineStatus = text
            )
        }
    }

    fun offlineResult(
        status: String,
        result: String,
        debug: String = "",
        parsed: OfflineDiagnosisResult? = null
    ) {
        _state.update { s ->
            s.copy(
                offlineBusy = false,
                offlineStatusText = status,
                offlineResultText = result,
                offlineDebugText = (s.offlineDebugText + "\n" + debug).trim(),
                offlineStatus = status,
                offlineResult = parsed
            )
        }
    }

    fun offlineError(err: String) {
        _state.update { s ->
            s.copy(
                offlineBusy = false,
                offlineStatusText = "失败",
                offlineResultText = err,
                offlineDebugText = (s.offlineDebugText + "\n" + err).trim(),
                offlineStatus = "失败：$err"
            )
        }
    }

    // =========================
    // ✅ 旧接口兼容层：OfflineDiagnosisCard 继续可用
    // =========================
    fun setOfflineSelected(uriOrName: String) {
        _state.update { s -> s.copy(offlineSelectedName = uriOrName) }
    }

    fun setOfflineStatus(status: String) {
        // 旧 UI 期望“状态文本变化”，这里不强制 busy=true，避免你 UI 一直转圈
        _state.update { s -> s.copy(offlineStatus = status, offlineStatusText = status) }
    }

    fun setOfflineResult(res: OfflineDiagnosisResult) {
        val resultText =
            "p=${res.p.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }}  " +
                    "u=${"%.3f".format(res.u)}  defer=${res.defer}"

        offlineResult(
            status = "离线诊断完成",
            result = "sev=${res.sevIdx}  $resultText",
            debug = res.debug,
            parsed = res
        )
    }
}
