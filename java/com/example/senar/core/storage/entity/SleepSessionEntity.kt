package com.example.senar.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sleep_sessions",
    indices = [
        Index(value = ["dayKey"]),
        Index(value = ["streamId"]),
        // ✅ 你真正要用的筛选维度
        Index(value = ["personId", "dayKey"])
    ]
)
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    // 你原来就有
    val streamId: String,

    // ✅ 新增：用于“多目标隔离”
    // 给默认值是为了迁移时 NOT NULL 不炸
    val personId: String = "default",

    val startEpochMs: Long,
    val endEpochMs: Long?,
    val dayKey: String,
    val diagType: String
)
