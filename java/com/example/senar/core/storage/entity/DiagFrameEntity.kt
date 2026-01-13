package com.example.senar.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "diag_frames",
    indices = [Index("sessionId"), Index("tsEpochMs")]
)
data class DiagFrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    val sessionId: Long,
    val tsEpochMs: Long,

    // 常用字段做列，报告直接 SQL 聚合
    val breathBpm: Float? = null,
    val qualityIndex: Float? = null,
    val snrDb: Float? = null,

    val ahiTotal: Float? = null,
    val ahiCentral: Float? = null,
    val ahiObstructive: Float? = null,
    val ahiHypopnea: Float? = null,

    // 保留你现在“大段 debug”，直接存 TEXT，不改你逻辑
    val rawDebugText: String? = null,

    // 关键：可追溯（把 features 的原始快照也存下来）
    val rawFeaturesJson: String? = null,
)
