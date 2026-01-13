package com.example.senar.core.storage

import androidx.room.TypeConverter

object Converters {
    // 预留：以后你要存 List<Float>/Map 等再扩展
    @TypeConverter
    @JvmStatic
    fun fromStringList(v: List<String>?): String? = v?.joinToString("|")

    @TypeConverter
    @JvmStatic
    fun toStringList(v: String?): List<String>? =
        v?.split("|")?.filter { it.isNotBlank() }
}
