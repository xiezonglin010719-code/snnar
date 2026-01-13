package com.example.senar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import com.example.senar.breath.reports.BreathHistoryReportActivity
import com.example.senar.breath.reports.BreathTonightReportActivity
import com.example.senar.core.diag.EngineResult
import com.example.senar.ui.AppScaffold

class MainActivity : ComponentActivity(), AudioProcessingService.UpdateListener {

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
    }

    @Volatile
    private var engineResultListener: ((EngineResult) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestAudioPermission()

        AudioProcessingService.setListener(this)

        setContent {
            MaterialTheme {
                AppScaffold(
                    registerEngineResultListener = { cb ->
                        engineResultListener = cb
                        { if (engineResultListener === cb) engineResultListener = null }
                    },
                    onStart = { personId -> startDiagnosis(personId) },  // ✅ 改
                    onStop = { stopDiagnosis() },

                    openTonight = { personId ->
                        startActivity(Intent(this, TonightReportActivity::class.java).apply {
                            putExtra(TonightReportActivity.EXTRA_PERSON_ID, personId)
                        })
                    },
                    openHistory = { personId ->
                        startActivity(Intent(this, HistoryReportActivity::class.java).apply {
                            putExtra(HistoryReportActivity.EXTRA_PERSON_ID, personId)
                        })
                    },

                    openBreathTonight = { personId ->
                        startActivity(Intent(this, BreathTonightReportActivity::class.java).apply {
                            putExtra(BreathTonightReportActivity.EXTRA_PERSON_ID, personId)
                        })
                    },
                    openBreathHistory = { personId ->
                        startActivity(Intent(this, BreathHistoryReportActivity::class.java).apply {
                            putExtra(BreathHistoryReportActivity.EXTRA_PERSON_ID, personId)
                        })
                    },
                )
            }
        }
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO
            )
        }
    }

    /** ✅ 启动时带 personId（默认 p1） */
    private fun startDiagnosis(personId: String) {
        val it = Intent(this, AudioProcessingService::class.java).apply {
            putExtra(AudioProcessingService.EXTRA_PERSON_ID, personId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it)
    }

    private fun stopDiagnosis() {
        stopService(Intent(this, AudioProcessingService::class.java))
    }

    /**
     * ✅ 运行中切换目标人（给你一个调用示例）
     * 你做 UI 下拉选择后，选中某个人就调用这个。
     */
    private fun switchPerson(personId: String) {
        val it = Intent(this, AudioProcessingService::class.java).apply {
            action = AudioProcessingService.ACTION_SET_PERSON
            putExtra(AudioProcessingService.EXTRA_PERSON_ID, personId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it)
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioProcessingService.setListener(null)
        engineResultListener = null
    }

    override fun onUpdate(result: EngineResult) {
        engineResultListener?.invoke(result)
    }
}
