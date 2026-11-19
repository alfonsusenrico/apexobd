package com.apexobd.core.protocol

import com.apexobd.core.model.FuelCoastingMode
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.TransmissionType
import com.apexobd.core.model.VehicleProfile
import kotlin.math.abs

data class FuelMetricsSnapshot(
    val fuelFlowLitersPerHour: Double,
    val instantKpl: Double,
    val tripAvgKpl: Double,
    val roadOnlyAvgKpl: Double,
    val allTimeRoadAvgKpl: Double,
    val allTimeTotalAvgKpl: Double,
    val totalFuelUsedLiters: Double,
    val roadFuelUsedLiters: Double,
    val idleFuelUsedLiters: Double,
    val totalDistanceKm: Double,
    val roadDistanceKm: Double,
    val allTimeRoadDistanceKm: Double,
    val allTimeRoadFuelLiters: Double,
    val allTimeTotalDistanceKm: Double,
    val allTimeTotalFuelLiters: Double,
    val fuelUsedSinceFullRefuelLiters: Double,
    val allTimeOdometerKm: Double,
    val estimatedRemainingFuelLiters: Double,
    val estimatedRangeKm: Double?,
    val dteEconomyKpl: Double?,
    val rawRecentDteKpl: Double? = null,
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
    private var allTimeTotalDistKm = 0.0
    private var allTimeTotalFuelL = 0.0
    private var fuelUsedSinceFullL = 0.0
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
            allTimeTotalDistKm = maxOf(profile.allTimeTotalDistanceKm, profile.allTimeRoadDistanceKm)
            allTimeTotalFuelL = maxOf(profile.allTimeTotalFuelLiters, profile.allTimeRoadFuelLiters)
            fuelUsedSinceFullL = profile.fuelUsedSinceFullRefuelLiters.coerceAtLeast(0.0)
            allTimeOdometerKm = profile.allTimeOdometerKm
            estimatedFuelLiters = profile.estimatedRemainingFuelLiters.coerceIn(0.0, profile.tankCapacityLiters)
            recentDteKpl = profile.recentDteKpl?.takeIf { it in 1.5..28.0 }
            displayedRangeKm = null
            recentSliceDistKm = 0.0
            recentSliceFuelL = 0.0
        }
    }

    fun applyRefuelAdded(addedVolumeLiters: Double, tankCapacityLiters: Double, isFullTank: Boolean = false) {
        if (addedVolumeLiters <= 0.0) return
        estimatedFuelLiters = if (isFullTank) {
            tankCapacityLiters
        } else {
            (estimatedFuelLiters + addedVolumeLiters).coerceIn(0.0, tankCapacityLiters)
        }
        if (isFullTank) {
            fuelUsedSinceFullL = 0.0
        }
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
        allTimeTotalDistKm = 0.0
        allTimeTotalFuelL = 0.0
        fuelUsedSinceFullL = 0.0
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
        fuelSystemStatus: Double? = null,
        fuelCoastingMode: FuelCoastingMode = FuelCoastingMode.REALISTIC_DFCO,
        nowMs: Long = System.currentTimeMillis()
    ): FuelMetricsSnapshot {
        seedFromProfile(profile)

        val effectiveBaro = (baroKpa ?: 101.325).coerceIn(60.0, 110.0)
        val effectiveMapKpa = mapKpa ?: inferMapFromEngineLoadKpa(engineLoadPct, effectiveBaro)
        val effectiveMaf = mafGramsPerSec ?: estimateMafSpeedDensity(
            displacementLiters = profile.displacementLiters,
            vePct = profile.volumetricEfficiencyPct,
            rpm = rpm,
            mapKpa = effectiveMapKpa,
            iatCelsius = iatCelsius,
            baroKpa = effectiveBaro,
            engineLoadPct = engineLoadPct
        ) ?: 0.0

        val isClosedThrottle = when {
            relThrottlePct != null -> relThrottlePct <= 1.5
            throttlePct != null -> throttlePct <= 12.0 && effectiveMapKpa != null && effectiveMapKpa <= effectiveBaro * 0.28
            else -> false
        }
        val highVacuum = effectiveMapKpa != null && effectiveMapKpa <= effectiveBaro * 0.28
        val isWarmEnough = (coolantCelsius ?: 80.0) >= 65.0
        val isManualOrEv = profile.transmission == TransmissionType.MANUAL ||
            profile.transmission == TransmissionType.SINGLE_SPEED_EV
        val minDfcoSpeed = if (isManualOrEv) 18.0 else 26.0
        val minDfcoRpm = if (isManualOrEv) 1500.0 else 1700.0

        val sysStatusInt = fuelSystemStatus?.toInt()
        // SAE 0103 bit 1 (0x02) = closed loop firing; suppress unverified DFCO unless O2 confirms extreme lean cut
        val ecuClosedLoopFiring = sysStatusInt != null && (sysStatusInt and 0x02) != 0 && (lambda == null || lambda < 1.65)
        // SAE 0103 bit 2 (0x04) = open-loop load or decel fuel cut; O2 lambda >= 1.75 confirms injector cut
        val ecuDecelCutConfirmed = (sysStatusInt != null && (sysStatusInt and 0x04) != 0 &&
            isClosedThrottle && speedKmh > 14.0 && (rpm ?: 0.0) > 1250.0) ||
            (lambda != null && lambda >= 1.75 && isClosedThrottle && speedKmh > 14.0 && (rpm ?: 0.0) > 1250.0)

        val isDfcoCoasting = profile.fuelType != FuelType.ELECTRIC &&
            fuelCoastingMode == FuelCoastingMode.REALISTIC_DFCO &&
            isClosedThrottle &&
            isWarmEnough &&
            !ecuClosedLoopFiring &&
            (ecuDecelCutConfirmed || (highVacuum && speedKmh >= minDfcoSpeed && (rpm ?: 0.0) >= minDfcoRpm))

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
                allTimeTotalDistKm += dDistKm
                allTimeTotalFuelL += dFuelL
                fuelUsedSinceFullL += dFuelL

                // Update DTE rolling window using BOTH distance & total fuel (idle included, conservative cap 28 km/L)
                if (dDistKm > 0.0 || dFuelL > 0.0) {
                    recentSliceDistKm += dDistKm
                    recentSliceFuelL += dFuelL
                    val sliceReady = if (recentDteKpl == null) {
                        recentSliceDistKm >= 0.025 || recentSliceFuelL >= 0.015
                    } else {
                        recentSliceDistKm >= 0.12 || recentSliceFuelL >= 0.025
                    }
                    if (sliceReady) {
                        if (recentSliceFuelL > 0.0005) {
                            val sliceKpl = (recentSliceDistKm / recentSliceFuelL).coerceIn(2.0, 24.0)
                            val alpha = (recentSliceFuelL / 0.85).coerceIn(0.02, 0.25)
                            recentDteKpl = recentDteKpl?.let { prev -> alpha * sliceKpl + (1.0 - alpha) * prev } ?: sliceKpl
                        }
                        recentSliceDistKm = 0.0
                        recentSliceFuelL = 0.0
                    }
                }

                if (speedKmh > profile.idleSpeedThresholdKmh) {
                    roadDistanceKm += dDistKm
                    roadFuelLiters += dFuelL
                    allTimeRoadDistKm += dDistKm
                    allTimeRoadFuelL += dFuelL
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
        val allTimeTotalAvg = if (allTimeTotalFuelL > 0.0005) (allTimeTotalDistKm / allTimeTotalFuelL).coerceIn(0.0, 99.9) else tripAvg

        val slowBaseline = if (allTimeTotalFuelL > 0.005) {
            (allTimeTotalDistKm / allTimeTotalFuelL).coerceIn(1.5, 40.0)
        } else if (totalFuelLiters > 0.002) {
            tripAvg.takeIf { it > 1.0 }
        } else null
        val fastWindow = recentDteKpl ?: tripAvg.takeIf { totalFuelLiters > 0.002 && it > 1.0 }

        val dteKpl = computeConservativeDteKpl(
            allTimeTotalAvgKpl = slowBaseline,
            recentDteKpl = fastWindow,
            remainingFuelLiters = estimatedFuelLiters,
            tankCapacityLiters = profile.tankCapacityLiters
        )

        val estRangeRaw = computeEstimatedRangeKm(
            remainingFuelLiters = estimatedFuelLiters,
            tankCapacityLiters = profile.tankCapacityLiters,
            dteKpl = dteKpl
        )
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
            allTimeTotalAvgKpl = allTimeTotalAvg,
            totalFuelUsedLiters = totalFuelLiters,
            roadFuelUsedLiters = roadFuelLiters,
            idleFuelUsedLiters = idleFuelLiters,
            totalDistanceKm = totalDistanceKm,
            roadDistanceKm = roadDistanceKm,
            allTimeRoadDistanceKm = allTimeRoadDistKm,
            allTimeRoadFuelLiters = allTimeRoadFuelL,
            allTimeTotalDistanceKm = allTimeTotalDistKm,
            allTimeTotalFuelLiters = allTimeTotalFuelL,
            fuelUsedSinceFullRefuelLiters = fuelUsedSinceFullL,
            allTimeOdometerKm = allTimeOdometerKm,
            estimatedRemainingFuelLiters = estimatedFuelLiters,
            estimatedRangeKm = smoothedRange,
            dteEconomyKpl = dteKpl,
            rawRecentDteKpl = recentDteKpl,
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

        val load = engineLoadPct ?: 0.0
        val wotLambda = when {
            lambda != null -> lambda.coerceIn(0.65, 1.5)
            load > 82.0 || (relThrottlePct ?: 0.0) > 72.0 || (throttlePct ?: 0.0) > 78.0 -> 0.84
            load > 70.0 -> 1.0 - ((load - 70.0) / 12.0) * 0.14
            else -> 1.0
        }

        val trimSumPct = (stftPct ?: 0.0) + (ltftPct ?: 0.0)
        val trimFactor = if (lambda == null) (1.0 + trimSumPct / 100.0).coerceIn(0.75, 1.35) else 1.0

        val coldFactor = if (coolantCelsius != null && coolantCelsius < 70.0 && trimSumPct > -3.0) {
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
         * Infers intake manifold absolute pressure (kPa) from SAE J1979 Engine Load (`0104`) and Baro
         * on naturally aspirated engines when `010B MAP` is not exposed by the ECU.
         */
        fun inferMapFromEngineLoadKpa(engineLoadPct: Double?, baroKpa: Double = 101.325): Double? {
            val load = engineLoadPct ?: return null
            val safeBaro = baroKpa.coerceIn(60.0, 110.0)
            return (safeBaro * ((load.coerceIn(0.0, 100.0) / 100.0) * 0.94 + 0.04)).coerceIn(15.0, safeBaro * 1.02)
        }

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
            val resolvedMap = mapKpa ?: inferMapFromEngineLoadKpa(engineLoadPct, baroKpa)
            if (rpm == null || resolvedMap == null || rpm < 100.0) return null
            val tempK = (iatCelsius ?: 35.0) + 273.15
            val pressureRatio = (resolvedMap / baroKpa.coerceAtLeast(60.0)).coerceIn(0.2, 2.5)
            val pumpingVeScale = (0.82 + 0.18 * pressureRatio.coerceAtMost(1.0))
            val dynamicVe = if (engineLoadPct != null && pressureRatio < 1.05) {
                val baseVe = vePct * pumpingVeScale
                (baseVe * 0.75 + (engineLoadPct.coerceIn(10.0, 100.0) * 0.25)).coerceIn(vePct * 0.55, vePct * 1.25)
            } else {
                vePct * pumpingVeScale
            }
            val airDensityGramsPerLiter = resolvedMap / (SPECIFIC_GAS_CONSTANT_AIR * tempK)
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

        /**
         * Bounded full-tank pump calibration for `mafTrimPct` (`75%..135%`).
         * Blends 40% toward `currentTrimPct * (actualPumpLiters / obdFuelUsedSinceFullLiters)`
         * only when meaningful fuel (`>= 5.0 L`) was tracked since the previous full fill.
         */
        fun computeCalibratedTrimPct(
            currentTrimPct: Double,
            actualPumpLiters: Double,
            obdFuelUsedSinceFullLiters: Double
        ): Double {
            val clampedCurrent = currentTrimPct.coerceIn(75.0, 135.0)
            if (actualPumpLiters < 3.0 || obdFuelUsedSinceFullLiters < 5.0) return clampedCurrent
            val ratio = (actualPumpLiters / obdFuelUsedSinceFullLiters).coerceIn(0.80, 1.25)
            val targetTrim = (clampedCurrent * ratio).coerceIn(75.0, 135.0)
            return ((0.40 * targetTrim) + (0.60 * clampedCurrent)).coerceIn(75.0, 135.0)
        }

        /** Reserve cushion (10% of tank, bounded to 2..6 L) kept out of usable range before dry empty. */
        fun computeReserveLiters(tankCapacityLiters: Double): Double =
            (tankCapacityLiters.coerceAtLeast(1.0) * 0.10).coerceIn(2.0, 6.0)

        /** Remaining usable fuel liters above safety reserve. */
        fun computeUsableFuelLiters(remainingFuelLiters: Double, tankCapacityLiters: Double): Double {
            val cap = tankCapacityLiters.coerceAtLeast(1.0)
            return (remainingFuelLiters.coerceIn(0.0, cap) - computeReserveLiters(cap)).coerceAtLeast(0.0)
        }

        /**
         * Cumulative-anchored DTE fuel economy: weights lifetime all-time economy `80%` on a high tank
         * (rising to `55%` below quarter tank) and prevents highway spikes from inflating high-tank estimates.
         */
        fun computeConservativeDteKpl(
            allTimeTotalAvgKpl: Double?,
            recentDteKpl: Double?,
            remainingFuelLiters: Double,
            tankCapacityLiters: Double
        ): Double? {
            val slow = allTimeTotalAvgKpl?.takeIf { it > 1.0 }?.coerceIn(1.5, 40.0)
            val fast = recentDteKpl?.takeIf { it > 1.0 }?.coerceIn(1.5, 24.0)
            return when {
                fast != null && slow != null -> {
                    val tankFrac = (remainingFuelLiters / tankCapacityLiters.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                    val wRecent = when {
                        tankFrac <= 0.25 -> 0.45
                        tankFrac <= 0.50 -> 0.30
                        else -> 0.20
                    }
                    val blended = (wRecent * fast) + ((1.0 - wRecent) * slow)
                    val capped = if (tankFrac > 0.50) minOf(blended, slow) else blended.coerceAtMost(slow * 1.04)
                    capped.coerceIn(1.5, 28.0)
                }
                fast != null -> fast.coerceIn(1.5, 24.0)
                slow != null -> slow.coerceIn(1.5, 28.0)
                else -> null
            }
        }

        /** Estimated usable range (km) after deducting standard safety reserve cushion. */
        fun computeEstimatedRangeKm(
            remainingFuelLiters: Double,
            tankCapacityLiters: Double,
            dteKpl: Double?
        ): Double? {
            val kpl = dteKpl?.takeIf { it > 0.5 } ?: return null
            return (computeUsableFuelLiters(remainingFuelLiters, tankCapacityLiters) * kpl).coerceAtLeast(0.0)
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
            maxValue = 220.0
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
            shortName = "KPL · INST",
            sourceLabel = "FUSED (MAF/SD/TRIM)",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10", "0D"), setOf("0B", "0C"), setOf("04", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "kpl_road_avg",
            name = "All Time Avg (Total w/ Idle)",
            shortName = "KPL · AVG",
            sourceLabel = "ALL-TIME (Incl. Idle)",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("04", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "kpl_moving_avg",
            name = "Moving Avg (Road Only)",
            shortName = "KPL · MOVE",
            sourceLabel = "ALL-TIME (No Idle)",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("04", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "kpl_trip_avg",
            name = "Trip KPL",
            shortName = "KPL · TRIP",
            sourceLabel = "REC RUN TRIP",
            defaultUnit = "km/L",
            quantity = SensorQuantity.FUEL_ECONOMY_KPL,
            minValue = 0.0,
            maxValue = 40.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("04", "0C"), setOf("5E"))
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
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("04", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "fuel_used_l",
            name = "Trip Fuel Used",
            shortName = "Trip Fuel",
            sourceLabel = "REC RUN",
            defaultUnit = "L",
            quantity = SensorQuantity.VOLUME_L,
            minValue = 0.0,
            maxValue = 80.0,
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("04", "0C"), setOf("5E"))
        ),
        DerivedSensorSpec(
            key = "trip_range",
            name = "Trip Range",
            shortName = "Trip Range",
            sourceLabel = "REC RUN",
            defaultUnit = "km",
            quantity = SensorQuantity.DISTANCE_KM,
            minValue = 0.0,
            maxValue = 500.0
        ),
        DerivedSensorSpec(
            key = "trip_time",
            name = "Trip Time",
            shortName = "Trip Time",
            sourceLabel = "REC RUN",
            defaultUnit = "mm:ss",
            quantity = SensorQuantity.DURATION_SEC,
            minValue = 0.0,
            maxValue = 10800.0
        ),
        DerivedSensorSpec(
            key = "trip_avg_speed",
            name = "Trip Avg Speed",
            shortName = "Trip Spd",
            sourceLabel = "REC RUN",
            defaultUnit = "km/h",
            quantity = SensorQuantity.SPEED,
            minValue = 0.0,
            maxValue = 200.0
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
            requiredAnyOfPidGroups = listOf(setOf("0C", "10"), setOf("0C", "0B"), setOf("0C", "04"), setOf("62", "63"))
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
            requiredAnyOfPidGroups = listOf(setOf("0C", "10"), setOf("0C", "0B"), setOf("0C", "04"), setOf("62", "63"))
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
            requiredAnyOfPidGroups = listOf(setOf("10"), setOf("0B", "0C"), setOf("04", "0C"), setOf("5E"))
        )
    )

    fun isAvailable(spec: DerivedSensorSpec, supportedMode01Pids: Set<String>): Boolean {
        if (spec.requiredAnyOfPidGroups.isEmpty() || supportedMode01Pids.isEmpty()) return true
        return spec.requiredAnyOfPidGroups.any { group -> group.all { it in supportedMode01Pids } }
    }
}

/**
 * Centralized run-record-triggered snapshot for the 5 trip metrics (`kpl_trip_avg`,
 * `fuel_used_l`, `trip_range`, `trip_time`, `trip_avg_speed`). Remains idle until the user
 * triggers Run Recording via the Map/Logger control.
 */
data class TripRunMetrics(
    val activeOrCompleted: Boolean = false,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val distanceKm: Double = 0.0,
    val fuelUsedLiters: Double = 0.0,
    val elapsedSec: Long = 0L,
    val avgKpl: Double? = null,
    val avgSpeedKmh: Double? = null
)

object TripRunCalculator {
    val TRIP_SENSOR_KEYS = setOf(
        "kpl_trip_avg",
        "fuel_used_l",
        "trip_range",
        "trip_time",
        "trip_avg_speed"
    )

    fun computeStep(
        previousDistanceKm: Double,
        gpsDeltaKm: Double?,
        gpsHasRealFix: Boolean,
        msSinceLastGpsMovement: Long,
        useSimulator: Boolean,
        speedKmh: Double,
        dtMs: Long,
        elapsedMs: Long,
        fuelDeltaLiters: Double
    ): TripRunMetrics {
        val dtSafe = dtMs.coerceIn(0L, 5_000L)
        val distInc = when {
            !useSimulator && gpsHasRealFix -> {
                when {
                    gpsDeltaKm != null && gpsDeltaKm in 0.001..0.35 && speedKmh >= 1.0 -> gpsDeltaKm
                    msSinceLastGpsMovement >= 2_500L && speedKmh >= 2.5 -> speedKmh * (dtSafe / 3_600_000.0)
                    else -> 0.0
                }
            }
            speedKmh >= 0.8 -> speedKmh * (dtSafe / 3_600_000.0)
            else -> 0.0
        }
        val nextDist = (previousDistanceKm + distInc).coerceAtLeast(0.0)
        val nextFuel = fuelDeltaLiters.coerceAtLeast(0.0)
        val safeElapsedMs = elapsedMs.coerceAtLeast(0L)
        val elapsedHours = safeElapsedMs / 3_600_000.0
        val avgSpd = if (safeElapsedMs >= 1_000L) {
            (nextDist / elapsedHours.coerceAtLeast(1e-6)).coerceIn(0.0, 400.0)
        } else {
            0.0
        }
        val avgKpl = if (nextFuel > 0.002) {
            (nextDist / nextFuel).coerceIn(0.0, 99.9)
        } else {
            null
        }
        return TripRunMetrics(
            activeOrCompleted = true,
            isRecording = true,
            distanceKm = nextDist,
            fuelUsedLiters = nextFuel,
            elapsedSec = safeElapsedMs / 1_000L,
            avgKpl = avgKpl,
            avgSpeedKmh = avgSpd
        )
    }

    fun applyToLiveValues(base: Map<String, Double>, trip: TripRunMetrics): Map<String, Double> {
        val out = base.toMutableMap()
        if (!trip.activeOrCompleted) {
            out.remove("kpl_trip_avg")
            out.remove("fuel_used_l")
            out.remove("trip_range")
            out.remove("trip_avg_speed")
            out["trip_time"] = 0.0
            return out
        }
        out["trip_range"] = trip.distanceKm
        out["fuel_used_l"] = trip.fuelUsedLiters
        out["trip_time"] = trip.elapsedSec.toDouble()
        trip.avgSpeedKmh?.let { out["trip_avg_speed"] = it } ?: out.remove("trip_avg_speed")
        trip.avgKpl?.let { out["kpl_trip_avg"] = it } ?: out.remove("kpl_trip_avg")
        return out
    }

    fun formatDurationClock(totalSeconds: Long): String {
        val sec = totalSeconds.coerceAtLeast(0L)
        val h = sec / 3_600L
        val m = (sec % 3_600L) / 60L
        val s = sec % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

enum class GaugeSmoothingMode(
    val label: String,
    val tauMs: Double
) {
    RAW_INSTANT("Raw Instant (0ms)", 0.0),
    LIGHT_DAMPER("Light Damper (26ms)", 26.0),
    ADAPTIVE_GLIDE("Adaptive Glide", 85.0)
}

/**
 * UI-only visual smoothing/interpolation for rapid telemetry channels (`rpm`, throttle, boost,
 * speed, power) so dial needles and fast numbers transition smoothly between OBD samples
 * WITHOUT modifying the underlying raw [TelemetrySampleEntity] logs or KPL/trip math.
 */
object TelemetryDisplaySmoother {
    val SMOOTHED_KEYS = setOf(
        "rpm",
        "obd_speed",
        "gps_speed",
        "maf",
        "map",
        "boost_kpa",
        "boost_bar",
        "rel_throttle",
        "throttle",
        "pedal_d",
        "pedal_e",
        "engine_load",
        "hp",
        "torque",
        "kpl_instant",
        "kpl_road_avg",
        "accel_g"
    )

    private val GPS_ROLLING_KEYS = setOf("gps_speed")
    private val OBD_SPEED_KEYS = setOf("obd_speed")
    private val KPL_INSTANT_KEYS = setOf("kpl_instant")
    private val KPL_AVG_KEYS = setOf("kpl_road_avg")
    private val MICRO_SHIMMER_KEYS = setOf("rpm", "engine_load", "rel_throttle", "throttle", "pedal_d", "pedal_e", "kpl_instant", "kpl_road_avg")

    private data class ChannelSpring(
        var pos: Double,
        var vel: Double,
        var target: Double,
        var lastTargetAtMs: Long,
        var avgPeriodSec: Double,
        var activeStopSnap: Boolean = false,
        var wakeBoosted: Boolean = false
    )

    private data class ChannelTimingSpec(
        val initialPeriodSec: Double,
        val minPeriodSec: Double,
        val maxPeriodSec: Double,
        val plateauResetMs: Long,
        val wakePeriodSec: Double,
        val lightScale: Double,
        val adaptiveScale: Double,
        val minSmoothSec: Double,
        val maxSmoothSec: Double,
        val stopSmoothSec: Double
    )

    private fun specForKey(key: String): ChannelTimingSpec = when (key) {
        in GPS_ROLLING_KEYS -> ChannelTimingSpec(
            initialPeriodSec = 0.96,
            minPeriodSec = 0.25,
            maxPeriodSec = 1.25,
            plateauResetMs = 2400L,
            wakePeriodSec = 0.24,
            lightScale = 0.36,
            adaptiveScale = 0.44,
            minSmoothSec = 0.14,
            maxSmoothSec = 0.48,
            stopSmoothSec = 0.038
        )
        in OBD_SPEED_KEYS -> ChannelTimingSpec(
            initialPeriodSec = 0.24,
            minPeriodSec = 0.08,
            maxPeriodSec = 1.00,
            plateauResetMs = 1800L,
            wakePeriodSec = 0.16,
            lightScale = 0.48,
            adaptiveScale = 0.62,
            minSmoothSec = 0.10,
            maxSmoothSec = 0.42,
            stopSmoothSec = 0.038
        )
        in KPL_INSTANT_KEYS -> ChannelTimingSpec(
            initialPeriodSec = 0.28,
            minPeriodSec = 0.08,
            maxPeriodSec = 1.10,
            plateauResetMs = 1800L,
            wakePeriodSec = 0.28,
            lightScale = 0.95,
            adaptiveScale = 1.15,
            minSmoothSec = 0.28,
            maxSmoothSec = 0.55,
            stopSmoothSec = 0.22
        )
        in KPL_AVG_KEYS -> ChannelTimingSpec(
            initialPeriodSec = 0.25,
            minPeriodSec = 0.08,
            maxPeriodSec = 1.10,
            plateauResetMs = 1800L,
            wakePeriodSec = 0.25,
            lightScale = 0.56,
            adaptiveScale = 0.72,
            minSmoothSec = 0.18,
            maxSmoothSec = 0.48,
            stopSmoothSec = 0.18
        )
        else -> ChannelTimingSpec(
            initialPeriodSec = 0.22,
            minPeriodSec = 0.05,
            maxPeriodSec = 1.10,
            plateauResetMs = 1750L,
            wakePeriodSec = 0.14,
            lightScale = 0.56,
            adaptiveScale = 0.76,
            minSmoothSec = 0.11,
            maxSmoothSec = 0.46,
            stopSmoothSec = 0.11
        )
    }

    /**
     * C1 critically damped 2nd-order spring (`SmoothDamp`, Game Programming Gems 4)
     * that preserves momentum across variable-cadence CAN/GPS arrivals without Hermite endpoint braking.
     */
    class AdaptiveSession {
        private val tracks = HashMap<String, ChannelSpring>(20)

        fun reset() {
            tracks.clear()
        }

        fun step(
            rawTargets: Map<String, Double>,
            dtMs: Long,
            nowMs: Long,
            mode: GaugeSmoothingMode,
            liveMicroMotion: Boolean = false
        ): Map<String, Double> {
            if (rawTargets.isEmpty()) return emptyMap()
            val out = if (mode == GaugeSmoothingMode.RAW_INSTANT) {
                rawTargets.toMutableMap()
            } else {
                val dtSec = (dtMs.coerceIn(1L, 500L)) / 1000.0
                val res = LinkedHashMap<String, Double>(rawTargets.size)
                for ((k, raw) in rawTargets) {
                    if (k !in SMOOTHED_KEYS || !raw.isFinite()) {
                        res[k] = raw
                        continue
                    }
                    val spec = specForKey(k)
                    val spring = tracks[k]
                    if (spring == null || !spring.target.isFinite()) {
                        tracks[k] = ChannelSpring(
                            pos = raw,
                            vel = 0.0,
                            target = raw,
                            lastTargetAtMs = nowMs,
                            avgPeriodSec = spec.initialPeriodSec
                        )
                        res[k] = raw
                        continue
                    }

                    if (abs(raw - spring.target) > 1e-4) {
                        val gapMs = (nowMs - spring.lastTargetAtMs).coerceAtLeast(1L)
                        val isStopping = k in rollingSpeedKeys && raw < 0.8 && spring.pos > 1.5
                        val measuredSec = if (gapMs > spec.plateauResetMs) {
                            spec.wakePeriodSec
                        } else {
                            (gapMs / 1000.0).coerceIn(spec.minPeriodSec, spec.maxPeriodSec)
                        }
                        spring.avgPeriodSec = when {
                            gapMs > spec.plateauResetMs -> measuredSec
                            measuredSec > spring.avgPeriodSec -> (spring.avgPeriodSec * 0.40) + (measuredSec * 0.60)
                            else -> (spring.avgPeriodSec * 0.55) + (measuredSec * 0.45)
                        }
                        spring.lastTargetAtMs = nowMs
                        spring.target = raw
                        spring.activeStopSnap = isStopping
                        spring.wakeBoosted = gapMs > spec.plateauResetMs
                    }

                    if (abs(spring.target - spring.pos) < 1e-6 && abs(spring.vel) < 1e-6) {
                        spring.pos = spring.target
                        spring.vel = 0.0
                        spring.activeStopSnap = false
                        spring.wakeBoosted = false
                        res[k] = spring.pos
                        continue
                    }

                    val smoothSec = if (spring.activeStopSnap) {
                        spec.stopSmoothSec
                    } else if (spring.wakeBoosted) {
                        minOf(spec.minSmoothSec, 0.085)
                    } else {
                        val modeScale = if (mode == GaugeSmoothingMode.LIGHT_DAMPER) spec.lightScale else spec.adaptiveScale
                        (spring.avgPeriodSec * modeScale).coerceIn(spec.minSmoothSec, spec.maxSmoothSec)
                    }
                    val omega = 2.0 / smoothSec.coerceAtLeast(0.01)
                    val x = omega * dtSec
                    val expFactor = kotlin.math.exp(-x)
                    val change = spring.pos - spring.target
                    val temp = (spring.vel + omega * change) * dtSec
                    var nextVel = (spring.vel - omega * temp) * expFactor
                    var nextPos = spring.target + (change + temp) * expFactor

                    val origDelta = spring.target - spring.pos
                    val outDelta = nextPos - spring.target
                    if ((origDelta > 0.0 && outDelta > 0.0) || (origDelta < 0.0 && outDelta < 0.0)) {
                        nextPos = spring.target
                        nextVel = 0.0
                    }
                    if (spring.activeStopSnap && abs(nextPos) < 0.01 && spring.target < 0.05) {
                        nextPos = spring.target
                        nextVel = 0.0
                        spring.activeStopSnap = false
                    }
                    spring.pos = nextPos
                    spring.vel = nextVel
                    res[k] = nextPos
                }
                res
            }

            if (liveMicroMotion && (rawTargets["rpm"] ?: 0.0) > 300.0) {
                val tSec = (nowMs % 600_000L) / 1000.0
                for ((k, v) in out) {
                    if (k in MICRO_SHIMMER_KEYS && v.isFinite() && v > 0.05) {
                        val phase = (k.hashCode() and 0xFF) * 0.13
                        val wave = (kotlin.math.sin(tSec * 9.5 + phase) * 0.65) +
                            (kotlin.math.sin(tSec * 21.7 + phase * 1.9) * 0.35)
                        val amp = when (k) {
                            "rpm" -> 4.0
                            "kpl_instant", "kpl_road_avg" -> 0.04
                            else -> 0.14
                        }
                        val minClamp = if (k == "kpl_instant" || k == "kpl_road_avg") 0.01 else 0.0
                        val maxClamp = if (k in setOf("engine_load", "rel_throttle", "throttle", "pedal_d", "pedal_e")) 100.0 else Double.MAX_VALUE
                        out[k] = (v + wave * amp).coerceIn(minClamp, maxClamp)
                    }
                }
            }
            return out
        }

        private val rollingSpeedKeys = setOf("gps_speed", "obd_speed")
    }

    fun step(
        currentDisplay: Map<String, Double>,
        rawTargets: Map<String, Double>,
        dtMs: Long,
        tauMs: Double = 40.0
    ): Map<String, Double> {
        if (rawTargets.isEmpty()) return emptyMap()
        if (tauMs <= 0.0) return rawTargets
        val safeDt = dtMs.coerceIn(1L, 500L).toDouble()
        val alpha = (1.0 - kotlin.math.exp(-safeDt / tauMs.coerceAtLeast(10.0))).coerceIn(0.05, 1.0)
        val out = LinkedHashMap<String, Double>(rawTargets.size)
        for ((k, raw) in rawTargets) {
            if (k !in SMOOTHED_KEYS) {
                out[k] = raw
            } else {
                val prev = currentDisplay[k]
                if (prev == null || !prev.isFinite()) {
                    out[k] = raw
                } else {
                    val delta = raw - prev
                    out[k] = if (abs(delta) < 0.05 && abs(raw) < 20.0 || abs(delta) < 0.5 && abs(raw) >= 200.0) {
                        raw
                    } else {
                        prev + delta * alpha
                    }
                }
            }
        }
        return out
    }
}
