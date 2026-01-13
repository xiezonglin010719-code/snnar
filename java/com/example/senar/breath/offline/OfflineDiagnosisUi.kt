package com.example.senar.breath.offline

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.senar.breath.BreathInferenceEngine
import com.example.senar.breath.BreathRealtimeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OfflineDiagnosisCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by BreathRealtimeStore.state.collectAsState()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        pickedUri = uri
        if (uri != null) {
            // 仅用于显示名字：实际 copyToCache 里会再取一次 name
            BreathRealtimeStore.setOfflineSelected(uri.toString())
        }
    }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("离线诊断", style = MaterialTheme.typography.titleMedium)

            Text(
                text = "支持：.npz 或 .wav（mp4 建议先转 wav/npz；下一步我可以给你补 mp4->wav 抽取器）",
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        picker.launch(arrayOf("application/octet-stream", "audio/*"))
                    }
                ) {
                    Text("选择文件")
                }

                Button(
                    enabled = pickedUri != null,
                    onClick = {
                        val uri = pickedUri ?: return@Button
                        scope.launch {
                            BreathRealtimeStore.setOfflineStatus("处理中…")
                            try {
                                val engine = BreathInferenceEngine(ctx)
                                val mgr = OfflineDiagnosisManager(ctx, engine)

                                val file = withContext(Dispatchers.IO) { mgr.copyToCache(uri) }
                                BreathRealtimeStore.setOfflineStatus("推理中…")

                                val res = withContext(Dispatchers.Default) { mgr.runOnce(file) }
                                BreathRealtimeStore.setOfflineResult(res)
                            } catch (t: Throwable) {
                                BreathRealtimeStore.setOfflineStatus("失败：${t.message}")
                            }
                        }
                    }
                ) {
                    Text("运行离线诊断")
                }
            }

            if (state.offlineSelectedName.isNotBlank()) {
                Text("已选：${state.offlineSelectedName}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.offlineStatus.isNotBlank()) {
                Text("状态：${state.offlineStatus}", style = MaterialTheme.typography.bodySmall)
            }

            val r = state.offlineResult
            if (r != null) {
                Text("结果：${r.label}", style = MaterialTheme.typography.titleMedium)

                Text(
                    text =
                        "p=${r.p.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }}  " +
                                "u=${"%.3f".format(r.u)}  defer=${r.defer}",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(6.dp))

                SelectionContainer {
                    Text(
                        text = r.debug,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
