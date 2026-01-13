package com.example.senar.breath

import android.content.Context
import com.example.senar.breath.reports.BreathReportRepository
import kotlinx.coroutines.*
import java.util.ArrayDeque


import com.example.senar.breath.evidence.EvidenceClip

import kotlin.math.max

class BreathRealtimeManager(
    private val ctx: Context,
    private val engine: BreathInferenceEngine,
    private val scope: CoroutineScope,
) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val WINDOW_SEC = 30

        // ✅ 业务语义：5min一次诊断；30s一窗 => 10窗
        const val STEP_WINDOWS = 10

        // ✅ 每窗特征维度（extractor 输出 2*64*64）
        const val C = 2
        const val H = 64
        const val W = 64

        // ✅ PCM 下采样用于回放（波形显示足够）
        private const val PCM_DS_HZ = 200
    }

    private val audio = BreathAudioFrontend(ctx)



    // ring：最近10个特征窗（当前5min）
    private val windowRing = ArrayDeque<FloatArray>()

    // ✅ ring：最近2个 30s PCM（用于 pre30 / post30）
    // 只留很小的下采样波形，避免内存爆炸
    private val pcmDsRing = ArrayDeque<FloatArray>()  // each = downsampled 30s

    // 累计窗计数（用于 evidence 的 winStartIdx/winEndIdx）
    private var windowsSinceStart = 0
    private var windowsSinceLastInfer = 0

    // ✅ pending：推理完成后等待“下一窗”补 post30
    private var pendingPostForTs: Long? = null

    private var job: Job? = null

    // ✅ 生成一个唯一 id（最简单：用时间戳）


    // ✅ cur30：你当前窗的下采样波形。最稳的做法：取 ring 的最后一个（就是刚来的那窗）
    val cur30: FloatArray? = pcmDsRing.lastOrNull()



    fun start() {
        if (job != null) return

        windowsSinceStart = 0
        windowsSinceLastInfer = 0
        windowRing.clear()
        pcmDsRing.clear()
        pendingPostForTs = null

        BreathRealtimeStore.update {
            it.copy(
                running = true,
                status = "启动中",
                statusText = "启动中",
                lastInferenceText = "",
                debugText = (it.debugText + "\n[start] init").trim()
            )
        }

        job = scope.launch(Dispatchers.Default) {
            try {
                audio.start(sampleRate = SAMPLE_RATE)

                BreathRealtimeStore.update {
                    it.copy(
                        status = "运行中（采集中）",
                        statusText = "运行中（采集中）",
                        debugText = (it.debugText + "\n[start] AudioRecord started").trim()
                    )
                }

                while (isActive) {
                    val pcm = audio.readPcmSeconds(seconds = WINDOW_SEC, sampleRate = SAMPLE_RATE)

                    val q = BreathFeatureExtractor.estimateQuality(pcm, SAMPLE_RATE).coerceIn(0f, 1f)
                    val feat = BreathFeatureExtractor.extract30sToC2HW64(pcm, SAMPLE_RATE)

                    // ✅ 保存下采样波形，用于 evidence pre/post
                    val pcmDs = downsampleToHz(pcm, SAMPLE_RATE, PCM_DS_HZ)
                    onNewWindow(pcmDs = pcmDs, feature = feat, q = q)
                }
            } catch (se: SecurityException) {
                BreathRealtimeStore.update {
                    it.copy(
                        running = false,
                        status = "缺少麦克风权限",
                        statusText = "缺少麦克风权限（请在页面申请 RECORD_AUDIO）",
                        debugText = (it.debugText + "\n[error] $se").trim()
                    )
                }
                stop()
            } catch (t: Throwable) {
                BreathRealtimeStore.update {
                    it.copy(
                        running = false,
                        status = "运行异常",
                        statusText = "运行异常：${t.message}",
                        debugText = (it.debugText + "\n[crash]\n${t.stackTraceToString()}").trim()
                    )
                }
                stop()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        windowRing.clear()
        pcmDsRing.clear()
        pendingPostForTs = null
        audio.stop()

        windowsSinceStart = 0
        windowsSinceLastInfer = 0

        val timeline = BreathRealtimeStore.state.value.timeline
        if (timeline.isNotEmpty()) {
            BreathReportRepository.from(ctx).saveSession(
                source = BreathReportRepository.Source.ONLINE,
                label = "在线实时",
                timeline = timeline
            )
        }

        BreathRealtimeStore.update {
            it.copy(
                running = false,
                status = "已停止",
                statusText = "已停止"
            )
        }
    }

    /**
     * 每产生一个 30s window 就调用一次
     * - 先尝试补齐上一条推理的 post30
     * - 再更新 ring
     * - 每满 10 个新窗推一次（严格 5min 一次）
     */
    private fun onNewWindow(pcmDs: FloatArray, feature: FloatArray, q: Float) {
        // 0) ✅ 若上一条推理在等 post30，则用“本窗”补上
        pendingPostForTs?.let { ts ->
            BreathRealtimeStore.patchEvidencePost30(tsMs = ts, post30 = pcmDs)

            pendingPostForTs = null
        }

        // 1) 特征维度校验
        val stride = C * H * W
        if (feature.size != stride) {
            BreathRealtimeStore.update { s ->
                s.copy(
                    status = "特征维度错误",
                    statusText = "特征维度错误：got=${feature.size} expect=$stride",
                    debugText = (s.debugText + "\n[bad_feat] got=${feature.size} expect=$stride").trim()
                )
            }
            return
        }

        // 2) PCM 下采样 ring：保留最近2个（用于 pre/post）
        pcmDsRing.addLast(pcmDs)
        while (pcmDsRing.size > 2) pcmDsRing.removeFirst()

        // 3) 特征 ring：保留最近10个
        windowRing.addLast(feature)
        while (windowRing.size > STEP_WINDOWS) windowRing.removeFirst()

        windowsSinceStart += 1
        windowsSinceLastInfer += 1

        BreathRealtimeStore.update { s ->
            s.copy(
                quality = q,
                windowsBuffered = windowRing.size,
                status = "运行中（累计窗=$windowsSinceStart, 当前5min窗=${windowRing.size}/$STEP_WINDOWS）",
                statusText = "运行中（累计窗=$windowsSinceStart, 当前5min窗=${windowRing.size}/$STEP_WINDOWS）"
            )
        }

        // ✅ 真正的“5min一次”：只有每满 10 个新窗才推一次
        if (windowRing.size == STEP_WINDOWS && windowsSinceLastInfer >= STEP_WINDOWS) {
            windowsSinceLastInfer = 0
            runInferenceOnce()
        }
    }

    /**
     * 单次 5min 推理：只用最近10个窗（K=10）
     * 推完后：
     * - appendResult(timeline)
     * - appendEvidence(pre30先写，post30下一窗补)
     */
    private fun runInferenceOnce() {

        val tNow = System.currentTimeMillis()

        val xFlat = buildModelInputFlat() ?: run {
            BreathRealtimeStore.update { it.copy(lastInferenceText = "等待数据（ring 空）") }
            return
        }

        // 1) 推理（实时模型：10x2x64x64）
        val res = engine.inferRealtime10x64(xFlat)

        // 2) 可信度控制：融合 q + uModel
        val qNow = BreathRealtimeStore.state.value.quality
        val dec = CredibilityController.decideForBreath(q = qNow, uModel = res.u)

        val lastFeat = windowRing.lastOrNull()
        val heat64 = lastFeat?.copyOfRange(0, 64*64)  // ch0 作为“log-mel可视化”
        val env300: FloatArray? = null                // 呼吸通路不强求 envelope


        val clipId = "rt_$tNow"
        // 3) timeline（注意：用你工程里的 FiveMinResult 完整字段版）
        val fiveMin = FiveMinResult(
            tsMs = tNow,
            sevIdx = res.sevIdx,
            p = res.p,
            q = dec.q,
            uModel = dec.uModel,
            uEff = dec.uEff,
            weight = dec.weight,
            defer = dec.defer,
            action = dec.action,
            reason = dec.reason,
            debug = res.debug,
            evidenceId = clipId,   // ✅ 关联起来

        )
        BreathRealtimeStore.appendResult(fiveMin)

        // 4) ✅ 证据链：pre30（上一窗）+ post30（下一窗补）
        // pcmDsRing：你在 onNewWindow(pcmDs=...) 里 addLast，并最多保留2个
        // - firstOrNull(): 通常是上一窗
        val pre30 = pcmDsRing.firstOrNull()

        val winEnd = windowsSinceStart
        val winStart = kotlin.math.max(1, winEnd - STEP_WINDOWS + 1)

        BreathRealtimeStore.appendEvidence(
            EvidenceClip(
                id = clipId,
                tsMs = tNow,
                source = EvidenceClip.Source.REALTIME,
                title = "Breath 5min",
                sevIdx = res.sevIdx,
                p = res.p,
                u = res.u,
                defer = dec.defer,

                pre30 = pre30,      // FloatArray?
                cur30 = cur30,      // 你当前这 30s，如果你有就填，没有就 null
                post30 = null,      // ✅ 等下一窗补齐

                envelope = null,  // 可选：没有就 null
                heat64 = heat64,    // 可选：没有就 null
                note = "post30 等待下一窗补齐"

            )
        )

        // ✅ 标记：下一窗补 post30（你需要在 onNewWindow() 开头 patch）
        pendingPostForTs = tNow

        // 5) UI 文本（可选）
        val pText = res.p.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
        BreathRealtimeStore.update { s ->
            s.copy(
                status = "运行中（5min诊断已更新：段数=${s.timeline.size}）",
                statusText = "运行中（5min诊断已更新：段数=${s.timeline.size}）",
                lastInferenceText =
                    "sev=${res.sevIdx} p=$pText uModel=${"%.3f".format(res.u)} uEff=${"%.3f".format(dec.uEff)} w=${"%.2f".format(dec.weight)} defer=${dec.defer}",
                defer = dec.defer,
                uModel = dec.uModel,
                uEff = dec.uEff,
                weight = dec.weight,
                credAction = dec.action.name,
                credReason = dec.reason,
                debugText = (s.debugText +
                        "\n\n[infer] ${res.debug}" +
                        "\n[cred] ${dec.reason}" +
                        "\n[evidence] win=$winStart-$winEnd pre30=${pre30?.size ?: 0} post30=pending"
                        ).trim()
            )
        }
    }


    /**
     * 拼成 [1,10,2,64,64] 扁平 FloatArray
     */
    private fun buildModelInputFlat(): FloatArray? {
        if (windowRing.size < STEP_WINDOWS) return null

        val K = STEP_WINDOWS
        val stride = C * H * W
        val out = FloatArray(1 * K * stride)

        val windows = windowRing.toList()
        for (i in 0 until K) {
            val feat = windows[i]
            val dstOff = i * stride
            System.arraycopy(feat, 0, out, dstOff, stride)
        }
        return out
    }

    /**
     * ✅ 下采样到 targetHz（用于回放波形）
     */
    private fun downsampleToHz(pcm: FloatArray, srcHz: Int, targetHz: Int): FloatArray {
        if (targetHz >= srcHz) return pcm
        val step = (srcHz.toFloat() / targetHz.toFloat()).coerceAtLeast(1f)
        val outN = (pcm.size / step).toInt().coerceAtLeast(1)
        val out = FloatArray(outN)
        var idx = 0f
        for (i in 0 until outN) {
            val j = idx.toInt().coerceIn(0, pcm.size - 1)
            out[i] = pcm[j]
            idx += step
        }
        return out
    }
}
