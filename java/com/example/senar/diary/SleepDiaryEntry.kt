package com.example.senar.diary

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_diary")
data class SleepDiaryEntry(
    @PrimaryKey val date: String, // "YYYY-MM-DD"（按本地日期）
    val caffeineMg: Int = 0,       // 咖啡因估算（可用杯数换算）
    val alcohol: Boolean = false,
    val exerciseMinutes: Int = 0,
    val screenMinutesBeforeBed: Int = 0,
    val lateMeal: Boolean = false, // 睡前2h内进食
    val stressLevel: Int = 0,      // 0-10
    val nasalCongestion: Int = 0,  // 0-10 鼻塞/过敏
    val note: String = ""
)
