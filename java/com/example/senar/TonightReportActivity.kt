package com.example.senar

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.senar.core.repo.SessionRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class TonightReportActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvBasic: TextView
    private lateinit var tvAhi: TextView
    private lateinit var tvBreath: TextView
    private lateinit var tvQuality: TextView
    private lateinit var tvEvents: TextView

    companion object {
        private const val TAG = "TonightReportActivity"
        const val EXTRA_PERSON_ID = "extra_person_id"
    }

    private val repo by lazy { SessionRepository.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tonight_report)

        tvTitle = findViewById(R.id.tvSessionTitle)
        tvBasic = findViewById(R.id.tvSessionBasic)
        tvAhi = findViewById(R.id.tvSessionAhi)
        tvBreath = findViewById(R.id.tvSessionBreath)
        tvQuality = findViewById(R.id.tvSessionQuality)
        tvEvents = findViewById(R.id.tvSessionEvents)

        renderLatestFromDb()
    }

    private fun renderLatestFromDb() {
        lifecycleScope.launch {
            runCatching {
                val personId = intent.getStringExtra(EXTRA_PERSON_ID)
                    ?: MultiPersonDiagnosis.DEFAULT_PERSON_ID

                val sum = repo.latestSessionSummary(personId) // ✅ 按人取最新
                if (sum == null) {
                    tvTitle.text = "本次报告"
                    tvBasic.text = "当前没有找到 Session 记录，请先完成一次诊断。"
                    tvAhi.text = ""
                    tvBreath.text = ""
                    tvQuality.text = ""
                    tvEvents.text = ""
                    return@launch
                }

                val s = sum.session
                val agg = sum.agg
                val events = sum.events

                tvTitle.text = "本次报告（${s.diagType}）"

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val startStr = if (s.startEpochMs > 0) sdf.format(Date(s.startEpochMs)) else "--"
                val endStr = s.endEpochMs?.let { sdf.format(Date(it)) } ?: "--"
                val durationSec = if (s.endEpochMs != null && s.endEpochMs!! > s.startEpochMs)
                    (s.endEpochMs!! - s.startEpochMs) / 1000.0
                else 0.0
                val durMin = (durationSec / 60.0).roundToInt()

                tvBasic.text =
                    "streamId：${s.streamId.take(12)}...\n" +
                            "开始时间：$startStr\n结束时间：$endStr\n总时长：${durMin} 分钟"

                // ✅ AHI：你现在的 agg 字段我拿不到具体列名（你没贴 SessionAggRow 定义）
                // 先做“可运行版本”：把 agg.toString() 打出来（后面你贴 SessionAggRow，我再把显示升级成 AHI/均值/范围）
                tvAhi.text = "AHI（来自聚合/最后帧）：\n" + (agg?.toString() ?: "数据不足")

                // 同理：呼吸/质量先展示 agg 的 toString（可跑），后续按字段精确展示
                tvBreath.text = "呼吸统计：\n" + (agg?.toString() ?: "数据不足")
                tvQuality.text = "信号质量统计：\n" + (agg?.toString() ?: "数据不足")

                // ✅ 事件时间线（Room 里是 SleepEventEntity）
                if (events.isEmpty()) {
                    tvEvents.text = "事件时间线：无明显呼吸暂停/低通气事件"
                } else {
                    val sb = StringBuilder("事件时间线（共 ${events.size} 个）：\n")
                    for (e in events) {
                        val confTxt = e.confidence?.let { String.format(Locale.US, " conf=%.2f", it) } ?: ""
                        sb.append("- ${e.type}：${"%.0f".format(e.startSec)}–${"%.0f".format(e.endSec)} s$confTxt\n")
                    }
                    tvEvents.text = sb.toString()
                }

            }.onFailure { e ->
                Log.e(TAG, "renderLatestFromDb failed: ${e.message}", e)
                tvBasic.text = "报告加载失败：${e.message}"
            }
        }
    }
}
