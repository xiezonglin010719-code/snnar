package com.example.senar.ui.breath

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.senar.breath.BreathRealtimeStore
import com.example.senar.breath.BreathService
import com.example.senar.breath.offline.BreathOfflineRunner
import kotlinx.coroutines.launch

@Composable
fun BreathScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by BreathRealtimeStore.state.collectAsState()

    // NPZ picker
    val pickNpz = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch { BreathOfflineRunner.runNpZ(ctx, uri) }
        }
    }

    // MP4 picker
    val pickMp4 = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch { BreathOfflineRunner.runMp4(ctx, uri) }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("呼吸实时诊断", style = MaterialTheme.typography.titleLarge)

        // -------- realtime card --------
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("状态：${state.statusText}", style = MaterialTheme.typography.titleMedium)
                Text("最近推理：${state.lastInferenceText}", style = MaterialTheme.typography.bodyMedium)
                Text("defer：${state.defer}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = state.quality.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("输入质量：${(state.quality * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !state.running,
                onClick = {
                    val intent = Intent(ctx, BreathService::class.java).apply {
                        action = BreathService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                    else ctx.startService(intent)
                }
            ) { Text("开始呼吸监测") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = state.running,
                onClick = {
                    ctx.startService(Intent(ctx, BreathService::class.java).apply {
                        action = BreathService.ACTION_STOP
                    })
                }
            ) { Text("停止") }
        }

        // -------- offline module --------
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("离线诊断", style = MaterialTheme.typography.titleMedium)

                Text("状态：${state.offlineStatusText}")
                if (state.offlineBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !state.offlineBusy,
                        onClick = { pickNpz.launch(arrayOf("*/*")) }
                    ) { Text("选择 NPZ") }

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !state.offlineBusy,
                        onClick = { pickMp4.launch(arrayOf("video/*")) }
                    ) { Text("选择 MP4") }
                }

                if (state.offlineResultText.isNotBlank()) {
                    Text("结果：${state.offlineResultText}", style = MaterialTheme.typography.bodyMedium)
                }

                if (state.offlineDebugText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Offline Debug", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(state.offlineDebugText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // -------- debug --------
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Realtime Debug", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(state.debugText.ifBlank { "(empty)" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
