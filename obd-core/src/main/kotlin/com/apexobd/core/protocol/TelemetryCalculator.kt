package com.apexobd.core.protocol

import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.VehicleProfile

data class FuelMetricsSnapshot(
    val fuelFlowLitersPerHour: Double,
    val instantKpl: Double,
    val tripAvgKpl: Double,
    val roadOnlyAvgKpl: Double,
    val totalFuelUsedLiters: Double,
    val roadFuelUsedLiters: Double,
    val idleFuelUsedLiters: Double,
    val totalDistanceKm: Double,
    val roadDistanceKm: Double,
    val fuelCalculationPaused: Boolean
)

/**
 * Computes real-time and averaged fuel consumption from MAF (or Speed-Density MAP+RPM fallback).
 * Separates [roadOnlyAvgKpl] from [tripAvgKpl] by excluding stationary idling and honoring
 * an explicit [paused] toggle so resting in the car with the engine running never skews road average.
 */
class FuelEconomyTracker {
    private var totalDistanceKm = 0.0
    private var roadDistanceKm = 0.0
    private var totalFuelLiters = 0.0
    private var roadFuelLiters = 0.0
    private var idleFuelLiters = 0.0
    private var lastUpdateMs: Long = 0L

    @Volatile
    var paused: Boolean = false

    fun reset() {
        totalDistanceKm = 0.0
        roadDistanceKm = 0.0
        totalFuelLiters = 0.0
        roadFuelLiters = 0.0
        idleFuelLiters = 0.0
        lastUpdateMs = 0L
    }

    fun update(
        profile: VehicleProfile,
        speedKmh: Double,
        rpm: Double?,
        mafGramsPerSec: Double?,
        mapKpa: Double?,
        iatCelsius: Double?,
        lambda: Double?,
        throttlePct: Double?,
        nowMs: Long = System.currentTimeMillis()
    ): FuelMetricsSnapshot {
        val effectiveMaf = mafGramsPerSec ?: estimateMafSpeedDensity(
            displacementLiters = profile.displacementLiters,
            vePct = profile.volumetricEfficiencyPct,
            rpm = rpm,
            mapKpa = mapKpa,
            iatCelsius = iatCelsius
        ) ?: 0.0

        val isDfcoCoasting = speedKmh > 15.0 &&
            (throttlePct ?: 10.0) <= 1.5 &&
            (rpm ?: 0.0) > 1200.0

        val fuelFlowLph = if (isDfcoCoasting || effectiveMaf <= 0.05) {
            0.0
        } else {
            val afr = profile.fuelType.stoichiometricAfr * (lambda ?: 1.0).coerceIn(0.65, 1.5)
            val fuelGramsPerSec = (effectiveMaf / afr) * (profile.mafTrimPct / 100.0)
            (fuelGramsPerSec / profile.fuelType.densityGramsPerLiter) * 3600.0
        }

        val instantKpl = when {
            speedKmh <= profile.idleSpeedThresholdKmh -> 0.0
            fuelFlowLph <= 0.02 -> 99.9
            else -> (speedKmh / fuelFlowLph).coerceIn(0.0, 99.9)
        }

        if (lastUpdateMs > 0L && nowMs > lastUpdateMs) {
            val dtHours = ((nowMs - lastUpdateMs) / 3_600_000.0).coerceAtMost(0.01)
            val dDistKm = speedKmh * dtHours
            val dFuelL = fuelFlowLph * dtHours

            if (!paused) {
                totalDistanceKm += dDistKm
                totalFuelLiters += dFuelL
                if (speedKmh > profile.idleSpeedThresholdKmh) {
                    roadDistanceKm += dDistKm
                    roadFuelLiters += dFuelL
                } else {
                    idleFuelLiters += dFuelL
                }
            }
        }
        lastUpdateMs = nowMs

        val tripAvg = if (totalFuelLiters > 0.0005) (totalDistanceKm / totalFuelLiters).coerceIn(0.0, 99.9) else 0.0
        val roadAvg = if (roadFuelLiters > 0.0005) (roadDistanceKm / roadFuelLiters).coerceIn(0.0, 99.9) else 0.0

        return FuelMetricsSnapshot(
            fuelFlowLitersPerHour = fuelFlowLph,
            instantKpl = instantKpl,
            tripAvgKpl = tripAvg,
            roadOnlyAvgKpl = roadAvg,
            totalFuelUsedLiters = totalFuelLiters,
            roadFuelUsedLiters = roadFuelLiters,
            idleFuelUsedLiters = idleFuelLiters,
            totalDistanceKm = totalDistanceKm,
            roadDistanceKm = roadDistanceKm,
            fuelCalculationPaused = paused
        )
    }

