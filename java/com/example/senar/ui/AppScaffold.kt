package com.example.senar.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.*
import com.example.senar.core.diag.EngineResult
import com.example.senar.ui.breath.BreathScreen
import com.example.senar.ui.live.LiveScreen
import com.example.senar.ui.reports.ReportsScreen
import com.example.senar.ui.settings.SettingsScreen

sealed class BottomDest(val route: String, val label: String) {
    object Live : BottomDest("live", "Live")
    object Breath : BottomDest("breath", "呼吸")
    object Reports : BottomDest("reports", "Reports")
    object Settings : BottomDest("settings", "Settings")
}

@Composable
fun AppScaffold(
    registerEngineResultListener: (cb: (EngineResult) -> Unit) -> (() -> Unit),
    onStart: (personId: String) -> Unit,
    onStop: () -> Unit,

    // ===== Sonar 报告 =====
    openTonight: (personId: String) -> Unit,
    openHistory: (personId: String) -> Unit,

    // ===== Breath 报告 =====
    openBreathTonight: (personId: String) -> Unit,
    openBreathHistory: (personId: String) -> Unit,
) {
    val navController = rememberNavController()

    // sonar 结果（原逻辑保留）
    var engineResult by remember { mutableStateOf<EngineResult?>(null) }
    DisposableEffect(Unit) {
        val unregister = registerEngineResultListener { r -> engineResult = r }
        onDispose { unregister() }
    }

    val items = listOf(
        BottomDest.Live,
        BottomDest.Breath,
        BottomDest.Reports,
        BottomDest.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination?.route

                items.forEach { d ->
                    NavigationBarItem(
                        selected = current == d.route,
                        onClick = {
                            navController.navigate(d.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            when (d) {
                                BottomDest.Live -> Icon(Icons.Filled.PlayArrow, null)
                                BottomDest.Breath -> Icon(Icons.Filled.Air, null)
                                BottomDest.Reports -> Icon(Icons.Filled.List, null)
                                BottomDest.Settings -> Icon(Icons.Filled.Settings, null)
                            }
                        },
                        label = { Text(d.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomDest.Live.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomDest.Live.route) {
                LiveScreen(
                    engineResult = engineResult,
                    onStart = onStart,
                    onStop = onStop,
                    openTonight = openTonight,
                    openHistory = openHistory,
                )
            }

            // ✅ 呼吸页（独立服务）
            composable(BottomDest.Breath.route) {
                BreathScreen()
            }

            // ✅ Reports：改成四个入口
            composable(BottomDest.Reports.route) {
                ReportsScreen(
                    openSonarTonight = { personId -> openTonight(personId) },
                    openSonarHistory = { personId -> openHistory(personId) },
                    openBreathTonight = { personId -> openBreathTonight(personId) },
                    openBreathHistory = { personId -> openBreathHistory(personId) },
                )
            }

            composable(BottomDest.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
