package com.example.senar.breath.reports

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.senar.MultiPersonDiagnosis
import com.example.senar.R
import com.example.senar.breath.BreathReportGenerator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class BreathTonightReportActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvBasic: TextView
    private lateinit var tvAhi: TextView
    private lateinit var tvBreath: TextView
    private lateinit var tvQuality: TextView
    private lateinit var tvEvents: TextView

    private val repo by lazy { BreathReportRepository.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tonight_report)

        tvTitle = findViewById(R.id.tvSessionTitle)
        tvBasic = findViewById(R.id.tvSessionBasic)
        tvAhi = findViewById(R.id.tvSessionAhi)
        tvBreath = findViewById(R.id.tvSessionBreath)
        tvQuality = findViewById(R.id.tvSessionQuality)
        tvEvents = findViewById(R.id.tvSessionEvents)

        render()
    }

    private fun render() {
        lifecycleScope.launch {
            val todayKey = repo.todayKey()
            val s = repo.latestSessionForDay(todayKey) ?: repo.latestSession()
            val personId = intent.getStringExtra(EXTRA_PERSON_ID)
                ?: MultiPersonDiagnosis.DEFAULT_PERSON_ID

            if (s == null) {
                tvTitle.text = "呼吸今晚报告"
                tvBasic.text = "暂无呼吸报告记录（在线先跑一次，或导入一次离线 NPZ）。"
                tvAhi.text = ""
                tvBreath.text = ""
                tvQuality.text = ""
                tvEvents.text = ""
                return@launch
            }

            val tag = when (s.source) {
                BreathReportRepository.Source.ONLINE -> "ONLINE"
                BreathReportRepository.Source.OFFLINE -> "OFFLINE"
            }

            tvTitle.text = "呼吸报告（$tag）"

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val startStr = s.timeline.firstOrNull()?.tsMs?.let { sdf.format(Date(it)) } ?: "--"
            val endStr = s.timeline.lastOrNull()?.tsMs?.let { sdf.format(Date(it)) } ?: "--"
            val durationMin = if (s.timeline.size >= 2) {
                val dur = (s.timeline.last().tsMs - s.timeline.first().tsMs) / 1000.0
                (dur / 60.0).roundToInt()
            } else 0

            tvBasic.text =
                "label：${s.label}\n" +
                        "日期：${s.dayKey}\n" +
                        "开始：$startStr\n" +
                        "结束：$endStr\n" +
                        "段数(5min)：${s.timeline.size}\n" +
                        "时长：${durationMin} 分钟"

            // 用你现有的 BreathReportGenerator 做整夜汇总
            val report = BreathReportGenerator.generate(s.timeline)

            tvAhi.text =
                "整夜汇总：\n" +
                        "overallSevIdx=${report.overallSevIdx}\n" +
                        "overallP=${
                            report.overallP.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
                        }\n" +
                        "deferSegments=${report.deferSegments.size}"

            // 这里给一个“呼吸/可信度摘要”（你后面想精确显示 b15/b30，再把模型输出加进 FiveMinResult 或 Evidence）
            val last = s.timeline.last()
            tvBreath.text =
                "最近一段：sev=${last.sevIdx}\n" +
                        "p=${
                            last.p.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
                        }\n" +
                        "q=${"%.2f".format(last.q)} uEff=${"%.3f".format(last.uEff)} weight=${"%.2f".format(last.weight)}\n" +
                        "defer=${last.defer} action=${last.action.name}\n" +
                        "reason=${last.reason}"

            tvQuality.text =
                "报告摘要：\n" + report.summaryText

            tvEvents.text =
                "时间线（前10条展示）：\n" +
                        report.timeline.take(10).joinToString("\n") { it2 ->
                            val t = sdf.format(Date(it2.tsMs))
                            "[$t] sev=${it2.sevIdx} uEff=${"%.3f".format(it2.uEff)} defer=${it2.defer} q=${"%.2f".format(it2.q)}"
                        }
        }
    }

    companion object {
        const val EXTRA_PERSON_ID = "extra_person_id"
    }

}
