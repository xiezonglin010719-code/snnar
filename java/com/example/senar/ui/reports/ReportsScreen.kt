package com.example.senar.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.senar.MultiPersonDiagnosis
import com.example.senar.breath.BreathRealtimeStore
import com.example.senar.ui.breath.EvidencePlaybackDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    openSonarTonight: (personId: String) -> Unit,
    openSonarHistory: (personId: String) -> Unit,
    openBreathTonight: (personId: String) -> Unit,
    openBreathHistory: (personId: String) -> Unit,
) {
    val state by BreathRealtimeStore.state.collectAsState()

    // ✅ 目标人：先给 3 个示例（你也可以替换成 dad/mom/me）
    val people = remember {
        listOf(
            MultiPersonDiagnosis.DEFAULT_PERSON_ID, // "p1"
            "p2",
            "p3"
        )
    }
    var selectedPerson by remember { mutableStateOf(MultiPersonDiagnosis.DEFAULT_PERSON_ID) }

    var showEvidence by remember { mutableStateOf(false) }
    var currentTsMs by remember { mutableStateOf<Long?>(null) }
    val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Reports", style = MaterialTheme.typography.titleLarge)

        // ✅ 人选择下拉
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目标人", style = MaterialTheme.typography.titleMedium)

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        value = selectedPerson,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("personId") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        people.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p) },
                                onClick = {
                                    selectedPerson = p
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Text("提示：Reports 会按该目标人过滤展示/跳转", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ✅ 入口：全部带 selectedPerson
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("报告入口", style = MaterialTheme.typography.titleMedium)

                Text("声纳报告入口", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { openSonarTonight(selectedPerson) }, modifier = Modifier.fillMaxWidth()) {
                    Text("声纳：今晚报告")
                }
                OutlinedButton(onClick = { openSonarHistory(selectedPerson) }, modifier = Modifier.fillMaxWidth()) {
                    Text("声纳：历史报告")
                }

                Text("呼吸报告入口", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { openBreathTonight(selectedPerson) }, modifier = Modifier.fillMaxWidth()) {
                    Text("呼吸：今晚报告")
                }
                OutlinedButton(onClick = { openBreathHistory(selectedPerson) }, modifier = Modifier.fillMaxWidth()) {
                    Text("呼吸：历史报告")
                }
            }
        }

        // ===== 你原来的呼吸证据列表（不动）=====
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("呼吸：5min 诊断列表（点击查看证据）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "段数：${state.timeline.size}   证据条数：${state.evidences.size}",
                    style = MaterialTheme.typography.bodySmall
                )

                if (state.timeline.isEmpty()) {
                    Text("暂无数据：先在呼吸页运行实时采集。", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.timeline.asReversed()) { r ->
                            val t = sdf.format(Date(r.tsMs))
                            val clip = state.evidences.firstOrNull { it.tsMs == r.tsMs }
                            val hasEvidence = clip != null
                            val tag = if (hasEvidence) "✅证据" else "❌无证据"

                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = hasEvidence) {
                                        currentTsMs = r.tsMs
                                        showEvidence = true
                                    }
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("$t  sev=${r.sevIdx}  $tag", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "p=${r.p.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "q=${"%.2f".format(r.q)}  " +
                                                "uModel=${"%.3f".format(r.uModel)}  " +
                                                "uEff=${"%.3f".format(r.uEff)}  " +
                                                "w=${"%.2f".format(r.weight)}  " +
                                                "defer=${r.defer}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (r.reason.isNotBlank()) {
                                        Text("reason=${r.reason}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showEvidence && currentTsMs != null) {
            val clip = state.evidences.firstOrNull { it.tsMs == currentTsMs }
            if (clip != null) {
                EvidencePlaybackDialog(
                    clip = clip,
                    onDismiss = {
                        showEvidence = false
                        currentTsMs = null
                    }
                )
            } else {
                LaunchedEffect(currentTsMs) {
                    showEvidence = false
                    currentTsMs = null
                }
            }
        }
    }
}
