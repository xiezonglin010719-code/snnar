package com.example.senar.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.senar.core.storage.entity.SleepEventEntity

@Dao
interface SleepEventDao {

    // 用 eventKey 去重：插入前先查是否存在（更通用）
    @Query("SELECT COUNT(*) FROM sleep_events WHERE sessionId=:sessionId AND eventKey=:eventKey")
    suspend fun exists(sessionId: Long, eventKey: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: SleepEventEntity)

    @Query("SELECT * FROM sleep_events WHERE sessionId=:sessionId ORDER BY startSec ASC")
    suspend fun listBySession(sessionId: Long): List<SleepEventEntity>

    @Query("SELECT COUNT(*) FROM sleep_events WHERE sessionId=:sessionId")
    suspend fun countBySession(sessionId: Long): Int
}
