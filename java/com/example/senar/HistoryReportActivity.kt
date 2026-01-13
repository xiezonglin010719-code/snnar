package com.example.senar

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.senar.core.repo.SessionRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERSON_ID = "extra_person_id"
    }

    private val sdfDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val sdfTime = SimpleDateFormat("HH:mm", Locale.US)

    private lateinit var tvDay: TextView
    private lateinit var list: ListView
    private lateinit var tvDetail: TextView

    private var dayKey: String = sdfDay.format(Calendar.getInstance().time)

    // ✅ 新增：目标人
    private var personId: String = "p1"

    private val repo by lazy { SessionRepository.from(this) }

    private var summaries = emptyList<com.example.senar.core.repo.SessionSummary>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_report)

        // ✅ 从 Intent 读 personId
        personId = intent.getStringExtra(EXTRA_PERSON_ID) ?: "p1"
        title = "History ($personId)"  // 没有布局控件就用标题提示

        tvDay = findViewById(R.id.tvDay)
        list = findViewById(R.id.listSessions)
        tvDetail = findViewById(R.id.tvDetail)

        tvDay.text = dayKey
        tvDay.setOnClickListener { pickDay() }

        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos in summaries.indices) showDetail(summaries[pos])
        }

        reload()
    }

    private fun pickDay() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val c = Calendar.getInstance()
                c.set(y, m, d, 0, 0, 0)
                dayKey = sdfDay.format(c.time)
                tvDay.text = dayKey
                reload()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun reload() {
        lifecycleScope.launch {
            // ✅ 关键：按 personId 过滤
            val data = runCatching { repo.listSummariesByDay(dayKey, personId) }
                .getOrElse { emptyList() }

            val personId = intent.getStringExtra(TonightReportActivity.Companion.EXTRA_PERSON_ID)
                ?: MultiPersonDiagnosis.DEFAULT_PERSON_ID

            summaries = data

            val items = summaries.map { sum ->
                val s = sum.session
                val start = if (s.startEpochMs > 0) sdfTime.format(Date(s.startEpochMs)) else "--:--"
                val end = s.endEpochMs?.let { sdfTime.format(Date(it)) } ?: "--:--"
                val nEvt = sum.events.size
                "$start-$end  ${s.diagType}  evt=$nEvt  stream=${s.streamId.take(8)}..."
            }

            list.adapter = ArrayAdapter(
                this@HistoryReportActivity,
                android.R.layout.simple_list_item_1,
                items
            )
            tvDetail.text = if (summaries.isEmpty()) {
                "该日期没有报告（personId=$personId）"
            } else {
                "点击一条记录查看详情（personId=$personId）"
            }
        }
    }

    private fun showDetail(sum: com.example.senar.core.repo.SessionSummary) {
        val s = sum.session
        val agg = sum.agg
        val events = sum.events

        val sb = StringBuilder()
        sb.append("personId=").append(personId).append("\n")
        sb.append("sessionId=").append(s.id).append("\n")
        sb.append("diagType=").append(s.diagType).append("\n")
        sb.append("streamId=").append(s.streamId).append("\n")
        sb.append("start=").append(Date(s.startEpochMs)).append("\n")
        sb.append("end=").append(s.endEpochMs?.let { Date(it) } ?: "--").append("\n\n")

        sb.append("--- Agg ---\n")
        sb.append(agg?.toString() ?: "数据不足").append("\n\n")

        sb.append("--- Events (").append(events.size).append(") ---\n")
        if (events.isEmpty()) {
            sb.append("无事件\n")
        } else {
            for (e in events.take(200)) {
                val confTxt = e.confidence?.let { String.format(Locale.US, " conf=%.2f", it) } ?: ""
                sb.append("${e.type}  ${"%.1f".format(e.startSec)}s -> ${"%.1f".format(e.endSec)}s$confTxt\n")
            }
            if (events.size > 200) sb.append("...(仅展示前 200 条)\n")
        }

        tvDetail.text = sb.toString()
    }
}
