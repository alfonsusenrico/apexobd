package com.apexobd.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

enum class WidgetDisplayType {
    ANALOG_DIAL,
    SEMI_DIGITAL,
    DIGITAL,
    BAR,
    CHART,
    MINI_MAP,
    DTC_CHECKER,
    PERF_TIMER,
    DATA_LOGGER
}

@Entity(tableName = "hud_pages")
data class HudPageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sortOrder: Int
)

@Entity(
    tableName = "hud_widgets",
    foreignKeys = [
        ForeignKey(
            entity = HudPageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId")]
)
data class HudWidgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val sensorKey: String,
    val displayType: String, // WidgetDisplayType.name
    val spanCols: Int,       // 1, 2, or 4
    val spanRows: Int,       // 1 or 2
    val sortOrder: Int,
    val warnThreshold: Double? = null,
    val critThreshold: Double? = null
)

@Entity(tableName = "custom_pids")
data class CustomPidEntity(
    @PrimaryKey val key: String,
    val name: String,
    val shortName: String,
    val modeAndPidHex: String,
    val formula: String,
    val minValue: Double,
    val maxValue: Double,
    val unit: String,
    val headerHex: String? = null
)

@Entity(tableName = "trip_sessions")
data class TripSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val distanceKm: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val peakRpm: Double = 0.0,
    val best0100Sec: Double? = null,
    val sampleCount: Int = 0
)

