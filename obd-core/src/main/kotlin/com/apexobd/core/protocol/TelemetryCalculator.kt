package com.apexobd.core.protocol

import com.apexobd.core.model.FuelType
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.VehicleProfile
import kotlin.math.abs

data class FuelMetricsSnapshot(
    val fuelFlowLitersPerHour: Double,
    val instantKpl: Double,
    val tripAvgKpl: Double,
    val roadOnlyAvgKpl: Double,
    val allTimeRoadAvgKpl: Double,
    val totalFuelUsedLiters: Double,
    val roadFuelUsedLiters: Double,
    val idleFuelUsedLiters: Double,
    val totalDistanceKm: Double,
    val roadDistanceKm: Double,
    val allTimeRoadDistanceKm: Double,
    val allTimeRoadFuelLiters: Double,
    val allTimeOdometerKm: Double,
    val estimatedRemainingFuelLiters: Double,
    val estimatedRangeKm: Double?,
    val dteEconomyKpl: Double?,
    val dfcoActive: Boolean,
    val fuelCalculationPaused: Boolean
)

/**
 * Multi-sensor fuel economy, Speed-Density, tank depletion, and volumetric efficiency engine.
 * Combines 015E direct ECU fuel rate, MAF (0110) or Load-compensated Speed-Density (010B/010C/010F/0133),
 * closed-loop STFT/LTFT trims (0106-0109), wideband lambda (0144), coolant cold enrichment (0105),
 * WOT enrichment, Gasoline/Diesel/Electric physics, and DBW-accurate DFCO (0145 + MAP/Baro).
 */
class FuelEconomyTracker {
    private var totalDistanceKm = 0.0
    private var roadDistanceKm = 0.0
    private var totalFuelLiters = 0.0
    private var roadFuelLiters = 0.0
    private var idleFuelLiters = 0.0

    private var allTimeRoadDistKm = 0.0
    private var allTimeRoadFuelL = 0.0
    private var allTimeOdometerKm = 0.0
    private var estimatedFuelLiters = 0.0
    private var recentDteKpl: Double? = null
    private var displayedRangeKm: Double? = null
    private var recentSliceDistKm = 0.0
    private var recentSliceFuelL = 0.0
    private var seededProfileId: Long? = null
    private var lastUpdateMs: Long = 0L
    private var lastThrottlePct: Double? = null
    private var lastAccelG: Double? = null

    @Volatile
    var paused: Boolean = false

    fun seedFromProfile(profile: VehicleProfile, force: Boolean = false) {
        if (force || seededProfileId != profile.id) {
            seededProfileId = profile.id
            allTimeRoadDistKm = profile.allTimeRoadDistanceKm
            allTimeRoadFuelL = profile.allTimeRoadFuelLiters
            allTimeOdometerKm = profile.allTimeOdometerKm
            estimatedFuelLiters = profile.estimatedRemainingFuelLiters.coerceIn(0.0, profile.tankCapacityLiters)
            recentDteKpl = profile.recentDteKpl?.takeIf { it > 0.5 }
            displayedRangeKm = null
            recentSliceDistKm = 0.0
            recentSliceFuelL = 0.0
        }
    }

    fun applyRefuelAdded(addedVolumeLiters: Double, tankCapacityLiters: Double) {
        if (addedVolumeLiters <= 0.0) return
        estimatedFuelLiters = (estimatedFuelLiters + addedVolumeLiters).coerceIn(0.0, tankCapacityLiters)
        displayedRangeKm = null
    }

    fun syncManualProfileCounters(profile: VehicleProfile) {
        allTimeOdometerKm = profile.allTimeOdometerKm
        estimatedFuelLiters = profile.estimatedRemainingFuelLiters.coerceIn(0.0, profile.tankCapacityLiters)
        displayedRangeKm = null
    }

    fun resetSession() {
        totalDistanceKm = 0.0
        roadDistanceKm = 0.0
        totalFuelLiters = 0.0
        roadFuelLiters = 0.0
        idleFuelLiters = 0.0
        recentSliceDistKm = 0.0
        recentSliceFuelL = 0.0
        displayedRangeKm = null
        lastUpdateMs = 0L
    }

