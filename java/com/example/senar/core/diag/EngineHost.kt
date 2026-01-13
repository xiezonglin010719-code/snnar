package com.example.senar.core.diag

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 负责：
 * - 持有 DiagnosisEngine（SonarEngine 或 MultiPersonDiagnosis 包一层）
 * - 接收音频帧 -> 调 engine.onAudioFrame -> 分发 EngineResult 给 UI
 *
 * 不改诊断算法，只做“接线/分发”。
 */
class EngineHost(
    private val engine: DiagnosisEngine
) {
    private val running = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(EngineResult) -> Unit>()
    private var seq: Long = 0L

    fun start() {
        if (running.compareAndSet(false, true)) {
            seq = 0L
            engine.start()
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            engine.stop()
        }
    }

    fun isRunning(): Boolean = running.get()

    /** UI 用：AppScaffold 需要这个签名：注册回调并返回 unregister */
    fun registerListener(cb: (EngineResult) -> Unit): () -> Unit {
        listeners.add(cb)
        return { listeners.remove(cb) }
    }

    /** 音频采集线程/Service 每来一帧就调用这个 */
    fun onPcmFrame(pcm: ShortArray, tCaptureNs: Long = System.nanoTime()) {
        if (!running.get()) return
        val r = engine.onAudioFrame(pcm = pcm, tCaptureNs = tCaptureNs, seq = seq++)
        if (r != null) {
            for (cb in listeners) cb(r)
        }
    }
}
