package com.example.senar.breath.evidence

/**
 * 每个 5min 诊断结果都应有一条 EvidenceClip（证据链）
 *
 * - pre30 / cur30 / post30：呼吸暂停前后30s波形（FloatArray[-1,1]），在线的 post30 可能延迟补齐
 * - envelope：下采样后的包络（用于快速画折线）
 * - heat64：用于展示“证据热力图”（来自你 feature 的 ch0 或其它），64x64 flatten
 */
data class EvidenceClip(
    val id: String,
    val tsMs: Long,
    val source: Source,
    val title: String,
    val sevIdx: Int,
    val p: FloatArray,
    val u: Float,
    val defer: Boolean,

    val pre30: FloatArray? = null,
    val cur30: FloatArray? = null,
    val post30: FloatArray? = null,

    val envelope: FloatArray? = null, // e.g. 300 points
    val heat64: FloatArray? = null,   // 64*64
    val note: String = "",
) {
    enum class Source { REALTIME, OFFLINE }
}
