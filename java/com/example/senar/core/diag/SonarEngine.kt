package com.example.senar.core.diag

import com.example.senar.PythonBridge
import com.example.senar.core.mapper.FrameMapper
import com.example.senar.core.model.DiagMode

class SonarEngine(
    private val pythonBridge: PythonBridge,
    private val streamId: String,
    private val txChirp: ShortArray,
    private val chirpsPerFrame: Int,
    private val sampleRate: Int = 48_000,
    private val saveDir: String? = null
) : DiagnosisEngine {

    /**
     * 多人轮换：Python 端用 stream_id 作为全局状态 key。
     * 最安全的隔离方式：每个人每次 session 用不同 streamId。
     */
    companion object {

        /**
         * Build a unique streamId for "same device rotate multiple people" mode.
         *
         * @param baseStreamId Usually your deviceId (or any stable id for the device).
         * @param personId A stable person key in your app (e.g. "p1", "mom", "dad", UUID).
         * @param epochSec Optional; defaults to current time; makes each session unique even for same person.
         */
        fun buildStreamId(
            baseStreamId: String,
            personId: String,
            epochSec: Long = System.currentTimeMillis() / 1000L
        ): String {
            return "${baseStreamId}__p_${personId}__s_${epochSec}"
        }
    }

    override val mode: DiagMode = DiagMode.SONAR

    @Volatile private var running = false
    override fun start() { running = true }
    override fun stop() { running = false }

    override fun onAudioFrame(pcm: ShortArray, tCaptureNs: Long, seq: Long): EngineResult? {
        if (!running) return null

        val (features, eventsRaw) = pythonBridge.fmcwProcessFrameStream(
            streamId = streamId,
            rxPcm = pcm,
            txChirp = txChirp,
            chirpsPerFrame = chirpsPerFrame,
            sampleRate = sampleRate,
            saveDir = saveDir
        )

        val tsMs = System.currentTimeMillis()
        val frame = FrameMapper.mapToFrame(features, tsMs = tsMs, mode = mode)
        val events = FrameMapper.mapToEvents(eventsRaw)

        // ✅ 关键：把 rawFeatures/rawEvents 原样带回去
        // 这样 LiveScreen 的 debugText（posture/turn、ROI locker、baseline 等）就都回来了
        return EngineResult(
            frame = frame,
            events = events,
            rawFeatures = features,
            rawEvents = eventsRaw
        )
    }
}
