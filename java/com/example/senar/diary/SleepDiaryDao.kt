package com.example.senar.diary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SleepDiaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SleepDiaryEntry)

    @Query("SELECT * FROM sleep_diary WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): SleepDiaryEntry?


    @Query("""
    SELECT * FROM sleep_diary
    WHERE date >= :fromDate
    ORDER BY date DESC
""")
    suspend fun since(fromDate: String): List<SleepDiaryEntry>


    @Query("SELECT * FROM sleep_diary ORDER BY date DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<SleepDiaryEntry>

    @Query("SELECT * FROM sleep_diary WHERE date BETWEEN :start AND :end ORDER BY date ASC")
    suspend fun between(start: String, end: String): List<SleepDiaryEntry>

}