    companion object {
        private const val SPECIFIC_GAS_CONSTANT_AIR = 0.287058 // kJ/(kg*K)
        private const val MOLAR_MASS_AIR = 28.97

        /**
         * Speed-density fallback for cars without a MAF sensor (uses MAP, RPM, IAT, and displacement).
         */
        fun estimateMafSpeedDensity(
            displacementLiters: Double,
            vePct: Double,
            rpm: Double?,
            mapKpa: Double?,
            iatCelsius: Double?
        ): Double? {
            if (rpm == null || mapKpa == null || rpm < 100.0) return null
            val tempK = (iatCelsius ?: 35.0) + 273.15
            val imap = (rpm * mapKpa) / tempK / 2.0
            // Ideal gas: grams/sec = (IMAP / 60) * (VE/100) * V_d * (M_air / R)
            return (imap / 60.0) * (vePct / 100.0) * displacementLiters * (MOLAR_MASS_AIR / 8.31447)
        }

        /**
         * Computes live Volumetric Efficiency (%) when both MAF and MAP are supported.
         */
        fun computeVolumetricEfficiencyPct(
            displacementLiters: Double,
            rpm: Double?,
            mafGramsPerSec: Double?,
            mapKpa: Double?,
            iatCelsius: Double?
        ): Double? {
            if (rpm == null || mafGramsPerSec == null || mapKpa == null || rpm < 200.0 || mapKpa <= 5.0 || displacementLiters <= 0.1) {
                return null
            }
            val tempK = (iatCelsius ?: 35.0) + 273.15
            val airDensityGramsPerLiter = (mapKpa / (SPECIFIC_GAS_CONSTANT_AIR * tempK))
            val theoreticalMaxGramsPerSec = (displacementLiters * (rpm / 120.0)) * airDensityGramsPerLiter
            if (theoreticalMaxGramsPerSec <= 0.01) return null
            return ((mafGramsPerSec / theoreticalMaxGramsPerSec) * 100.0).coerceIn(0.0, 150.0)
        }

        /**
         * Driving efficiency score (0-100) combining fuel economy, throttle smoothness, and engine load.
         */
        fun computeDrivingEfficiencyScore(
            instantKpl: Double,
            throttlePct: Double?,
            engineLoadPct: Double?,
            speedKmh: Double
        ): Double {
            if (speedKmh <= 2.0) return 0.0
            val kplFactor = ((instantKpl / 18.0) * 60.0).coerceIn(0.0, 60.0)
            val tpsPenalty = ((throttlePct ?: 20.0) / 100.0) * 20.0
            val loadPenalty = ((engineLoadPct ?: 30.0) / 100.0) * 20.0
            return (40.0 + kplFactor - tpsPenalty - loadPenalty).coerceIn(0.0, 100.0)
        }
    }
}

data class DerivedSensorSpec(
    val key: String,
    val name: String,
    val shortName: String,
    val sourceLabel: String,
    val defaultUnit: String,
    val quantity: SensorQuantity,
    val minValue: Double,
    val maxValue: Double,
    val requiredAnyOfPidGroups: List<Set<String>> = emptyList()
)

