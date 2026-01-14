// com.example.senar.diary.DbProvider.kt
package com.example.senar.diary

import android.content.Context
import com.example.senar.core.storage.AppDatabase

object DbProvider {
    fun diaryDao(context: Context): SleepDiaryDao {
        return AppDatabase.get(context).sleepDiaryDao()
    }
}
