package com.example.senar.diary

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SleepDiaryScreen(vm: SleepDiaryViewModel) {
    val s = vm.ui.value

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("睡眠日记", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = s.date,
            onValueChange = { vm.ui.value = s.copy(date = it) },
            label = { Text("日期 (YYYY-MM-DD)") },
            modifier = Modifier.fillMaxWidth()
        )

        IntField("咖啡因 (mg)", s.caffeineMg) { vm.ui.value = s.copy(caffeineMg = it) }
        BoolField("饮酒", s.alcohol) { vm.ui.value = s.copy(alcohol = it) }
        IntField("运动 (分钟)", s.exerciseMinutes) { vm.ui.value = s.copy(exerciseMinutes = it) }
        IntField("睡前屏幕时间 (分钟)", s.screenMinutesBeforeBed) { vm.ui.value = s.copy(screenMinutesBeforeBed = it) }
        BoolField("睡前2小时内进食", s.lateMeal) { vm.ui.value = s.copy(lateMeal = it) }
        IntField("压力 (0-10)", s.stressLevel) { vm.ui.value = s.copy(stressLevel = it.coerceIn(0, 10)) }
        IntField("鼻塞/过敏 (0-10)", s.nasalCongestion) { vm.ui.value = s.copy(nasalCongestion = it.coerceIn(0, 10)) }

        OutlinedTextField(
            value = s.note,
            onValueChange = { vm.ui.value = s.copy(note = it) },
            label = { Text("备注") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save() }) { Text("保存") }
            if (s.savedHint.isNotBlank()) {
                Text(s.savedHint, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun IntField(label: String, v: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = v.toString(),
        onValueChange = { onChange(it.toIntOrNull() ?: 0) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BoolField(label: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = v, onCheckedChange = onChange)
    }
}
