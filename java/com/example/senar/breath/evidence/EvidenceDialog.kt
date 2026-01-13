package com.example.senar.breath.evidence

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

@Composable
fun EvidenceDialog() {
    val st by BreathEvidenceStore.state.collectAsState()
    val id = st.selectedId ?: return
    val clip = st.items[id] ?: return

    AlertDialog(
        onDismissRequest = { BreathEvidenceStore.select(null) },
        confirmButton = {
            TextButton(onClick = { BreathEvidenceStore.select(null) }) { Text("关闭") }
        },
        title = { Text("证据回放：${clip.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("sev=${clip.sevIdx}  u=${"%.3f".format(clip.u)}  defer=${clip.defer}")
                Text("note=${clip.note}", style = MaterialTheme.typography.bodySmall)

                clip.envelope?.let { env ->
                    Text("呼吸 envelope（30s）", style = MaterialTheme.typography.bodySmall)
                    Sparkline(env, Modifier.fillMaxWidth().height(90.dp))
                }

                clip.heat64?.let { heat ->
                    Text("证据热力图（log-mel pooled ch0）", style = MaterialTheme.typography.bodySmall)
                    Heatmap64(heat, Modifier.fillMaxWidth().height(160.dp))
                }

                // 波形回放：这里先画当前窗 cur30 的简化波形（采样显示）
                clip.cur30?.let { pcm ->
                    Text("当前30s 波形（下采样显示）", style = MaterialTheme.typography.bodySmall)
                    Sparkline(downsampleAbs(pcm, 300), Modifier.fillMaxWidth().height(90.dp))
                }
                // 你也可以加 pre30 / post30 两段的切换按钮
            }
        }
    )
}

@Composable
private fun Sparkline(y: FloatArray, modifier: Modifier = Modifier) {
    if (y.isEmpty()) return
    val ymin = y.minOrNull() ?: 0f
    val ymax = y.maxOrNull() ?: 1f
    val span = max(1e-6f, ymax - ymin)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val n = y.size
        var prev = Offset(0f, h - (y[0] - ymin) / span * h)
        for (i in 1 until n) {
            val x = i.toFloat() / (n - 1) * w
            val yy = h - (y[i] - ymin) / span * h
            val cur = Offset(x, yy)
            drawLine(Color(0xFF1E88E5), prev, cur, strokeWidth = 2f)
            prev = cur
        }
    }
}

@Composable
private fun Heatmap64(v: FloatArray, modifier: Modifier = Modifier) {
    if (v.size != 64 * 64) return
    val vmin = v.minOrNull() ?: 0f
    val vmax = v.maxOrNull() ?: 1f
    val span = max(1e-6f, vmax - vmin)

    Canvas(modifier = modifier) {
        val cellW = size.width / 64f
        val cellH = size.height / 64f
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val t = ((v[y * 64 + x] - vmin) / span).coerceIn(0f, 1f)
                // 简单灰度：t 越大越亮
                val c = Color(t, t, t, 1f)
                drawRect(
                    color = c,
                    topLeft = Offset(x * cellW, y * cellH),
                    size = androidx.compose.ui.geometry.Size(cellW, cellH)
                )
            }
        }
    }
}

private fun downsampleAbs(pcm: FloatArray, points: Int): FloatArray {
    if (pcm.isEmpty()) return FloatArray(0)
    val hop = max(1, pcm.size / points)
    val out = FloatArray(points)
    var oi = 0
    var i = 0
    while (i < pcm.size && oi < points) {
        var s = 0.0
        var c = 0
        val end = min(pcm.size, i + hop)
        while (i < end) {
            s += kotlin.math.abs(pcm[i]).toDouble()
            c++; i++
        }
        out[oi] = (s / max(1, c)).toFloat()
        oi++
    }
    return out.copyOf(oi)
}
