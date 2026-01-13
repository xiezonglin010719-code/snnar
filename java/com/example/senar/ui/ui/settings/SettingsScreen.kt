package com.example.senar.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.senar.core.settings.DevSettings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val devMode by DevSettings.devModeFlow(ctx).collectAsState(initial = false)

    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("开发者模式")
        Switch(
            checked = devMode,
            onCheckedChange = { enabled ->
                scope.launch { DevSettings.setDevMode(ctx, enabled) }
            }
        )
    }
}
