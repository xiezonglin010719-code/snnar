package com.example.senar.breath

import kotlin.math.max
import kotlin.math.min

object CredibilityController {

    enum class Action { OK, LOW_QUALITY, DEFER }

    data class Decision(
        val q: Float,
        val uModel: Float,
        val uEff: Float,
        val weight: Float,
        val defer: Boolean,
        val action: Action,
        val reason: String
    )

    /**
     * 通用融合：同时考虑数据质量 q 与模型不确定度 uModel
     * - uEff 越大越不可信
     * - weight 用于“整夜/全局统计加权”
     */
    fun fuse(qIn: Float, uModelIn: Float, deferThr: Float = 0.55f): Decision {
        val q = qIn.coerceIn(0f, 1f)
        val uModel = uModelIn.coerceIn(0f, 1f)

        // ✅ 简单稳健：质量差会把“不确定度”抬高
        val uEff = (1f - (1f - uModel) * q).coerceIn(0f, 1f)
        val weight = (1f - uEff).coerceIn(0f, 1f)

        val lowQ = q < 0.25f
        val defer = (uEff >= deferThr) || lowQ

        val action = when {
            defer -> Action.DEFER
            lowQ -> Action.LOW_QUALITY
            else -> Action.OK
        }

        val reason = buildString {
            append("q=").append("%.2f".format(q))
            append(" uModel=").append("%.2f".format(uModel))
            append(" uEff=").append("%.2f".format(uEff))
            if (lowQ) append(" (low_quality)")
            if (uEff >= deferThr) append(" (high_uncertainty)")
        }

        return Decision(
            q = q,
            uModel = uModel,
            uEff = uEff,
            weight = weight,
            defer = defer,
            action = action,
            reason = reason
        )
    }

    // ------------- 两条通路的“约定接口” -------------

    /** 呼吸：uModel 来自模型输出 u；q 来自音频质量 */
    fun decideForBreath(q: Float, uModel: Float): Decision =
        fuse(qIn = q, uModelIn = uModel, deferThr = 0.55f)

    /**
     * 声纳：你现在大概率已经有 conf / snr / presence / coverage 等
     * 你只要把它们映射成：
     * - q: 数据质量（比如 presence/coverage 低就 q 低）
     * - uModel: 规则/模型的不确定度（比如 conf 低就 uModel 高）
     */
    fun decideForSonar(q: Float, uModel: Float): Decision =
        fuse(qIn = q, uModelIn = uModel, deferThr = 0.60f)
}
