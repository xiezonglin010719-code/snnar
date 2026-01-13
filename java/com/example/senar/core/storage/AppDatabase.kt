package com.example.senar.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.senar.core.storage.dao.DiagFrameDao
import com.example.senar.core.storage.dao.SleepEventDao
import com.example.senar.core.storage.dao.SleepSessionDao
import com.example.senar.core.storage.entity.DiagFrameEntity
import com.example.senar.core.storage.entity.SleepEventEntity
import com.example.senar.core.storage.entity.SleepSessionEntity

@Database(
    entities = [SleepSessionEntity::class, DiagFrameEntity::class, SleepEventEntity::class],
    version = 2,              // ✅ 1 -> 2
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun diagFrameDao(): DiagFrameDao
    abstract fun sleepEventDao(): SleepEventDao

    companion object {
        @Volatile private var I: AppDatabase? = null

        /**
         * v1 -> v2: sleep_sessions 新增 personId，用于按人隔离数据
         *
         * 说明：
         * - SQLite 支持 ALTER TABLE ADD COLUMN
         * - NOT NULL 必须给 DEFAULT，否则老行会违反约束
         * - 默认给 'p1'（你也可以换成 'default'）
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) 加列
                db.execSQL(
                    "ALTER TABLE sleep_sessions ADD COLUMN personId TEXT NOT NULL DEFAULT 'p1'"
                )

                // 2) 如果你在 Entity 上加了索引（推荐），这里补建索引，避免全表扫
                //    注意：索引名要稳定；Room 生成的名字通常是 index_<table>_<col...>
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sleep_sessions_personId_dayKey ON sleep_sessions(personId, dayKey)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sleep_sessions_streamId ON sleep_sessions(streamId)"
                )
            }
        }

        fun get(ctx: Context): AppDatabase {
            return I ?: synchronized(this) {
                I ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "senar.db")
                    // ✅ 不要再 destructive（否则升级会清库，Reports 按人就没意义了）
                    .addMigrations(MIGRATION_1_2)
                    // 如果你开发期还想保底不崩，可以只对 debug 打开 destructive：
                    // .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { I = it }
            }
        }
    }
}
