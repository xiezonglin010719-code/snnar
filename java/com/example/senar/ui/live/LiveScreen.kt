package com.example.senar.ui.live

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.senar.AudioProcessingService
import com.example.senar.MultiPersonDiagnosis
import com.example.senar.core.diag.EngineResult
import com.example.senar.core.settings.DevSettings
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material3.ExperimentalMaterial3Api

private const val WINDOW_SEC = 60f

@Composable
fun LiveScreen(
    engineResult: EngineResult?,
    onStart: (personId: String) -> Unit,
    onStop: () -> Unit,
    openTonight: (personId: String) -> Unit,
    openHistory: (personId: String) -> Unit,  // ✅ 改
) {
    val ctx = LocalContext.current
    val devMode by DevSettings.devModeFlow(ctx).collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    // ✅ 仍然以按钮控制为主
    var isRunning by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingSwitchTo by remember { mutableStateOf<String?>(null) }


    // ✅ 目标人：先用本地状态（你后续想持久化到 DataStore 再说）
    val people = remember {
        listOf(
            MultiPersonDiagnosis.DEFAULT_PERSON_ID, // "p1"
            "p2",
            "p3"
            // 你也可以换成 listOf("dad","mom","me")
        )
    }
    var selectedPerson by remember { mutableStateOf(MultiPersonDiagnosis.DEFAULT_PERSON_ID) }

    // ✅ 把当前帧里带回来的 person_id 作为“显示参考”（如果你在 service 里已加 features["person_id"]）
    val personFromFrame = remember(engineResult) {
        val f = engineResult?.rawFeatures
        f?.get("person_id")?.toString()
    }

    val ui = remember(engineResult, isRunning) {
        mapResultToUi(engineResult, isRunning)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Live", style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("开发者模式", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = devMode,
                    onCheckedChange = { enabled ->
                        scope.launch { DevSettings.setDevMode(ctx, enabled) }
                    }
                )
            }
        }

        // ✅ 新增：目标人选择（只改变 streamId，不改你的诊断逻辑）
        PersonSelectorCard(
            people = people,
            selected = selectedPerson,
            running = ui.isRunning,
            personFromFrame = personFromFrame,
            onSelect = { newPerson ->
                if (newPerson == selectedPerson) return@PersonSelectorCard

                if (ui.isRunning) {
                    // 运行中：先弹窗确认
                    pendingSwitchTo = newPerson
                } else {
                    // 未运行：直接切
                    selectedPerson = newPerson
                    scope.launch { snackbarHostState.showSnackbar("已切换目标：$newPerson") }
                }
            }
        )

        StatusBanner(
            statusText = ui.statusText,
            guidanceText = ui.guidanceText,
            qualityPercent = ui.qualityPercent
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("呼吸频率", "${"%.1f".format(ui.breathBpm)} bpm", Modifier.weight(1f))
            MetricCard("AHI（估计）", "${"%.1f".format(ui.ahiTotal)}", Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("信号质量", "${ui.qualityPercent}%", Modifier.weight(1f))
            MetricCard("近30s事件", "${ui.eventCountRecent}", Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { openTonight(selectedPerson) },
                modifier = Modifier.weight(1f)
            ) { Text("Tonight") }

            OutlinedButton(
                onClick = { openHistory(selectedPerson) },
                modifier = Modifier.weight(1f)
            ) { Text("History") }
        }

        BreathChartCard(samples = ui.chart)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    isRunning = true
                    onStart(selectedPerson)   // ✅ 传 personId
                },
                enabled = !ui.isRunning,
                modifier = Modifier.weight(1f)
            ) { Text("开始监测") }

            OutlinedButton(
                onClick = {
                    isRunning = false
                    onStop()
                },
                enabled = ui.isRunning,
                modifier = Modifier.weight(1f)
            ) { Text("停止") }
        }

        AnimatedVisibility(visible = devMode) {
            DeveloperDebugSection(debugText = ui.debugText)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonSelectorCard(
    people: List<String>,
    selected: String,
    running: Boolean,
    personFromFrame: String?,
    onSelect: (String) -> Unit
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("目标人（多人轮换）", style = MaterialTheme.typography.titleMedium)

            if (!personFromFrame.isNullOrBlank()) {
                Text("当前帧标记 person_id：$personFromFrame", style = MaterialTheme.typography.bodySmall)
            }

            var expanded by remember { mutableStateOf(false) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selected,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择目标") },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        people.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p) },
                                onClick = {
                                    expanded = false
                                    if (p != selected) onSelect(p)
                                }
                            )
                        }
                    }
                }

                // 提示：你如果担心切换过程中用户误解，这里给个小状态
                AssistChip(
                    onClick = {},
                    label = { Text(if (running) "监测中" else "未监测") }
                )
            }

            Text(
                "说明：同一时刻只诊断一个人；切换后会用不同 streamId 让 ROI/baseline/posture/turn 等状态按人隔离。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StatusBanner(statusText: String, guidanceText: String, qualityPercent: Int) {
    Card {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(statusText, style = MaterialTheme.typography.titleMedium)
            Text(guidanceText, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = (qualityPercent.coerceIn(0, 100) / 100f),
                modifier = Modifier.fillMaxWidth()
            )
            Text("信号质量：$qualityPercent%", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun BreathChartCard(samples: FloatArray) {
    Card {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("胸廓运动（最近窗口）", style = MaterialTheme.typography.titleMedium)

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                factory = { ctx ->
                    LineChart(ctx).apply {
                        description.isEnabled = false
                        setTouchEnabled(false)
                        setPinchZoom(false)
                        setScaleEnabled(false)
                        legend.isEnabled = false

                        xAxis.apply {
                            position = XAxis.XAxisPosition.BOTTOM
                            setDrawGridLines(true)
                            textSize = 10f
                            axisMinimum = 0f
                            axisMaximum = WINDOW_SEC
                            labelCount = 7
                        }

                        axisLeft.apply {
                            setDrawGridLines(true)
                            textSize = 10f
                            axisMinimum = -2f
                            axisMaximum = 2f
                        }
                        axisRight.isEnabled = false
                    }
                },
                update = { chart ->
                    updateLineChart(chart, samples)
                }
            )

            Text(
                if (samples.isEmpty()) "暂无波形数据（等待 respEnv）" else "点数：${samples.size}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun updateLineChart(chart: LineChart, env: FloatArray) {
    if (env.isEmpty() || env.size < 2) {
        chart.clear()
        chart.invalidate()
        return
    }

    val n = env.size

    var mean = 0f
    for (v in env) mean += v
    mean /= n.toFloat()

    var sq = 0f
    for (v in env) {
        val d = v - mean
        sq += d * d
    }
    val std = kotlin.math.sqrt((sq / n.toFloat()).toDouble()).toFloat().coerceAtLeast(1e-6f)

    val norm = FloatArray(n)
    for (i in 0 until n) norm[i] = (env[i] - mean) / std

    val dt = WINDOW_SEC / (n - 1).toFloat()
    val entries = ArrayList<Entry>(n)
    for (i in 0 until n) entries.add(Entry(i * dt, norm[i]))

    val dataSet: LineDataSet
    if (chart.data != null && chart.data.dataSetCount > 0) {
        dataSet = chart.data.getDataSetByIndex(0) as LineDataSet
        dataSet.values = entries
        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
    } else {
        dataSet = LineDataSet(entries, "Chest Motion").apply {
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 1.5f
            mode = LineDataSet.Mode.LINEAR
        }
        chart.data = LineData(dataSet)
    }

    val minY = norm.minOrNull() ?: -2f
    val maxY = norm.maxOrNull() ?: 2f
    val margin = 0.5f
    chart.axisLeft.axisMinimum = minY - margin
    chart.axisLeft.axisMaximum = maxY + margin
    chart.xAxis.axisMinimum = 0f
    chart.xAxis.axisMaximum = WINDOW_SEC

    chart.invalidate()
}

@Composable
private fun DeveloperDebugSection(debugText: String) {
    var expanded by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("开发者调试信息", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开")
                }
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(
                        text = debugText.ifBlank { "(empty)" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/**
 * ======= 下面两块：你原来的 mapResultToUi + buildLegacyDebugText
 * 我保持原样（只要你粘贴时别漏）。
 */

private fun mapResultToUi(r: EngineResult?, isRunning: Boolean): LiveUiState {
    if (r == null) {
        return LiveUiState(
            isRunning = false,
            statusText = "未开始",
            guidanceText = "点击开始进行监测",
            qualityPercent = 0,
            debugText = ""
        )
    }

    val f: Map<String, Any?> = r.rawFeatures ?: emptyMap()

    val breath = (f["breath_freq"] as? Number)?.toFloat() ?: r.frame.breathBpm
    val q = ((f["quality_index"] as? Number)?.toFloat() ?: (r.frame.qualityIndex ?: 0f)).coerceIn(0f, 1f)
    val snr = (f["snr_db"] as? Number)?.toFloat() ?: r.frame.snrDb

    val ahiTotal = (f["ahi_total"] as? Number)?.toFloat()
        ?: (f["ahi_est"] as? Number)?.toFloat()
        ?: r.frame.ahiTotal

    val recent = (f["recent_event_count"] as? Number)?.toInt() ?: r.events.size

    val status = f["diagnosis"]?.toString()?.takeIf { it.isNotBlank() } ?: (r.frame.diagnosis ?: "监测中")
    val guidance = when {
        q < 0.2f -> "信号偏弱：调整手机位置，靠近胸腔并保持安静"
        q < 0.4f -> "信号一般：尽量保持静止，减少环境噪声"
        else -> "信号良好：保持当前姿势即可"
    }

    val chart = r.frame.respEnv
    val debugText = buildLegacyDebugText(r)

    return LiveUiState(
        isRunning = isRunning,
        statusText = "状态：$status",
        guidanceText = guidance,
        breathBpm = breath,
        qualityPercent = (q * 100f).roundToInt(),
        snrDb = snr,
        ahiTotal = ahiTotal,
        eventCountRecent = recent,
        chart = chart,
        debugText = debugText
    )
}


/**
 * ✅ 把你 MainActivity.onUpdate 里那坨 StringBuilder 原封不动搬过来
 * 注意：这里只负责“展示”，不做任何诊断逻辑改变
 */
private fun buildLegacyDebugText(result: EngineResult): String {
    val features: Map<String, Any?> = result.rawFeatures ?: emptyMap()
    val eventsRaw: List<Map<String, Any?>> = result.rawEvents ?: emptyList()
    val frame = result.frame

    val diagnosisRaw = features["diagnosis"]?.toString() ?: (frame.diagnosis ?: "诊断中…")

    val breathMain = (features["breath_freq"] as? Number)?.toFloat() ?: frame.breathBpm
    val breathFft = (features["breath_freq_fft"] as? Number)?.toFloat() ?: 0f
    val breathPeak = (features["breath_freq_peak"] as? Number)?.toFloat() ?: 0f
    val breathConf = (features["breath_fft_conf"] as? Number)?.toFloat() ?: 0f

    val decisionWinSec = (features["decision_period_sec"] as? Number)?.toFloat() ?: 30f
    val recentCountRaw = (features["recent_event_count"] as? Number)?.toInt() ?: 0

    val ahiTotalRaw = (features["ahi_total"] as? Number)?.toFloat()
        ?: (features["ahi_est"] as? Number)?.toFloat()
        ?: frame.ahiTotal
    val ahiCentralRaw = (features["ahi_central"] as? Number)?.toFloat() ?: frame.ahiCentral
    val ahiObstructiveRaw = (features["ahi_obstructive"] as? Number)?.toFloat() ?: frame.ahiObstructive
    val ahiHypopneaRaw = (features["ahi_hypopnea"] as? Number)?.toFloat() ?: frame.ahiHypopnea

    val qualityIndex = (features["quality_index"] as? Number)?.toFloat() ?: (frame.qualityIndex ?: -1f)
    val snrDb = (features["snr_db"] as? Number)?.toFloat() ?: (frame.snrDb ?: Float.NaN)
    val coverage = (features["coverage"] as? Number)?.toFloat() ?: (frame.coverage ?: Float.NaN)
    val baselineRebuild = (features["baseline_rebuild"] as? Boolean) ?: false

    val qualityGrade = (features["quality_grade"] as? Number)?.toInt() ?: (frame.qualityGrade ?: -1)
    val qualityLabel = features["quality_label"]?.toString() ?: (frame.qualityLabel ?: "")
    val ahiValidSec = (features["ahi_valid_sec"] as? Number)?.toFloat() ?: 0f
    val usableForAhi = (features["usable_for_ahi"] as? Boolean) ?: (frame.usableForAhi ?: false)

    val baselineReady = (features["baseline_ready"] as? Boolean) ?: false
    val gateReady = (features["peak_gate_ready"] as? Boolean) ?: false
    val presenceScore = (features["presence_score"] as? Number)?.toFloat() ?: -1f

    val peakCountAll = (features["peak_count_all"] as? Number)?.toInt() ?: -1
    val peakAccepted = (features["peak_accepted"] as? Number)?.toInt() ?: -1
    val peakCandidates = (features["peak_candidates"] as? Number)?.toInt() ?: -1

    val gateElapsedSec = (features["gate_elapsed_sec"] as? Number)?.toFloat() ?: -1f
    val gateWarmupNeedSec = (features["gate_warmup_need_sec"] as? Number)?.toFloat() ?: 45f
    val gateMinPeaksNeed = (features["gate_min_peaks_need"] as? Number)?.toInt() ?: 8
    val peakMinDistSec = (features["peak_min_dist_sec"] as? Number)?.toFloat() ?: 1.2f

    val baselineWinSec = (features["baseline_win_sec"] as? Number)?.toFloat() ?: 180f
    val baselineMinPeaksNeed = (features["baseline_min_peaks_need"] as? Number)?.toInt() ?: 10
    val baselinePeaksInWin = (features["baseline_peaks_in_win"] as? Number)?.toInt() ?: -1

    val freezeReason = features["baseline_freeze_reason"]?.toString() ?: ""

    // ===== ROI locker debug =====
    val roiState = features["roi_state"]?.toString() ?: ""
    val roiReason = features["roi_reason"]?.toString() ?: ""
    val roiBestBin = (features["roi_best_bin"] as? Number)?.toInt() ?: -1
    val roiBestScore = (features["roi_best_score"] as? Number)?.toFloat() ?: Float.NaN

    val roiLockBin = (features["roi_lock_bin"] as? Number)?.toInt() ?: -1
    val roiLockScore = (features["roi_lock_score"] as? Number)?.toFloat() ?: Float.NaN
    val roiCandidateBin = (features["roi_candidate_bin"] as? Number)?.toInt() ?: -1
    val roiCandidateScore = (features["roi_candidate_score"] as? Number)?.toFloat() ?: Float.NaN

    val roiHoldLeft = (features["roi_hold_left_sec"] as? Number)?.toFloat() ?: Float.NaN
    val roiSwitchCount = (features["roi_switch_count"] as? Number)?.toInt() ?: 0
    val roiStrengthRatio = (features["roi_strength_ratio"] as? Number)?.toFloat()
        ?: (features["presence_strength_ratio"] as? Number)?.toFloat()
        ?: Float.NaN

    val turnEvent = (features["turn_event"] as? Boolean) == true
    val turnActive = (features["turn_active"] as? Boolean) == true
    val turnReason = features["turn_reason"]?.toString() ?: ""
    val postureState = features["posture_state"]?.toString() ?: ""
    val postureReason = features["posture_reason"]?.toString() ?: ""
    val holdLeft = (features["turn_hold_left_sec"] as? Number)?.toFloat() ?: 0f

    // ===== Innovation UI fields (from Python) =====
    val qualityWeight = (features["quality_weight"] as? Number)?.toFloat() ?: Float.NaN
    val qualityTier = (features["quality_tier"]?.toString() ?: "")
    val ahiEffValidSec = (features["ahi_eff_valid_sec"] as? Number)?.toFloat()
        ?: (features["ahi_valid_sec"] as? Number)?.toFloat()
        ?: 0f

    val ahiTotalCiLow = (features["ahi_total_ci_low"] as? Number)?.toFloat() ?: Float.NaN
    val ahiTotalCiHigh = (features["ahi_total_ci_high"] as? Number)?.toFloat() ?: Float.NaN
    val ahiCentralCiLow = (features["ahi_central_ci_low"] as? Number)?.toFloat() ?: Float.NaN
    val ahiCentralCiHigh = (features["ahi_central_ci_high"] as? Number)?.toFloat() ?: Float.NaN
    val ahiObstructiveCiLow = (features["ahi_obstructive_ci_low"] as? Number)?.toFloat() ?: Float.NaN
    val ahiObstructiveCiHigh = (features["ahi_obstructive_ci_high"] as? Number)?.toFloat() ?: Float.NaN
    val ahiHypopneaCiLow = (features["ahi_hypopnea_ci_low"] as? Number)?.toFloat() ?: Float.NaN
    val ahiHypopneaCiHigh = (features["ahi_hypopnea_ci_high"] as? Number)?.toFloat() ?: Float.NaN

    val ciWidth = if (ahiTotalCiLow.isFinite() && ahiTotalCiHigh.isFinite())
        (ahiTotalCiHigh - ahiTotalCiLow) else Float.NaN

    val confidenceLevel = when {
        !ciWidth.isFinite() -> "未知"
        ciWidth <= 5f -> "高"
        ciWidth <= 15f -> "中"
        else -> "低"
    }

    fun anyToIntList(x: Any?): List<Int> {
        return when (x) {
            null -> emptyList()
            is IntArray -> x.toList()
            is Array<*> -> x.mapNotNull { (it as? Number)?.toInt() }
            is List<*> -> x.mapNotNull { (it as? Number)?.toInt() }
            else -> emptyList()
        }
    }

    fun anyToFloatList(x: Any?): List<Float> {
        return when (x) {
            null -> emptyList()
            is FloatArray -> x.toList()
            is DoubleArray -> x.map { it.toFloat() }
            is Array<*> -> x.mapNotNull { (it as? Number)?.toFloat() }
            is List<*> -> x.mapNotNull { (it as? Number)?.toFloat() }
            else -> emptyList()
        }
    }

    fun softCountN(eventsList: List<Map<String, Any?>>): Float {
        var s = 0f
        for (ev in eventsList) {
            val c = (ev["confidence"] as? Number)?.toFloat() ?: 1f
            val cc = when {
                c.isNaN() -> 1f
                c < 0f -> 0f
                c > 1f -> 1f
                else -> c
            }
            s += cc
        }
        return s
    }

    val roiTopkBins = anyToIntList(features["roi_topk_bins"])
    val roiTopkScores = anyToFloatList(features["roi_topk_scores"])
    val targetRangeBin = (features["target_range_bin"] as? Number)?.toInt() ?: -1

    // 事件 summary
    val eventSummary = if (eventsRaw.isEmpty()) {
        "无"
    } else {
        eventsRaw.joinToString(" | ") { ev ->
            val type = ev["type"]?.toString() ?: "?"
            val s = (ev["start_sec"] as? Number)?.toFloat() ?: 0f
            val e = (ev["end_sec"] as? Number)?.toFloat() ?: 0f
            val conf = (ev["confidence"] as? Number)?.toFloat() ?: Float.NaN
            val confTxt = if (conf.isFinite()) String.format(" conf=%.2f", conf) else ""
            "$type(${String.format("%.0f", s)}–${String.format("%.0f", e)}s$confTxt)"
        }
    }

    val nEffForUi = softCountN(eventsRaw)

    var diagnosisForUi = diagnosisRaw
    if (baselineRebuild) diagnosisForUi += "（检测到翻身，基线重建中…）"

    val sb = StringBuilder()
    sb.append("状态：").append(diagnosisForUi).append("\n")

    sb.append("呼吸频率：")
        .append(String.format("%.1f", breathMain))
        .append(" bpm")
    if (breathFft > 0f || breathPeak > 0f) {
        sb.append("  [FFT=")
            .append(String.format("%.1f", breathFft))
            .append(", Peak=")
            .append(String.format("%.1f", breathPeak))
            .append(", conf=")
            .append(String.format("%.2f", breathConf))
            .append("]")
    }
    sb.append("\n")

    sb.append("质量分层：")
    if (qualityWeight.isFinite()) sb.append("w=").append(String.format("%.2f", qualityWeight)) else sb.append("w=--")
    if (qualityTier.isNotBlank()) sb.append(" tier=").append(qualityTier)
    sb.append("  TST_eff=").append(String.format("%.0f", ahiEffValidSec)).append("s\n")

    sb.append("最近 ${decisionWinSec.toInt()} 秒事件数：$recentCountRaw\n")

    sb.append(
        "AHI 估计：总="
                + String.format("%.1f", ahiTotalRaw)
                + "  C="
                + String.format("%.1f", ahiCentralRaw)
                + "  O="
                + String.format("%.1f", ahiObstructiveRaw)
                + "  H="
                + String.format("%.1f", ahiHypopneaRaw)
    ).append("\n")

    sb.append("软计数：N_eff=").append(String.format("%.2f", nEffForUi))
        .append("（由事件confidence累加）\n")

    sb.append("AHI可信度等级：").append(confidenceLevel)
        .append("（CI宽度=").append(if (ciWidth.isFinite()) String.format("%.1f", ciWidth) else "--")
        .append("）\n")

    fun fmtCi(lo: Float, hi: Float): String {
        return if (lo.isFinite() && hi.isFinite()) {
            "[${String.format("%.1f", lo)}, ${String.format("%.1f", hi)}]"
        } else "--"
    }

    sb.append("AHI 置信区间：总=").append(fmtCi(ahiTotalCiLow, ahiTotalCiHigh))
        .append("  C=").append(fmtCi(ahiCentralCiLow, ahiCentralCiHigh))
        .append("  O=").append(fmtCi(ahiObstructiveCiLow, ahiObstructiveCiHigh))
        .append("  H=").append(fmtCi(ahiHypopneaCiLow, ahiHypopneaCiHigh))
        .append("\n")

    sb.append("事件时间线：").append(eventSummary)

    sb.append("\n\n--- Debug(Gate/Baseline) ---\n")

    sb.append("baseline_ready=").append(baselineReady)
        .append("  (win=").append(baselineWinSec.toInt()).append("s")
        .append(", need_peaks=").append(baselineMinPeaksNeed)
        .append(", have=").append(baselinePeaksInWin)
        .append(")\n")

    sb.append("peak_gate_ready=").append(gateReady)
        .append("  (warmup_need=").append(gateWarmupNeedSec.toInt()).append("s")
        .append(", elapsed=").append(String.format("%.1f", gateElapsedSec))
        .append("s, need_peaks=").append(gateMinPeaksNeed)
        .append(", have=").append(peakCountAll)
        .append(", min_dist=").append(String.format("%.2f", peakMinDistSec)).append("s")
        .append(")\n")

    sb.append("peaks: accepted=").append(peakAccepted)
        .append(", candidates=").append(peakCandidates)
        .append("\n")

    if (freezeReason.isNotBlank()) {
        sb.append("baseline_frozen_reason=").append(freezeReason).append("\n")
    }

    sb.append("\n--- Debug(ROI locker) ---\n")
    if (roiState.isNotBlank()) {
        sb.append("roi_state=").append(roiState)
            .append("  reason=").append(roiReason)
            .append("\n")

        sb.append("target_range_bin=").append(targetRangeBin)
            .append("  presence=").append(String.format("%.2f", presenceScore))
            .append("  strength_ratio=").append(if (roiStrengthRatio.isFinite()) String.format("%.2f", roiStrengthRatio) else "--")
            .append("\n")

        sb.append("best=").append(roiBestBin)
            .append(" (").append(if (roiBestScore.isFinite()) String.format("%.3f", roiBestScore) else "--")
            .append(")")
            .append("  cand=").append(roiCandidateBin)
            .append(" (").append(if (roiCandidateScore.isFinite()) String.format("%.3f", roiCandidateScore) else "--")
            .append(")")
            .append("  lock=").append(roiLockBin)
            .append(" (").append(if (roiLockScore.isFinite()) String.format("%.3f", roiLockScore) else "--")
            .append(")")
            .append("\n")

        sb.append("hold_left=").append(if (roiHoldLeft.isFinite()) String.format("%.1f", roiHoldLeft) else "--")
            .append("s")
            .append("  switch_count=").append(roiSwitchCount)
            .append("\n")

        if (roiTopkBins.isNotEmpty()) {
            val k = minOf(roiTopkBins.size, roiTopkScores.size, 5)
            val pairs = (0 until k).joinToString(", ") { i ->
                val b = roiTopkBins[i]
                val s = roiTopkScores[i]
                "$b:${String.format("%.3f", s)}"
            }
            sb.append("topk=").append(pairs).append("\n")
        }
    } else {
        sb.append("roi fields not present.\n")
    }

    sb.append("\n--- Debug(Posture/Turn) ---\n")
    sb.append("posture_state=").append(postureState)
        .append("  reason=").append(postureReason).append("\n")

    sb.append("turn_active=").append(turnActive)
        .append("  hold_left=").append(String.format("%.1f", holdLeft)).append("s")
        .append("  reason=").append(turnReason).append("\n")

    if (turnEvent) {
        sb.append("!!! TURN DETECTED !!!  ").append(turnReason).append("\n")
    }

    return sb.toString()
}
