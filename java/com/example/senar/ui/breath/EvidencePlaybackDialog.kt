package com.example.senar.ui.breath

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.senar.breath.evidence.EvidenceClip
import kotlin.math.max
import kotlin.math.min

@Composable
fun EvidencePlaybackDialog(
    clip: EvidenceClip,
    onDismiss: () -> Unit
) {
    // ✅ 把 heat64 转成 Bitmap（只在 heat64 变化时重新计算）
    val heatBitmap: Bitmap? = remember(clip.heat64) {
        val h = clip.heat64
        if (h == null || h.size != 64 * 64) return@remember null

        // 找 min/max
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in h) {
            mn = min(mn, v)
            mx = max(mx, v)
        }
        val range = (mx - mn).takeIf { it > 1e-6f } ?: 1f

        // 灰度 Bitmap（64x64）
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val v = h[y * 64 + x]
                val norm = ((v - mn) / range).coerceIn(0f, 1f)
                val g = (norm * 255f).toInt()
                bmp.setPixel(x, y, Color.rgb(g, g, g))
            }
        }
        bmp
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("证据回放：${clip.title}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                // --- 基本结论信息 ---
                Text(
                    "sev=${clip.sevIdx}  " +
                            "u=${"%.3f".format(clip.u)}  " +
                            "defer=${clip.defer}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    "p=" + clip.p.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) },
                    style = MaterialTheme.typography.bodyMedium
                )

                // --- 三段窗口 ---
                Text(
                    "pre30=${clip.pre30?.size ?: 0}  " +
                            "cur30=${clip.cur30?.size ?: 0}  " +
                            "post30=${clip.post30?.size ?: 0}",
                    style = MaterialTheme.typography.bodySmall
                )

                if (clip.post30 == null) {
                    Text(
                        "post30 还未补齐：等下一窗到来会自动补。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // --- Envelope 预览（呼吸通路可选） ---
                val env = clip.envelope
                if (env != null && env.isNotEmpty()) {
                    val mn = env.minOrNull() ?: 0f
                    val mx = env.maxOrNull() ?: 0f
                    Text(
                        "envelope：${env.size} 点  min=${"%.3f".format(mn)}  max=${"%.3f".format(mx)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text("envelope：暂无", style = MaterialTheme.typography.bodySmall)
                }

                // --- Heat64：真正显示成图片 ---
                if (heatBitmap != null) {
                    Text("Spectrogram (64×64)", style = MaterialTheme.typography.titleSmall)

                    Image(
                        bitmap = heatBitmap.asImageBitmap(),
                        contentDescription = "Breath spectrogram 64x64",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentScale = ContentScale.FillBounds
                    )

                    // 同时保留 min/max 文字（对调试有用）
                    val h = clip.heat64!!
                    val mn = h.minOrNull() ?: 0f
                    val mx = h.maxOrNull() ?: 0f
                    Text(
                        "heat64：min=${"%.3f".format(mn)}  max=${"%.3f".format(mx)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text("heat64：暂无（当前 clip 未提供 64×64 数据）", style = MaterialTheme.typography.bodySmall)
                }

                // --- 备注 ---
                if (clip.note.isNotBlank()) {
                    Text(
                        "note：${clip.note}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    )
}