    fun reset() {
        resetSession()
        allTimeRoadDistKm = 0.0
        allTimeRoadFuelL = 0.0
        recentDteKpl = null
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
        relThrottlePct: Double? = null,
        baroKpa: Double? = null,
        coolantCelsius: Double? = null,
        engineLoadPct: Double? = null,
        stftPct: Double? = null,
        ltftPct: Double? = null,
        ecuFuelRateLph: Double? = null,
        ecuFuelLevelPct: Double? = null,
        wheelPowerKw: Double? = null,
        nowMs: Long = System.currentTimeMillis()
    ): FuelMetricsSnapshot {
        seedFromProfile(profile)

        val effectiveBaro = (baroKpa ?: 101.325).coerceIn(60.0, 110.0)
        val effectiveMaf = mafGramsPerSec ?: estimateMafSpeedDensity(
            displacementLiters = profile.displacementLiters,
            vePct = profile.volumetricEfficiencyPct,
            rpm = rpm,
            mapKpa = mapKpa,
            iatCelsius = iatCelsius,
            baroKpa = effectiveBaro,
            engineLoadPct = engineLoadPct
        ) ?: 0.0

        val isClosedThrottle = when {
            relThrottlePct != null -> relThrottlePct <= 1.5
            throttlePct != null -> throttlePct <= 12.0 && (mapKpa == null || mapKpa <= effectiveBaro * 0.30)
            else -> false
        }
        val highVacuum = mapKpa == null || mapKpa <= effectiveBaro * 0.36
        val isDfcoCoasting = profile.fuelType != FuelType.ELECTRIC &&
            speedKmh > 12.0 &&
            (rpm ?: 0.0) > 1150.0 &&
            isClosedThrottle &&
            highVacuum

        val fuelFlowLph = computeFuelFlowLitersPerHour(
            profile = profile,
            effectiveMaf = effectiveMaf,
            isDfcoCoasting = isDfcoCoasting,
            lambda = lambda,
            coolantCelsius = coolantCelsius,
            engineLoadPct = engineLoadPct,
            relThrottlePct = relThrottlePct,
            throttlePct = throttlePct,
            stftPct = stftPct,
            ltftPct = ltftPct,
            ecuFuelRateLph = ecuFuelRateLph,
            wheelPowerKw = wheelPowerKw
        )

        val instantKpl = when {
            speedKmh <= profile.idleSpeedThresholdKmh -> 0.0
            fuelFlowLph <= 0.02 -> 99.9
            else -> (speedKmh / fuelFlowLph).coerceIn(0.0, 99.9)
        }

        if (lastUpdateMs > 0L && nowMs > lastUpdateMs) {
            val dtHours = ((nowMs - lastUpdateMs) / 3_600_000.0).coerceAtMost(0.01)
            val dDistKm = speedKmh * dtHours
            val dFuelL = fuelFlowLph * dtHours

            if (dDistKm > 0.0) {
                allTimeOdometerKm += dDistKm
            }
            if ((rpm ?: 0.0) > 200.0 || profile.fuelType == FuelType.ELECTRIC) {
                estimatedFuelLiters = (estimatedFuelLiters - dFuelL).coerceIn(0.0, profile.tankCapacityLiters)
            }

            if (!paused) {
                totalDistanceKm += dDistKm
                totalFuelLiters += dFuelL
                if (speedKmh > profile.idleSpeedThresholdKmh) {
                    roadDistanceKm += dDistKm
                    roadFuelLiters += dFuelL
                    allTimeRoadDistKm += dDistKm
                    allTimeRoadFuelL += dFuelL

                    // Moving-only Dual-Window EWMA update (~4 km distance constant)
                    if (dDistKm > 0.0) {
                        recentSliceDistKm += dDistKm
                        recentSliceFuelL += dFuelL
                        if (recentSliceDistKm >= 0.008 && recentSliceFuelL > 0.00005) {
                            val sliceKpl = (recentSliceDistKm / recentSliceFuelL).coerceIn(1.5, 45.0)
                            val alpha = (recentSliceDistKm / 4.0).coerceIn(0.01, 0.30)
                            recentDteKpl = recentDteKpl?.let { prev -> alpha * sliceKpl + (1.0 - alpha) * prev } ?: sliceKpl
                            recentSliceDistKm = 0.0
                            recentSliceFuelL = 0.0
                        }
                    }
                } else {
                    idleFuelLiters += dFuelL
                }
            }
        }
        if (ecuFuelLevelPct != null && ecuFuelLevelPct in 0.0..100.0) {
            estimatedFuelLiters = (ecuFuelLevelPct / 100.0) * profile.tankCapacityLiters
            displayedRangeKm = null
        }
        lastUpdateMs = nowMs

        val tripAvg = if (totalFuelLiters > 0.0005) (totalDistanceKm / totalFuelLiters).coerceIn(0.0, 99.9) else 0.0
        val roadAvg = if (roadFuelLiters > 0.0005) (roadDistanceKm / roadFuelLiters).coerceIn(0.0, 99.9) else 0.0
        val allTimeRoadAvg = if (allTimeRoadFuelL > 0.0005) (allTimeRoadDistKm / allTimeRoadFuelL).coerceIn(0.0, 99.9) else roadAvg

        val slowBaseline = if (allTimeRoadFuelL > 0.005) {
            (allTimeRoadDistKm / allTimeRoadFuelL).coerceIn(0.5, 99.9)
        } else if (roadFuelLiters > 0.002) {
            roadAvg.takeIf { it > 0.5 }
        } else null
        val fastWindow = recentDteKpl ?: roadAvg.takeIf { roadFuelLiters > 0.002 && it > 0.5 }

        val dteKpl: Double? = when {
            fastWindow != null && slowBaseline != null -> {
                val tankFrac = (estimatedFuelLiters / profile.tankCapacityLiters.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val wRecent = if (tankFrac <= 0.25) 0.72 else 0.55
                (wRecent * fastWindow + (1.0 - wRecent) * slowBaseline).coerceIn(0.5, 99.9)
            }
            fastWindow != null -> fastWindow
            slowBaseline != null -> slowBaseline
            else -> null
        }

        val estRangeRaw = dteKpl?.let { (estimatedFuelLiters * it).coerceAtLeast(0.0) }
        val smoothedRange: Double? = if (estRangeRaw != null) {
            val prev = displayedRangeKm
            if (prev == null) {
                estRangeRaw
            } else {
                val delta = (estRangeRaw - prev).coerceIn(-2.0, 1.5)
                (prev + delta).coerceAtLeast(0.0)
            }
        } else {
            null
        }
        displayedRangeKm = smoothedRange

        return FuelMetricsSnapshot(
            fuelFlowLitersPerHour = fuelFlowLph,
            instantKpl = instantKpl,
            tripAvgKpl = tripAvg,
            roadOnlyAvgKpl = roadAvg,
            allTimeRoadAvgKpl = allTimeRoadAvg,
            totalFuelUsedLiters = totalFuelLiters,
            roadFuelUsedLiters = roadFuelLiters,
            idleFuelUsedLiters = idleFuelLiters,
            totalDistanceKm = totalDistanceKm,
            roadDistanceKm = roadDistanceKm,
            allTimeRoadDistanceKm = allTimeRoadDistKm,
            allTimeRoadFuelLiters = allTimeRoadFuelL,
            allTimeOdometerKm = allTimeOdometerKm,
            estimatedRemainingFuelLiters = estimatedFuelLiters,
            estimatedRangeKm = smoothedRange,
            dteEconomyKpl = dteKpl,
            dfcoActive = isDfcoCoasting,
            fuelCalculationPaused = paused
        )
    }

    fun computeEfficiencyDeltaScore(
        instantKpl: Double,
        baselineAvgKpl: Double,
        throttlePct: Double?,
        engineLoadPct: Double?,
        speedKmh: Double,
        accelG: Double? = null,
        dfcoActive: Boolean = false
    ): Double {
        if (speedKmh <= 2.0) return 0.0
        if (dfcoActive) return 98.0

        val referenceKpl = baselineAvgKpl.takeIf { it > 2.0 } ?: 13.0
        val kplScore = ((instantKpl / (referenceKpl * 1.3)) * 55.0).coerceIn(0.0, 58.0)

        val load = engineLoadPct ?: 35.0
        // Peak BSFC sits around 45-75% load; steep penalty at WOT enrichment > 82% or lugging/pumping extreme
        val bsfcBonus = when {
            load in 35.0..75.0 -> 20.0
            load > 82.0 -> 4.0
            else -> 12.0
        }

        val tpsNow = throttlePct ?: 20.0
        val tpsJerk = lastThrottlePct?.let { abs(tpsNow - it) } ?: 0.0
        lastThrottlePct = tpsNow

        val gJerk = if (accelG != null && lastAccelG != null) abs(accelG - lastAccelG!!) else 0.0
        if (accelG != null) lastAccelG = accelG

        val smoothness = (22.0 - (tpsJerk * 0.7) - (gJerk * 35.0)).coerceIn(0.0, 22.0)
        return (kplScore + bsfcBonus + smoothness).coerceIn(0.0, 100.0)
    }

    private fun computeFuelFlowLitersPerHour(
        profile: VehicleProfile,
        effectiveMaf: Double,
        isDfcoCoasting: Boolean,
        lambda: Double?,
        coolantCelsius: Double?,
        engineLoadPct: Double?,
        relThrottlePct: Double?,
        throttlePct: Double?,
        stftPct: Double?,
        ltftPct: Double?,
        ecuFuelRateLph: Double?,
        wheelPowerKw: Double?
    ): Double {
        if (profile.fuelType == FuelType.ELECTRIC) {
            val kw = wheelPowerKw ?: 0.0
            return if (kw >= 0.0) (kw / 0.88).coerceAtMost(250.0) else (kw * 0.65)
        }
        if (isDfcoCoasting) return 0.0
        if (ecuFuelRateLph != null && ecuFuelRateLph >= 0.0) return ecuFuelRateLph
        if (effectiveMaf <= 0.05) return 0.0

        if (profile.fuelType == FuelType.DIESEL) {
            // Compression ignition runs lean unthrottled (~60:1 at low load to ~18:1 full load)
            val load = (engineLoadPct ?: 25.0).coerceIn(5.0, 100.0)
            val dieselAfr = (60.0 - (0.42 * load)).coerceIn(17.5, 65.0)
            val fuelGramsPerSec = (effectiveMaf / dieselAfr) * (profile.mafTrimPct / 100.0)
            return ((fuelGramsPerSec / profile.fuelType.densityGramsPerLiter) * 3600.0).coerceAtLeast(0.0)
        }

        val wotLambda = when {
            lambda != null -> lambda.coerceIn(0.65, 1.5)
            (engineLoadPct ?: 0.0) > 80.0 || (relThrottlePct ?: 0.0) > 70.0 || (throttlePct ?: 0.0) > 78.0 -> 0.84
            else -> 1.0
        }

        val trimSumPct = (stftPct ?: 0.0) + (ltftPct ?: 0.0)
        val trimFactor = if (lambda == null) (1.0 + trimSumPct / 100.0).coerceIn(0.75, 1.35) else 1.0

        val coldFactor = if (coolantCelsius != null && coolantCelsius < 70.0 && trimSumPct in -2.0..2.0) {
            1.0 + (0.18 * ((70.0 - coolantCelsius) / 70.0)).coerceIn(0.0, 0.22)
        } else {
            1.0
        }

        val afr = profile.fuelType.stoichiometricAfr * wotLambda
        val fuelGramsPerSec = (effectiveMaf / afr) * trimFactor * coldFactor * (profile.mafTrimPct / 100.0)
        return ((fuelGramsPerSec / profile.fuelType.densityGramsPerLiter) * 3600.0).coerceAtLeast(0.0)
    }

    companion object {
        private const val SPECIFIC_GAS_CONSTANT_AIR = 0.287058 // kJ/(kg*K)

        /**
         * Physics Speed-Density fallback with manifold pressure pumping factor + load awareness.
         */
        fun estimateMafSpeedDensity(
            displacementLiters: Double,
            vePct: Double,
            rpm: Double?,
            mapKpa: Double?,
            iatCelsius: Double?,
            baroKpa: Double = 101.325,
            engineLoadPct: Double? = null
        ): Double? {
            if (rpm == null || mapKpa == null || rpm < 100.0) return null
            val tempK = (iatCelsius ?: 35.0) + 273.15
            val pressureRatio = (mapKpa / baroKpa.coerceAtLeast(60.0)).coerceIn(0.2, 2.5)
            val pumpingVeScale = (0.82 + 0.18 * pressureRatio.coerceAtMost(1.0))
            val dynamicVe = if (engineLoadPct != null && pressureRatio < 1.05) {
                val baseVe = vePct * pumpingVeScale
                (baseVe * 0.75 + (engineLoadPct.coerceIn(10.0, 100.0) * 0.25)).coerceIn(vePct * 0.55, vePct * 1.25)
            } else {
                vePct * pumpingVeScale
            }
            val airDensityGramsPerLiter = mapKpa / (SPECIFIC_GAS_CONSTANT_AIR * tempK)
            return airDensityGramsPerLiter * displacementLiters * (rpm / 120.0) * (dynamicVe / 100.0)
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
            val airDensityGramsPerLiter = mapKpa / (SPECIFIC_GAS_CONSTANT_AIR * tempK)
            val theoreticalMaxGramsPerSec = (displacementLiters * (rpm / 120.0)) * airDensityGramsPerLiter
            if (theoreticalMaxGramsPerSec <= 0.01) return null
            return ((mafGramsPerSec / theoreticalMaxGramsPerSec) * 100.0).coerceIn(0.0, 150.0)
        }

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
            sourceLabel = "CALC (010B-0133)",
            defaultUnit = "bar",
            quantity = SensorQuantity.BOOST_KPA,
            minValue = -100.0,
            maxValue = 200.0,
            requiredAnyOfPidGroups = listOf(setOf("0B"))
        ),
        DerivedSensorSpec(
            key = "kpl_instant",
            name = "Instant Fuel Economy",
            shortName = "KPL Inst",
            sourceLabel = "FUSED (MAF/SD/TRIM)",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10", "0D"), setOf("0B", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "kpl_road_avg",
            name = "All Time Avg (Road)",
            shortName = "KPL",
            sourceLabel = "ALL-TIME (No Idle)",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "kpl_trip_avg",
            name = "Trip Fuel Avg",
            shortName = "KPL Trip",
            sourceLabel = "PER-RUN TRIP",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "fuel_flow_lph",
            name = "Fuel Flow Rate",
            shortName = "Fuel/h",
            sourceLabel = "FUSED",
            defaultUnit = "L/h",
            quantity = SensorQuantity.FUEL_FLOW_LPH,
            minValue = 0.0,
            maxValue = 50.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "fuel_used_l",
            name = "Trip Fuel Used",
            shortName = "Fuel Used",
            sourceLabel = "PER-RUN",
            defaultUnit = "L",
            quantity = SensorQuantity.VOLUME_L,
            minValue = 0.0,
            maxValue = 80.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "fuel_remaining",
            name = "Estimated Fuel Left",
            shortName = "Fuel Left",
            sourceLabel = "TANK / 012F",
            defaultUnit = "L",
            quantity = SensorQuantity.VOLUME_L,
            minValue = 0.0,
            maxValue = 90.0
        ),
        DerivedSensorSpec(
            key = "range_dte",
            name = "Estimated Range (DTE)",
            shortName = "Est. Range",
            sourceLabel = "TANK × KPL",
            defaultUnit = "km",
            quantity = SensorQuantity.DISTANCE_KM,
            minValue = 0.0,
            maxValue = 800.0
        ),
        DerivedSensorSpec(
            key = "ve_pct",
            name = "Volumetric Efficiency",
            shortName = "VE %",
            sourceLabel = "IDEAL GAS (MAF+MAP)",
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
            sourceLabel = "WILLANS + DYN",
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
            sourceLabel = "WILLANS + DYN",
            defaultUnit = "Nm",
            quantity = SensorQuantity.TORQUE_NM,
            minValue = 0.0,
            maxValue = 500.0,
            requiredAnyOfPidGroups = listOf(setOf("0C", "10"), setOf("0C", "0B"), setOf("62", "63"))
        ),
        DerivedSensorSpec(
            key = "efficiency_score",
            name = "Driving Efficiency",
            shortName = "Eff.",
            sourceLabel = "BSFC + DFCO + IMU",
            defaultUnit = "/100",
            quantity = SensorQuantity.RAW,
            minValue = 0.0,
            maxValue = 100.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("5E"))
        )
    )

    fun isAvailable(spec: DerivedSensorSpec, supportedMode01Pids: Set<String>): Boolean {
        if (spec.requiredAnyOfPidGroups.isEmpty() || supportedMode01Pids.isEmpty()) return true
        return spec.requiredAnyOfPidGroups.any { group -> group.all { it in supportedMode01Pids } }
    }
}