object DerivedSensorCatalog {
    val all: List<DerivedSensorSpec> = listOf(
        DerivedSensorSpec(
            key = "gps_speed",
            name = "GPS Speed",
            shortName = "GPS Spd",
            sourceLabel = "GPS",
            defaultUnit = "km/h",
            quantity = SensorQuantity.SPEED,
            minValue = 0.0,
            maxValue = 240.0
        ),
        DerivedSensorSpec(
            key = "accel_g",
            name = "Acceleration G-Force",
            shortName = "Accel",
            sourceLabel = "IMU",
            defaultUnit = "g",
            quantity = SensorQuantity.RAW,
            minValue = -1.5,
            maxValue = 1.5
        ),
        DerivedSensorSpec(
            key = "boost_kpa",
            name = "Boost / Vacuum",
            shortName = "Boost",
            sourceLabel = "CALC (010B)",
            defaultUnit = "bar",
            quantity = SensorQuantity.BOOST_KPA,
            minValue = -100.0,
            maxValue = 200.0,
            requiredAnyOfPidGroups = listOf(setOf("0B"))
        ),
        DerivedSensorSpec(
            key = "kpl_instant",
            name = "Fuel Economy (Instant)",
            shortName = "KPL",
            sourceLabel = "CALC (MAF/MAP)",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10", "0D"), setOf("0B", "0C"))
        ),
        DerivedSensorSpec(
            key = "kpl_road_avg",
            name = "Fuel Economy (Road-Only Avg)",
            shortName = "KPL Road",
            sourceLabel = "CALC (No Idle)",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"))
        ),
        DerivedSensorSpec(
            key = "kpl_trip_avg",
            name = "Fuel Economy (Trip Avg)",
            shortName = "KPL Avg",
            sourceLabel = "CALC (Trip)",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"))
        ),
        DerivedSensorSpec(
            key = "fuel_flow_lph",
            name = "Fuel Flow Rate",
            shortName = "Fuel/h",
            sourceLabel = "CALC",
            defaultUnit = "L/h",
            quantity = SensorQuantity.RAW,
            minValue = 0.0,
            maxValue = 50.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"))
        ),
        DerivedSensorSpec(
            key = "fuel_used_l",
            name = "Trip Fuel Used",
            shortName = "Fuel Used",
            sourceLabel = "CALC",
            defaultUnit = "L",
            quantity = SensorQuantity.RAW,
            minValue = 0.0,
            maxValue = 80.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"))
        ),
        DerivedSensorSpec(
            key = "ve_pct",
            name = "Volumetric Efficiency",
            shortName = "VE %",
            sourceLabel = "CALC (MAF+MAP)",
            defaultUnit = "%",
            quantity = SensorQuantity.RAW,
            minValue = 0.0,
            maxValue = 130.0,
            requiredAnyOfPidGroups = listOf(setOf("10", "0B", "0C"))
        ),
        DerivedSensorSpec(
            key = "hp",
            name = "Estimated Horsepower",
            shortName = "Power",
            sourceLabel = "CALC",
            defaultUnit = "HP",
            quantity = SensorQuantity.POWER_HP,
            minValue = 0.0,
            maxValue = 350.0,
            requiredAnyOfPidGroups = listOf(setOf("0C", "10"), setOf("0C", "0B"), setOf("62", "63"))
        ),
        DerivedSensorSpec(
            key = "torque",
            name = "Estimated Torque",
            shortName = "Torque",
            sourceLabel = "CALC",
            defaultUnit = "Nm",
            quantity = SensorQuantity.TORQUE_NM,
            minValue = 0.0,
            maxValue = 500.0,
            requiredAnyOfPidGroups = listOf(setOf("0C", "10"), setOf("0C", "0B"), setOf("62", "63"))
        ),
        DerivedSensorSpec(
            key = "efficiency_score",
            name = "Driving Efficiency Score",
            shortName = "Eff Score",
            sourceLabel = "CALC",
            defaultUnit = "/100",
            quantity = SensorQuantity.RAW,
            minValue = 0.0,
            maxValue = 100.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"))
        )
    )

    fun isAvailable(spec: DerivedSensorSpec, supportedMode01Pids: Set<String>): Boolean {
        if (spec.requiredAnyOfPidGroups.isEmpty() || supportedMode01Pids.isEmpty()) return true
        return spec.requiredAnyOfPidGroups.any { group -> group.all { it in supportedMode01Pids } }
    }
}
