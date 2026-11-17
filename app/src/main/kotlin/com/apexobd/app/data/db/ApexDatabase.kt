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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.VehicleProfile
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

@Entity(tableName = "vehicle_profiles")
data class VehicleProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "My Vehicle",
    val displacementLiters: Double = 1.3,
    val weightKg: Double = 1175.0,
    val fuelType: String = FuelType.GASOLINE.name,
    val tankCapacityLiters: Double = 45.0,
    val estimatedRemainingFuelLiters: Double = 45.0,
    val volumetricEfficiencyPct: Double = 85.0,
    val mafTrimPct: Double = 100.0,
    val drivetrainLossPct: Double = 15.0,
    val dragCoefficientAreaCdA: Double = 0.72,
    val redlineRpm: Int = 7000,
    val allTimeOdometerKm: Double = 0.0,
    val allTimeRoadDistanceKm: Double = 0.0,
    val allTimeRoadFuelLiters: Double = 0.0,
    val best0100Sec: Double? = null,
    val isActive: Boolean = false
) {
    fun toCoreModel(): VehicleProfile = VehicleProfile(
        id = id,
        name = name,
        displacementLiters = displacementLiters,
        weightKg = weightKg,
        fuelType = runCatching { FuelType.valueOf(fuelType) }.getOrDefault(FuelType.GASOLINE),
        tankCapacityLiters = tankCapacityLiters,
        estimatedRemainingFuelLiters = estimatedRemainingFuelLiters,
        volumetricEfficiencyPct = volumetricEfficiencyPct,
        mafTrimPct = mafTrimPct,
        drivetrainLossPct = drivetrainLossPct,
        dragCoefficientAreaCdA = dragCoefficientAreaCdA,
        redlineRpm = redlineRpm,
        allTimeOdometerKm = allTimeOdometerKm,
        allTimeRoadDistanceKm = allTimeRoadDistanceKm,
        allTimeRoadFuelLiters = allTimeRoadFuelLiters,
        best0To100Sec = best0100Sec
    )

    fun allTimeAvgKpl(): Double =
        if (allTimeRoadFuelLiters > 0.005) (allTimeRoadDistanceKm / allTimeRoadFuelLiters).coerceIn(0.0, 99.9) else 0.0
}

@Entity(
    tableName = "refuel_logs",
    foreignKeys = [
        ForeignKey(
            entity = VehicleProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("vehicleId")]
)
data class RefuelLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val timestampMs: Long,
    val odometerKm: Double,
    val volumeLiters: Double,
    val pricePaid: Double,
    val currencyCode: String = "IDR",
    val ronValue: Int? = null,
    val fuelName: String? = null,
    val cycleDistanceKm: Double? = null,
    val cycleAvgKpl: Double? = null
)

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
    val displayType: String,
    val spanCols: Int,
    val spanRows: Int,
    val sortOrder: Int,
    val freeformXFraction: Float = 0.04f,
    val freeformYFraction: Float = 0.06f,
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
    val sampleCount: Int = 0,
    val vehicleId: Long = 1L,
    val fuelUsedLiters: Double = 0.0,
    val avgKpl: Double = 0.0
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
interface VehicleDao {
    @Query("SELECT * FROM vehicle_profiles ORDER BY isActive DESC, id ASC")
    fun observeVehicles(): Flow<List<VehicleProfileEntity>>

    @Query("SELECT * FROM vehicle_profiles ORDER BY isActive DESC, id ASC")
    suspend fun getVehiclesOnce(): List<VehicleProfileEntity>

    @Query("SELECT * FROM vehicle_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VehicleProfileEntity?

    @Insert
    suspend fun insertVehicle(vehicle: VehicleProfileEntity): Long

    @Update
    suspend fun updateVehicle(vehicle: VehicleProfileEntity)

    @Query("UPDATE vehicle_profiles SET isActive = (id = :activeId)")
    suspend fun setActiveVehicle(activeId: Long)

    @Query("DELETE FROM vehicle_profiles WHERE id = :id")
    suspend fun deleteVehicle(id: Long)

    @Query("SELECT * FROM refuel_logs WHERE vehicleId = :vehicleId ORDER BY timestampMs DESC")
    fun observeRefuelsForVehicle(vehicleId: Long): Flow<List<RefuelLogEntity>>

    @Query("SELECT * FROM refuel_logs WHERE vehicleId = :vehicleId ORDER BY odometerKm DESC, timestampMs DESC LIMIT 1")
    suspend fun getLatestRefuelForVehicle(vehicleId: Long): RefuelLogEntity?

    @Insert
    suspend fun insertRefuel(entry: RefuelLogEntity): Long

    @Query("DELETE FROM refuel_logs WHERE id = :id")
    suspend fun deleteRefuel(id: Long)
}

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

