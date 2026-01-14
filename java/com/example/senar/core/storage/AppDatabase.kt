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
import com.example.senar.diary.SleepDiaryDao
import com.example.senar.diary.SleepDiaryEntry

@Database(
    entities = [
        SleepSessionEntity::class,
        DiagFrameEntity::class,
        SleepEventEntity::class,
        SleepDiaryEntry::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun diagFrameDao(): DiagFrameDao
    abstract fun sleepEventDao(): SleepEventDao
    abstract fun sleepDiaryDao(): SleepDiaryDao

    companion object {
        @Volatile private var I: AppDatabase? = null

        // v1 -> v2：给 sleep_sessions 加 personId（如果你历史上确实经历过 v1）
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 如果列已经存在会报错，所以用 try/catch 保护
                runCatching {
                    db.execSQL("ALTER TABLE sleep_sessions ADD COLUMN personId TEXT NOT NULL DEFAULT 'p1'")
                }
                // 索引同理
                runCatching {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_sleep_sessions_personId_dayKey " +
                                "ON sleep_sessions(personId, dayKey)"
                    )
                }
                runCatching {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_sleep_sessions_streamId " +
                                "ON sleep_sessions(streamId)"
                    )
                }
            }
        }

        // v2 -> v3：新增 sleep_diary + “强制把 sleep_sessions 修成 Expected 的 schema”
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) 创建 sleep_diary（如果你之前已经建过，也不会有问题）
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sleep_diary (
                        date TEXT NOT NULL PRIMARY KEY,
                        caffeineMg INTEGER NOT NULL DEFAULT 0,
                        alcohol INTEGER NOT NULL DEFAULT 0,
                        exerciseMinutes INTEGER NOT NULL DEFAULT 0,
                        screenMinutesBeforeBed INTEGER NOT NULL DEFAULT 0,
                        lateMeal INTEGER NOT NULL DEFAULT 0,
                        stressLevel INTEGER NOT NULL DEFAULT 0,
                        nasalCongestion INTEGER NOT NULL DEFAULT 0,
                        note TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )

                // 2) 读取旧 sleep_sessions 结构（防止你设备上是“中间态”）
                val oldCols = mutableSetOf<String>()
                db.query("PRAGMA table_info(sleep_sessions)").use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    while (c.moveToNext()) {
                        oldCols += c.getString(nameIdx)
                    }
                }

                // 3) 重建 sleep_sessions 为 Room Expected 的结构
                //    注意：字段名/类型/NULL 约束必须跟你的 SleepSessionEntity 一致
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sleep_sessions_new (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        dayKey TEXT NOT NULL,
                        streamId TEXT NOT NULL,
                        startEpochMs INTEGER NOT NULL,
                        diagType TEXT NOT NULL,
                        personId TEXT NOT NULL DEFAULT 'p1',
                        endEpochMs INTEGER
                    )
                    """.trimIndent()
                )

                // 4) 组织 INSERT：旧表有的列就拷贝，没有就用默认值
                //    你截图里 Expected 的 defaultValue 都是 'undefined'（Room 打印），
                //    实际 SQLite 里我们用合理默认值/或固定字符串，避免 NOT NULL 冲突。
                fun has(col: String) = oldCols.contains(col)

                // dayKey/diagType/startEpochMs 在你旧库里可能也没有（尤其如果你早期版本更简）
                // 这里用：
                // - dayKey：若旧表没有，就给 '1970-01-01'
                // - diagType：若旧表没有，就给 'unknown'
                // - startEpochMs：若旧表没有，就给 0
                // - personId：若旧表没有，就给 'p1'
                val selectDayKey = if (has("dayKey")) "dayKey" else "'1970-01-01'"
                val selectDiagType = if (has("diagType")) "diagType" else "'unknown'"
                val selectStart = if (has("startEpochMs")) "startEpochMs" else "0"
                val selectPerson = if (has("personId")) "personId" else "'p1'"
                val selectEnd = if (has("endEpochMs")) "endEpochMs" else "NULL"

                // streamId/id 基本都有；如果你旧库连 streamId 都没，那你历史数据本来也无法对齐
                val selectId = if (has("id")) "id" else "NULL"
                val selectStream = if (has("streamId")) "streamId" else "''"

                db.execSQL(
                    """
                    INSERT INTO sleep_sessions_new (id, dayKey, streamId, startEpochMs, diagType, personId, endEpochMs)
                    SELECT $selectId, $selectDayKey, $selectStream, $selectStart, $selectDiagType, $selectPerson, $selectEnd
                    FROM sleep_sessions
                    """.trimIndent()
                )

                // 5) 替换旧表
                db.execSQL("DROP TABLE sleep_sessions")
                db.execSQL("ALTER TABLE sleep_sessions_new RENAME TO sleep_sessions")

                // 6) 重建索引（必须跟 Expected 一致）
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sleep_sessions_dayKey ON sleep_sessions(dayKey)"
                )
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { I = it }
            }
        }
    }
}
