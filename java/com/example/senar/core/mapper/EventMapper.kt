package com.example.senar.core.mapper

import com.example.senar.core.model.SleepEvent
import com.example.senar.core.storage.entity.SleepEventEntity
import kotlin.math.roundToInt

/**
 * 生成稳定 eventKey：同一个 session 内，同一事件重复上报不会重复入库
 * - start/end 用 ms 粒度，避免 float 抖动导致 key 变化
 */
fun SleepEvent.toEntity(sessionId: Long): SleepEventEntity {
    val sMs = (startSec * 1000f).roundToInt()
    val eMs = (endSec * 1000f).roundToInt()
    val key = "${type}_${sMs}_${eMs}"

    return SleepEventEntity(
        sessionId = sessionId,
        eventKey = key,
        type = type,
        startSec = startSec,
        endSec = endSec,
        confidence = confidence
    )
}