@Entity(
    tableName = "telemetry_samples",
    foreignKeys = [
        ForeignKey(
            entity = TripSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class TelemetrySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val gpsSpeedKmh: Double?,
    val payloadJson: String
)

@Dao
interface HudDao {
    @Query("SELECT * FROM hud_pages ORDER BY sortOrder ASC")
    fun observePages(): Flow<List<HudPageEntity>>

    @Query("SELECT * FROM hud_pages ORDER BY sortOrder ASC")
    suspend fun getPagesOnce(): List<HudPageEntity>

    @Insert
    suspend fun insertPage(page: HudPageEntity): Long

    @Query("DELETE FROM hud_pages WHERE id = :pageId")
    suspend fun deletePage(pageId: Long)

    @Query("SELECT * FROM hud_widgets ORDER BY sortOrder ASC")
    fun observeAllWidgets(): Flow<List<HudWidgetEntity>>

    @Query("SELECT * FROM hud_widgets WHERE pageId = :pageId ORDER BY sortOrder ASC")
    suspend fun getWidgetsForPage(pageId: Long): List<HudWidgetEntity>

    @Insert
    suspend fun insertWidget(widget: HudWidgetEntity): Long

    @Insert
    suspend fun insertWidgets(widgets: List<HudWidgetEntity>)

    @Update
    suspend fun updateWidget(widget: HudWidgetEntity)

    @Update
    suspend fun updateWidgets(widgets: List<HudWidgetEntity>)

    @Query("DELETE FROM hud_widgets WHERE id = :widgetId")
    suspend fun deleteWidget(widgetId: Long)
}

@Dao
interface CustomPidDao {
    @Query("SELECT * FROM custom_pids ORDER BY name ASC")
    fun observeCustomPids(): Flow<List<CustomPidEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CustomPidEntity>)

    @Query("DELETE FROM custom_pids WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trip_sessions ORDER BY startedAtMs DESC")
    fun observeSessions(): Flow<List<TripSessionEntity>>

    @Insert
    suspend fun insertSession(session: TripSessionEntity): Long

    @Update
    suspend fun updateSession(session: TripSessionEntity)

    @Insert
    suspend fun insertSample(sample: TelemetrySampleEntity)

    @Query("SELECT * FROM telemetry_samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getSamplesForSession(sessionId: Long): List<TelemetrySampleEntity>
}

@Database(
    entities = [
        HudPageEntity::class,
        HudWidgetEntity::class,
        CustomPidEntity::class,
        TripSessionEntity::class,
        TelemetrySampleEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ApexDatabase : RoomDatabase() {
    abstract fun hudDao(): HudDao
    abstract fun customPidDao(): CustomPidDao
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var instance: ApexDatabase? = null

        fun getInstance(context: Context): ApexDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): ApexDatabase {
            lateinit var db: ApexDatabase
            db = Room.databaseBuilder(context, ApexDatabase::class.java, "apex_obd.db")
                .addCallback(object : Callback() {
                    override fun onCreate(sqliteDb: SupportSQLiteDatabase) {
                        super.onCreate(sqliteDb)
                        CoroutineScope(Dispatchers.IO).launch {
                            seedDefaults(db.hudDao())
                        }
                    }
                })
                .build()
            return db
        }

        suspend fun seedDefaults(hudDao: HudDao) {
            if (hudDao.getPagesOnce().isNotEmpty()) return

            // Page 1: Daily Tracking (All 8 user sensors + Google Mini-Map)
            val p1 = hudDao.insertPage(HudPageEntity(title = "1. Tracking", sortOrder = 0))
            hudDao.insertWidgets(
                listOf(
                    HudWidgetEntity(pageId = p1, sensorKey = "rpm", displayType = WidgetDisplayType.ANALOG_DIAL.name, spanCols = 2, spanRows = 2, sortOrder = 0, warnThreshold = 5800.0, critThreshold = 6500.0),
                    HudWidgetEntity(pageId = p1, sensorKey = "gps_speed", displayType = WidgetDisplayType.SEMI_DIGITAL.name, spanCols = 2, spanRows = 2, sortOrder = 1),
                    HudWidgetEntity(pageId = p1, sensorKey = "minimap", displayType = WidgetDisplayType.MINI_MAP.name, spanCols = 2, spanRows = 2, sortOrder = 2),
                    HudWidgetEntity(pageId = p1, sensorKey = "voltage", displayType = WidgetDisplayType.DIGITAL.name, spanCols = 1, spanRows = 1, sortOrder = 3, warnThreshold = 12.2, critThreshold = 11.6),
                    HudWidgetEntity(pageId = p1, sensorKey = "coolant", displayType = WidgetDisplayType.DIGITAL.name, spanCols = 1, spanRows = 1, sortOrder = 4, warnThreshold = 102.0, critThreshold = 110.0),
                    HudWidgetEntity(pageId = p1, sensorKey = "iat", displayType = WidgetDisplayType.DIGITAL.name, spanCols = 1, spanRows = 1, sortOrder = 5),
                    HudWidgetEntity(pageId = p1, sensorKey = "timing_advance", displayType = WidgetDisplayType.DIGITAL.name, spanCols = 1, spanRows = 1, sortOrder = 6),
                    HudWidgetEntity(pageId = p1, sensorKey = "throttle", displayType = WidgetDisplayType.BAR.name, spanCols = 1, spanRows = 1, sortOrder = 7),
                    HudWidgetEntity(pageId = p1, sensorKey = "maf", displayType = WidgetDisplayType.CHART.name, spanCols = 1, spanRows = 1, sortOrder = 8)
                )
            )

            // Page 2: DTC Checker
            val p2 = hudDao.insertPage(HudPageEntity(title = "2. DTC Checker", sortOrder = 1))
            hudDao.insertWidgets(
                listOf(
                    HudWidgetEntity(pageId = p2, sensorKey = "dtc_checker", displayType = WidgetDisplayType.DTC_CHECKER.name, spanCols = 4, spanRows = 2, sortOrder = 0),
                    HudWidgetEntity(pageId = p2, sensorKey = "stft_b1", displayType = WidgetDisplayType.CHART.name, spanCols = 2, spanRows = 1, sortOrder = 1),
                    HudWidgetEntity(pageId = p2, sensorKey = "ltft_b1", displayType = WidgetDisplayType.CHART.name, spanCols = 2, spanRows = 1, sortOrder = 2),
                    HudWidgetEntity(pageId = p2, sensorKey = "coolant", displayType = WidgetDisplayType.BAR.name, spanCols = 1, spanRows = 1, sortOrder = 3),
                    HudWidgetEntity(pageId = p2, sensorKey = "voltage", displayType = WidgetDisplayType.DIGITAL.name, spanCols = 1, spanRows = 1, sortOrder = 4)
                )
            )

            // Page 3: Performance (HP / Torque / 0-100 / Logger)
            val p3 = hudDao.insertPage(HudPageEntity(title = "3. Performance", sortOrder = 2))
            hudDao.insertWidgets(
                listOf(
                    HudWidgetEntity(pageId = p3, sensorKey = "hp", displayType = WidgetDisplayType.ANALOG_DIAL.name, spanCols = 2, spanRows = 2, sortOrder = 0),
                    HudWidgetEntity(pageId = p3, sensorKey = "torque", displayType = WidgetDisplayType.ANALOG_DIAL.name, spanCols = 2, spanRows = 2, sortOrder = 1),
                    HudWidgetEntity(pageId = p3, sensorKey = "timer_0100", displayType = WidgetDisplayType.PERF_TIMER.name, spanCols = 2, spanRows = 1, sortOrder = 2),
                    HudWidgetEntity(pageId = p3, sensorKey = "data_logger", displayType = WidgetDisplayType.DATA_LOGGER.name, spanCols = 2, spanRows = 1, sortOrder = 3),
                    HudWidgetEntity(pageId = p3, sensorKey = "rpm", displayType = WidgetDisplayType.CHART.name, spanCols = 2, spanRows = 1, sortOrder = 4)
                )
            )
        }
    }
}
