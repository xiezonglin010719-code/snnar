package com.example.senar.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.senar.core.storage.entity.SleepSessionEntity

@Dao
interface SleepSessionDao {

    /* ===================== 写入 ===================== */

    @Insert
    suspend fun insert(session: SleepSessionEntity): Long

    @Query("UPDATE sleep_sessions SET endEpochMs=:endMs WHERE id=:sessionId")
    suspend fun markEnded(sessionId: Long, endMs: Long)

    /* ===================== 原有接口（保留，兼容旧逻辑） ===================== */

    @Query("""
        SELECT * FROM sleep_sessions
        ORDER BY startEpochMs DESC
        LIMIT 1
    """)
    suspend fun latest(): SleepSessionEntity?

    @Query("""
        SELECT * FROM sleep_sessions
        WHERE dayKey=:dayKey
        ORDER BY startEpochMs DESC
    """)
    suspend fun listByDay(dayKey: String): List<SleepSessionEntity>

    /* ===================== ✅ 新增：多目标隔离接口 ===================== */

    /**
     * 按 personId 获取最新 session（多目标模式使用）
     */
    @Query("""
        SELECT * FROM sleep_sessions
        WHERE personId=:personId
        ORDER BY startEpochMs DESC
        LIMIT 1
    """)
    suspend fun latestByPerson(personId: String): SleepSessionEntity?

    /**
     * 按 dayKey + personId 列出 sessions（Reports 按人筛选）
     */
    @Query("""
        SELECT * FROM sleep_sessions
        WHERE dayKey=:dayKey AND personId=:personId
        ORDER BY startEpochMs DESC
    """)
    suspend fun listByDayAndPerson(
        dayKey: String,
        personId: String
    ): List<SleepSessionEntity>

    /**
     * （可选）某个人的全部 sessions（不分天）
     */
    @Query("""
        SELECT * FROM sleep_sessions
        WHERE personId=:personId
        ORDER BY startEpochMs DESC
    """)
    suspend fun listByPerson(personId: String): List<SleepSessionEntity>


    // com.example.senar.core.storage.dao.SleepSessionDao

    @Query("""
    SELECT * FROM sleep_sessions
    WHERE dayKey >= :startDayKey AND dayKey <= :endDayKey
    ORDER BY startEpochMs DESC
""")
    suspend fun listByDayRange(
        startDayKey: String,
        endDayKey: String
    ): List<SleepSessionEntity>

    @Query("""
    SELECT * FROM sleep_sessions
    WHERE personId = :personId
      AND dayKey >= :startDayKey AND dayKey <= :endDayKey
    ORDER BY startEpochMs DESC
""")
    suspend fun listByPersonAndDayRange(
        personId: String,
        startDayKey: String,
        endDayKey: String
    ): List<SleepSessionEntity>

}
