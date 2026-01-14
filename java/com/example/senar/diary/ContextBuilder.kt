package com.example.senar.ai

import com.example.senar.diary.SleepDiaryEntry

object ContextBuilder {

    fun diaryToText(list: List<SleepDiaryEntry>): String {
        if (list.isEmpty()) return "（近7天无睡眠日记记录）"

        val avgStress = list.map { it.stressLevel }.average()
        val avgNasal = list.map { it.nasalCongestion }.average()
        val avgScreen = list.map { it.screenMinutesBeforeBed }.average()

        val caffeineDays = list.count { it.caffeineMg > 0 }
        val alcoholDays = list.count { it.alcohol }
        val lateMealDays = list.count { it.lateMeal }
        val exerciseAvg = list.map { it.exerciseMinutes }.average()

        return buildString {
            append("【近7天生活方式统计（来自睡眠日记）】\n")
            append("- 记录天数：${list.size}\n")
            append("- 咖啡因摄入：$caffeineDays 天\n")
            append("- 饮酒：$alcoholDays 天\n")
            append("- 睡前2小时进食：$lateMealDays 天\n")
            append("- 平均运动时长：${"%.1f".format(exerciseAvg)} 分钟/天\n")
            append("- 睡前屏幕使用：${"%.0f".format(avgScreen)} 分钟（均值）\n")
            append("- 压力水平：${"%.1f".format(avgStress)} / 10（均值）\n")
            append("- 鼻塞/过敏：${"%.1f".format(avgNasal)} / 10（均值）\n")

            append("\n【逐日简要】\n")
            list.take(7).forEach { e ->
                append(
                    "- ${e.date}：" +
                            "咖啡因=${e.caffeineMg}mg，" +
                            "酒精=${if (e.alcohol) "是" else "否"}，" +
                            "运动=${e.exerciseMinutes}min，" +
                            "屏幕=${e.screenMinutesBeforeBed}min，" +
                            "压力=${e.stressLevel}/10，" +
                            "鼻塞=${e.nasalCongestion}/10\n"
                )
            }
        }
    }
}
