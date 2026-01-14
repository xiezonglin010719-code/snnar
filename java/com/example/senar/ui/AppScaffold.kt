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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import com.example.senar.ai.SleepAssistantScreen
import com.example.senar.ai.SleepAssistantViewModel

import androidx.compose.ui.platform.LocalContext
import com.example.senar.ai.*
import com.example.senar.diary.*
import com.example.senar.ReportRepository
import com.example.senar.core.storage.AppDatabase


sealed class BottomDest(val route: String, val label: String) {
    object Live : BottomDest("live", "Live")
    object Breath : BottomDest("breath", "呼吸")

    // ✅ 新增：睡眠日记
    object Diary : BottomDest("diary", "日记")

    // ✅ 新增：睡眠助理（AI对话）
    object Assistant : BottomDest("assistant", "助理")
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
        BottomDest.Diary,       // ✅ 新增
        BottomDest.Assistant,   // ✅ 新增
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
                                BottomDest.Diary -> Icon(Icons.Filled.Edit, null)        // ✅ 新增
                                BottomDest.Assistant -> Icon(Icons.Filled.Chat, null)    // ✅ 新增
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

            composable(BottomDest.Diary.route) {
                val ctx = LocalContext.current
                val dao = remember { DbProvider.diaryDao(ctx) }

                val vm = remember { SleepDiaryViewModel(dao) }
                SleepDiaryScreen(vm)
            }

            composable(BottomDest.Assistant.route) {
                val ctx = LocalContext.current

                val db = remember { AppDatabase.get(ctx) }

                val diaryDao = remember { db.sleepDiaryDao() }
                val sessionDao = remember { db.sleepSessionDao() }



                val reportProvider = remember {
                    object : ReportSummaryProvider {
                        override suspend fun latestNightSummary(): String {
                            val sessions = ReportRepository.loadAllSessions(ctx)
                            val s = sessions.firstOrNull()
                            return if (s == null) {
                                "当前没有找到 rt_*.npz（请先跑一次实时诊断生成数据）"
                            } else {
                                "最近一次声纳会话：streamId=${s.streamId}\n" +
                                        "日期=${s.dayKey}\n" +
                                        "ts=${s.lastTsSec}\n" +
                                        "npz=${s.lastNpz.name}"
                            }
                        }
                    }
                }

                // ================== ✅ 就放在这里 ==================
                // 1️⃣ 读取 Key（你可以换成写死的字符串）
                val rawKey = "sk-b267529e9b8f495eae098f5a1a98ee3b"   // ← 这里换成你真实的 OpenRouter Key
                val apiKey = rawKey.trim()

                // 2️⃣ 校验（防止 OkHttp header 报 unexpected char）
                require(apiKey.startsWith("sk-") || apiKey.startsWith("or-")) {
                    "OpenRouter API key 看起来不对：$apiKey"
                }
                require(apiKey.all { it.code in 32..126 }) {
                    "API key 含有非 ASCII 字符（可能混入中文/换行）"
                }

                // 3️⃣ 创建 client（注意用 apiKey 作为 remember key）
                val client = remember {
                    QwenClient(
                        apiKey = "sk-b267529e9b8f495eae098f5a1a98ee3b",
                        model = "qwen-turbo",
                        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
                    )
                }

                // ================== ✅ 到这里结束 ==================

                val vm = remember {
                    SleepAssistantViewModel(
                        appContext = ctx.applicationContext,
                        client = client,
                        diaryDao = diaryDao,
                        personId = "p1",                 // ✅ 跟你的 Reports 一致

                    )
                }

                SleepAssistantScreen(vm)
            }

        }
    }
}
