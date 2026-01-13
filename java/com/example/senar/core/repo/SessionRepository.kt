package com.example.senar.core.repo

import android.content.Context
import org.json.JSONObject
import com.example.senar.core.storage.AppDatabase
import com.example.senar.core.storage.dao.SessionAggRow
import com.example.senar.core.storage.entity.DiagFrameEntity
import com.example.senar.core.storage.entity.SleepEventEntity
import com.example.senar.core.storage.entity.SleepSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionSummary(
    val session: SleepSessionEntity,
    val agg: SessionAggRow?,
    val events: List<SleepEventEntity>
)

class SessionRepository private constructor(private val db: AppDatabase) {

    companion object {
        fun from(ctx: Context) = SessionRepository(AppDatabase.get(ctx))
    }

    private val sdfDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ✅ 新增 personId：隔离落库的关键
    suspend fun startSession(
        streamId: String,
        personId: String,
        diagType: String = "SONAR"
    ): Long {
        val now = System.currentTimeMillis()
        val dayKey = sdfDay.format(Date(now))
        return db.sleepSessionDao().insert(
            SleepSessionEntity(
                streamId = streamId,
                personId = personId,
                startEpochMs = now,
                endEpochMs = null,
                dayKey = dayKey,
                diagType = diagType
            )
        )
    }

    suspend fun endSession(sessionId: Long) {
        db.sleepSessionDao().markEnded(sessionId, System.currentTimeMillis())
    }

    suspend fun insertFrame(
        sessionId: Long,
        tsEpochMs: Long,
        features: Map<String, Any?>,
        rawDebugText: String?
    ) {
        val breath = (features["breath_freq"] as? Number)?.toFloat()
        val q = (features["quality_index"] as? Number)?.toFloat()
        val snr = (features["snr_db"] as? Number)?.toFloat()

        val ahiTotal = (features["ahi_total"] as? Number)?.toFloat()
            ?: (features["ahi_est"] as? Number)?.toFloat()
        val ahiC = (features["ahi_central"] as? Number)?.toFloat()
        val ahiO = (features["ahi_obstructive"] as? Number)?.toFloat()
        val ahiH = (features["ahi_hypopnea"] as? Number)?.toFloat()

        val rawJson = JSONObject()
        for ((k, v) in features) rawJson.put(k, v ?: JSONObject.NULL)

        db.diagFrameDao().insert(
            DiagFrameEntity(
                sessionId = sessionId,
                tsEpochMs = tsEpochMs,
                breathBpm = breath,
                qualityIndex = q,
                snrDb = snr,
                ahiTotal = ahiTotal,
                ahiCentral = ahiC,
                ahiObstructive = ahiO,
                ahiHypopnea = ahiH,
                rawDebugText = rawDebugText,
                rawFeaturesJson = rawJson.toString()
            )
        )
    }

    suspend fun insertEvents(sessionId: Long, events: List<SleepEventEntity>) {
        val dao = db.sleepEventDao()
        for (e in events) {
            // 你现在的 eventKey 去重逻辑保持不动 :contentReference[oaicite:3]{index=3}
            if (dao.exists(sessionId, e.eventKey) == 0) dao.insert(e)
        }
    }

    // ✅ 最新：按 personId
    suspend fun latestSessionSummary(personId: String): SessionSummary? {
        val s = db.sleepSessionDao().latestByPerson(personId) ?: return null
        val agg = db.diagFrameDao().aggregateForSession(s.id)
        val events = db.sleepEventDao().listBySession(s.id)
        return SessionSummary(s, agg, events)
    }

    // ✅ 按天：按 personId
    suspend fun listSummariesByDay(dayKey: String, personId: String): List<SessionSummary> {
        val sessions = db.sleepSessionDao().listByDayAndPerson(dayKey, personId)
        val out = ArrayList<SessionSummary>(sessions.size)
        for (s in sessions) {
            val agg = db.diagFrameDao().aggregateForSession(s.id)
            val events = db.sleepEventDao().listBySession(s.id)
            out.add(SessionSummary(s, agg, events))
        }
        return out
    }
}
