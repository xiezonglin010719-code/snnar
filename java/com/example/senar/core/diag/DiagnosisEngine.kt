package com.example.senar.core.diag

import com.example.senar.core.model.DiagMode

interface DiagnosisEngine {
    val mode: DiagMode
    fun start()
    fun stop()

    /**
     * 输入一帧音频（你现在 Sonar 也是基于 mic 采集的 pcm）
     * 输出统一 EngineResult，UI/报告不关心底层是 Python 还是 TFLite
     */
    fun onAudioFrame(pcm: ShortArray, tCaptureNs: Long, seq: Long): EngineResult?
}
