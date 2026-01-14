package com.example.senar.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun todayStr(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

data class DiaryUiState(
    val date: String = todayStr(),
    val caffeineMg: Int = 0,
    val alcohol: Boolean = false,
    val exerciseMinutes: Int = 0,
    val screenMinutesBeforeBed: Int = 0,
    val lateMeal: Boolean = false,
    val stressLevel: Int = 0,
    val nasalCongestion: Int = 0,
    val note: String = "",
    val savedHint: String = ""
)

class SleepDiaryViewModel(private val dao: SleepDiaryDao) : ViewModel() {
    var ui = androidx.compose.runtime.mutableStateOf(DiaryUiState())
        private set

    fun load(date: String) {
        viewModelScope.launch {
            val e = dao.getByDate(date)
            if (e != null) {
                ui.value = ui.value.copy(
                    date = e.date,
                    caffeineMg = e.caffeineMg,
                    alcohol = e.alcohol,
                    exerciseMinutes = e.exerciseMinutes,
                    screenMinutesBeforeBed = e.screenMinutesBeforeBed,
                    lateMeal = e.lateMeal,
                    stressLevel = e.stressLevel,
                    nasalCongestion = e.nasalCongestion,
                    note = e.note,
                    savedHint = ""
                )
            } else {
                ui.value = DiaryUiState(date = date)
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            val s = ui.value
            dao.upsert(
                SleepDiaryEntry(
                    date = s.date,
                    caffeineMg = s.caffeineMg,
                    alcohol = s.alcohol,
                    exerciseMinutes = s.exerciseMinutes,
                    screenMinutesBeforeBed = s.screenMinutesBeforeBed,
                    lateMeal = s.lateMeal,
                    stressLevel = s.stressLevel,
                    nasalCongestion = s.nasalCongestion,
                    note = s.note
                )
            )
            ui.value = ui.value.copy(savedHint = "已保存 ${s.date}")
        }
    }
}
