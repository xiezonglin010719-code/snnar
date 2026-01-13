package com.example.senar.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.senar.core.storage.entity.DiagFrameEntity

data class SessionAggRow(
    val sessionId: Long,
    val frameCount: Int,
    val avgBreath: Float?,
    val avgQuality: Float?,
    val lastAhiTotal: Float?,
    val lastAhiCentral: Float?,
    val lastAhiObstructive: Float?,
    val lastAhiHypopnea: Float?,
    val lastTsEpochMs: Long?,
)

@Dao
interface DiagFrameDao {
    @Insert
    suspend fun insert(frame: DiagFrameEntity)

    @Query("SELECT COUNT(*) FROM diag_frames WHERE sessionId=:sessionId")
    suspend fun countFrames(sessionId: Long): Int

    // 用子查询拿“最后一帧 AHI”，同时做均值统计
    @Query(
        """
        SELECT 
          :sessionId as sessionId,
          COUNT(*) as frameCount,
          AVG(breathBpm) as avgBreath,
          AVG(qualityIndex) as avgQuality,
          (SELECT ahiTotal FROM diag_frames WHERE sessionId=:sessionId ORDER BY tsEpochMs DESC LIMIT 1) as lastAhiTotal,
          (SELECT ahiCentral FROM diag_frames WHERE sessionId=:sessionId ORDER BY tsEpochMs DESC LIMIT 1) as lastAhiCentral,
          (SELECT ahiObstructive FROM diag_frames WHERE sessionId=:sessionId ORDER BY tsEpochMs DESC LIMIT 1) as lastAhiObstructive,
          (SELECT ahiHypopnea FROM diag_frames WHERE sessionId=:sessionId ORDER BY tsEpochMs DESC LIMIT 1) as lastAhiHypopnea,
          (SELECT tsEpochMs FROM diag_frames WHERE sessionId=:sessionId ORDER BY tsEpochMs DESC LIMIT 1) as lastTsEpochMs
        FROM diag_frames
        WHERE sessionId=:sessionId
        """
    )
    suspend fun aggregateForSession(sessionId: Long): SessionAggRow?
}
