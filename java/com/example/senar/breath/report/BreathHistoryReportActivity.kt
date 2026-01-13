package com.example.senar.breath.reports

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.senar.MultiPersonDiagnosis
import com.example.senar.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BreathHistoryReportActivity : AppCompatActivity() {

    private lateinit var tv: TextView
    private val repo by lazy { BreathReportRepository.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_breath_history_report)
        tv = findViewById(R.id.tvBreathHistory)
        render()
    }

    private fun render() {
        lifecycleScope.launch {

            val personId = intent.getStringExtra(EXTRA_PERSON_ID)
                ?: MultiPersonDiagnosis.DEFAULT_PERSON_ID

            val grouped = repo.listGroupedByDay(limitDays = 60)
            if (grouped.isEmpty()) {
                tv.text = "暂无呼吸历史报告（在线跑一次或导入离线 NPZ）。"
                return@launch
            }

            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val sb = StringBuilder()

            for ((day, sessions) in grouped) {
                sb.append("== ").append(day).append(" ==\n")
                for (s in sessions.sortedByDescending { it.createdAtMs }) {
                    val tag = if (s.source == BreathReportRepository.Source.OFFLINE) "OFFLINE" else "ONLINE"
                    val start = s.timeline.firstOrNull()?.tsMs ?: 0L
                    val end = s.timeline.lastOrNull()?.tsMs ?: 0L
                    sb.append("- [").append(tag).append("] ")
                        .append(s.label)
                        .append(" | seg=").append(s.timeline.size)
                        .append(" | ").append(if (start > 0) sdf.format(Date(start)) else "--")
                        .append(" ~ ").append(if (end > 0) sdf.format(Date(end)) else "--")
                        .append("\n")
                }
                sb.append("\n")
            }

            tv.text = sb.toString()
        }
    }

    companion object {
        const val EXTRA_PERSON_ID = "extra_person_id"
    }
}