    @Query("DELETE FROM hud_widgets WHERE pageId = :pageId")
    suspend fun deleteWidgetsForPage(pageId: Long)
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

    @Insert
    suspend fun insertSamples(samples: List<TelemetrySampleEntity>)

    @Query("SELECT * FROM telemetry_samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getSamplesForSession(sessionId: Long): List<TelemetrySampleEntity>
}

@Database(
    entities = [
        VehicleProfileEntity::class,
        RefuelLogEntity::class,
        HudPageEntity::class,
        HudWidgetEntity::class,
        CustomPidEntity::class,
        TripSessionEntity::class,
        TelemetrySampleEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ApexDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun hudDao(): HudDao
    abstract fun customPidDao(): CustomPidDao
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var instance: ApexDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS vehicle_profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        displacementLiters REAL NOT NULL,
                        weightKg REAL NOT NULL,
                        fuelType TEXT NOT NULL,
                        tankCapacityLiters REAL NOT NULL,
                        estimatedRemainingFuelLiters REAL NOT NULL,
                        volumetricEfficiencyPct REAL NOT NULL,
                        mafTrimPct REAL NOT NULL,
                        drivetrainLossPct REAL NOT NULL,
                        dragCoefficientAreaCdA REAL NOT NULL,
                        redlineRpm INTEGER NOT NULL,
                        allTimeOdometerKm REAL NOT NULL,
                        allTimeRoadDistanceKm REAL NOT NULL,
                        allTimeRoadFuelLiters REAL NOT NULL,
                        best0100Sec REAL,
                        isActive INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS refuel_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        vehicleId INTEGER NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        odometerKm REAL NOT NULL,
                        volumeLiters REAL NOT NULL,
                        pricePaid REAL NOT NULL,
                        currencyCode TEXT NOT NULL,
                        ronValue INTEGER,
                        fuelName TEXT,
                        cycleDistanceKm REAL,
                        cycleAvgKpl REAL,
                        FOREIGN KEY(vehicleId) REFERENCES vehicle_profiles(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_refuel_logs_vehicleId ON refuel_logs(vehicleId)")
                db.execSQL("ALTER TABLE hud_widgets ADD COLUMN freeformXFraction REAL NOT NULL DEFAULT 0.04")
                db.execSQL("ALTER TABLE hud_widgets ADD COLUMN freeformYFraction REAL NOT NULL DEFAULT 0.06")
                db.execSQL("ALTER TABLE trip_sessions ADD COLUMN vehicleId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE trip_sessions ADD COLUMN fuelUsedLiters REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE trip_sessions ADD COLUMN avgKpl REAL NOT NULL DEFAULT 0.0")
                db.execSQL(
                    """
                    INSERT INTO vehicle_profiles (
                        name, displacementLiters, weightKg, fuelType, tankCapacityLiters,
                        estimatedRemainingFuelLiters, volumetricEfficiencyPct, mafTrimPct,
                        drivetrainLossPct, dragCoefficientAreaCdA, redlineRpm,
                        allTimeOdometerKm, allTimeRoadDistanceKm, allTimeRoadFuelLiters,
                        best0100Sec, isActive
                    ) VALUES ('Avanza 1.3L', 1.3, 1175.0, 'GASOLINE', 45.0, 45.0, 85.0, 100.0, 15.0, 0.72, 7000, 0.0, 0.0, 0.0, NULL, 1)
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): ApexDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): ApexDatabase {
            lateinit var db: ApexDatabase
            db = Room.databaseBuilder(context, ApexDatabase::class.java, "apex_obd.db")
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(sqliteDb: SupportSQLiteDatabase) {
                        super.onCreate(sqliteDb)
                        CoroutineScope(Dispatchers.IO).launch {
                            seedDefaults(db.hudDao(), db.vehicleDao())
                        }
                    }
                })
                .build()
            return db
        }

        suspend fun seedDefaults(hudDao: HudDao, vehicleDao: VehicleDao) {
            if (vehicleDao.getVehiclesOnce().isEmpty()) {
                vehicleDao.insertVehicle(
                    VehicleProfileEntity(
                        name = "Avanza 1.3L",
                        displacementLiters = 1.3,
                        weightKg = 1175.0,
                        fuelType = FuelType.GASOLINE.name,
                        tankCapacityLiters = 45.0,
                        estimatedRemainingFuelLiters = 45.0,
                        isActive = true
                    )
                )
            }
            if (hudDao.getPagesOnce().isEmpty()) {
                // Approved spec: Page 2 HUD Canvas opens with ONE blank pure #000000 canvas (zero pre-added widgets)
                hudDao.insertPage(HudPageEntity(title = "Page 1", sortOrder = 0))
            }
        }
    }
}
