package com.example.senar.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sleep_events",
    indices = [Index("sessionId"), Index("startSec"), Index("type")]
)
data class SleepEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    val sessionId: Long,

    val type: String,
    val startSec: Float,
    val endSec: Float,
    val confidence: Float? = null,

    // 去重 key：type|start|end（你之前 SessionManager 就是这么干的）
    val eventKey: String,
)
