package com.example.senar.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SleepAssistantScreen(vm: SleepAssistantViewModel) {
    val msgs by vm.msgs.collectAsState()
    val loading by vm.loading.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("睡眠助理", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true
        ) {
            items(msgs.asReversed()) { m ->
                val align = if (m.fromUser) Arrangement.End else Arrangement.Start
                Row(Modifier.fillMaxWidth(), horizontalArrangement = align) {
                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            m.text,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("问：我昨晚为什么睡眠质量下降？") }
            )
            Button(
                enabled = input.isNotBlank() && !loading,
                onClick = {
                    val text = input.trim()
                    input = ""
                    vm.send(text)
                }
            ) { Text("发送") }
        }
    }
}
