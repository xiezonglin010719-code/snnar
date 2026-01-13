package com.example.senar

import com.example.senar.core.diag.SonarEngine

/**
 * 多人轮换（同一时刻只诊断一人）：
 * - 每个人用不同 streamId，Python 侧会把 ROI/baseline/posture/turn 等状态隔离到不同 key 下
 */
object MultiPersonDiagnosis {

    const val DEFAULT_PERSON_ID = "p1"

    fun streamIdForPerson(
        deviceId: String,
        personId: String,
        sessionEpochSec: Long
    ): String {
        return SonarEngine.buildStreamId(
            baseStreamId = deviceId,
            personId = personId,
            epochSec = sessionEpochSec
        )
    }
}
