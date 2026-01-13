package com.example.senar.breath.offline

import android.content.Context
import android.net.Uri
import com.example.senar.breath.BreathInferenceEngine
import com.example.senar.breath.BreathRealtimeStore
import com.example.senar.breath.CredibilityController
import com.example.senar.breath.FiveMinResult
import com.example.senar.breath.reports.BreathReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BreathOfflineRunner {

    suspend fun runNpZ(ctx: Context, uri: Uri) = withContext(Dispatchers.Default) {
        val file = NpzBridge.copyUriToCache(ctx, uri, outName = "offline_input.npz")
        val npzPath = file.absolutePath

        var handle: String? = null
        try {
            BreathRealtimeStore.offlineBusy(true, "打开 NPZ（解压X.npy + mmap）...")
            handle = NpzBridge.open(ctx, npzPath, xKey = "X")

            val engine = BreathInferenceEngine(ctx) // 默认：RT=uosas_mobile.ts, OFF=mobile_uosas_distill.ts
            val segCount = NpzBridge.count(ctx, handle)

            BreathRealtimeStore.offlineBusy(true, "开始整晚推理：段数=$segCount ...")
            BreathRealtimeStore.update { s -> s.copy(timeline = emptyList()) }

            var lastResText = ""
            var lastDbg = ""

            for (i in 0 until segCount) {
                val xFlat = NpzBridge.readSegmentFloat32Flat(
                    ctx = ctx,
                    handleId = handle,
                    segIdx = i,
                    K = BreathInferenceEngine.OFF_K,
                    C = BreathInferenceEngine.OFF_C,
                    H = BreathInferenceEngine.OFF_H,
                    W = BreathInferenceEngine.OFF_W
                )

                // ✅ 离线模型推理（128x2x80x80）
                val res = engine.inferOffline128x80(xFlat)

                // ✅ 离线先给默认质量分；你以后可用该段mel/pcm估计
                val q = 1.0f
                val dec = CredibilityController.decideForBreath(q = q, uModel = res.u)

                val tsMs = System.currentTimeMillis() + i.toLong() * 30_000L

                BreathRealtimeStore.appendResult(
                    FiveMinResult(
                        tsMs = tsMs,
                        sevIdx = res.sevIdx,
                        p = res.p,
                        q = dec.q,
                        uModel = dec.uModel,
                        uEff = dec.uEff,
                        weight = dec.weight,
                        defer = dec.defer,
                        action = dec.action,
                        reason = dec.reason,
                        debug = "seg=$i/$segCount | ${res.debug}"
                    )
                )

                lastResText =
                    "seg=${i + 1}/$segCount " +
                            "sev=${res.sevIdx} " +
                            "p=${res.p.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }} " +
                            "u=${"%.3f".format(res.u)} uEff=${"%.3f".format(dec.uEff)} w=${"%.2f".format(dec.weight)} " +
                            "b15=${"%.3f".format(res.bin15Prob)} b30=${"%.3f".format(res.bin30Prob)} " +
                            "defer=${dec.defer}"

                lastDbg = res.debug + "\n" + dec.reason

                if (i % 5 == 0) {
                    BreathRealtimeStore.offlineBusy(true, "推理中... ${i + 1}/$segCount")
                }
            }

            BreathRealtimeStore.offlineResult(
                status = "NPZ 整晚诊断完成",
                result = lastResText.ifBlank { "完成：共 $segCount 段（timeline 已写入）" },
                debug = "timeline.size=$segCount\n$lastDbg"
            )

            val timeline = BreathRealtimeStore.state.value.timeline
            if (timeline.isNotEmpty()) {
                BreathReportRepository.from(ctx).saveSession(
                    source = BreathReportRepository.Source.OFFLINE,
                    label = "离线NPZ:${file.name}",
                    timeline = timeline
                )
            }
        } catch (t: Throwable) {
            BreathRealtimeStore.offlineError("NPZ 离线诊断失败：${t.message}")
        } finally {
            handle?.let { NpzBridge.close(ctx, it) }
        }
    }

    // UI 里还在调用 runMp4，所以保留（你现在先不管离线也行）
    suspend fun runMp4(ctx: Context, uri: Uri) = withContext(Dispatchers.Default) {
        try {
            BreathRealtimeStore.offlineBusy(true, "MP4 离线暂未启用")
            BreathRealtimeStore.offlineResult(
                status = "MP4 离线暂未启用",
                result = "请先用 NPZ",
                debug = ""
            )
        } catch (t: Throwable) {
            BreathRealtimeStore.offlineError("MP4 离线诊断失败：${t.message}")
        }
    }
}
