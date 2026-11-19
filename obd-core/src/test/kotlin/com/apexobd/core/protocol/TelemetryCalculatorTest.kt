package com.apexobd.core.protocol

import com.apexobd.core.model.FuelCoastingMode
import com.apexobd.core.model.FuelEconomyUnit
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.PressureUnit
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.SpeedUnit
import com.apexobd.core.model.TempUnit
import com.apexobd.core.model.TransmissionType
import com.apexobd.core.model.UnitConverter
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.model.VehicleProfile
import com.apexobd.core.model.VolumeUnit
import com.apexobd.core.scheduler.PerformanceCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCalculatorTest {

    @Test
    fun `FuelEconomyTracker excludes stationary idle and manual pause from roadOnlyAvgKpl`() {
        val tracker = FuelEconomyTracker()
        val avanza = VehicleProfile(
            name = "Avanza 1.3L",
            displacementLiters = 1.3,
            weightKg = 1175.0,
            tankCapacityLiters = 45.0,
            estimatedRemainingFuelLiters = 30.0
        )

        tracker.update(avanza, speedKmh = 60.0, rpm = 2200.0, mafGramsPerSec = 10.0, mapKpa = 55.0, iatCelsius = 40.0, lambda = 1.0, throttlePct = 20.0, nowMs = 10_000L)
        val driving = tracker.update(avanza, speedKmh = 60.0, rpm = 2200.0, mafGramsPerSec = 10.0, mapKpa = 55.0, iatCelsius = 40.0, lambda = 1.0, throttlePct = 20.0, nowMs = 46_000L)
        assertTrue(driving.roadOnlyAvgKpl > 10.0)
        assertTrue(driving.estimatedRemainingFuelLiters < 30.0)
        val savedRoadAvg = driving.roadOnlyAvgKpl

        val idling = tracker.update(avanza, speedKmh = 0.0, rpm = 780.0, mafGramsPerSec = 1.6, mapKpa = 29.0, iatCelsius = 65.0, lambda = 1.0, throttlePct = 10.0, nowMs = 82_000L)
        assertTrue(idling.tripAvgKpl < savedRoadAvg)
        assertEquals(savedRoadAvg, idling.roadOnlyAvgKpl, 0.01)
        assertTrue(idling.idleFuelUsedLiters > 0.0)

        tracker.paused = true
        val pausedSnap = tracker.update(avanza, speedKmh = 10.0, rpm = 1200.0, mafGramsPerSec = 6.0, mapKpa = 45.0, iatCelsius = 50.0, lambda = 1.0, throttlePct = 15.0, nowMs = 118_000L)
        assertEquals(idling.totalFuelUsedLiters, pausedSnap.totalFuelUsedLiters, 0.0001)
        assertEquals(savedRoadAvg, pausedSnap.roadOnlyAvgKpl, 0.01)
    }

    @Test
    fun `DFCO triggers on DBW relative throttle zero even when absolute throttle rests above 10 percent`() {
        val tracker = FuelEconomyTracker()
        val avanza = VehicleProfile(name = "Avanza 1.3L")
        val snap = tracker.update(
            profile = avanza,
            speedKmh = 58.0,
            rpm = 2100.0,
            mafGramsPerSec = 3.2,
            mapKpa = 22.0,
            iatCelsius = 55.0,
            lambda = null,
            throttlePct = 10.588, // Real 0111 value when foot is off pedal
            relThrottlePct = 0.0, // Real 0145 value on Toyota CAN
            baroKpa = 100.0,
            nowMs = 10_000L
        )
        assertTrue(snap.dfcoActive)
        assertEquals(0.0, snap.fuelFlowLitersPerHour, 0.0001)
    }

    @Test
    fun `Diesel lean-burn AFR prevents idle overestimation and Gasoline applies STFT LTFT and tank refuel`() {
        val dieselTracker = FuelEconomyTracker()
        val diesel = VehicleProfile(name = "Diesel SUV", displacementLiters = 2.4, fuelType = FuelType.DIESEL)
        val dieselIdle = dieselTracker.update(
            profile = diesel, speedKmh = 0.0, rpm = 750.0, mafGramsPerSec = 11.0,
            mapKpa = 100.0, iatCelsius = 35.0, lambda = null, throttlePct = 0.0, engineLoadPct = 12.0, nowMs = 10_000L
        )
        // At 12% load diesel AFR ~55:1 -> ~0.87 L/h instead of ~3.2 L/h stoichiometric!
        assertTrue(dieselIdle.fuelFlowLitersPerHour in 0.5..1.3)

        val gasTracker = FuelEconomyTracker()
        val gas = VehicleProfile(name = "Avanza", tankCapacityLiters = 45.0, estimatedRemainingFuelLiters = 20.0)
        val noTrim = gasTracker.update(gas, speedKmh = 60.0, rpm = 2000.0, mafGramsPerSec = 10.0, mapKpa = 55.0, iatCelsius = 35.0, lambda = null, throttlePct = 20.0, stftPct = 0.0, ltftPct = 0.0, nowMs = 10_000L)
        // Before any distance/consumption history exists, DTE must be empty (null), never fabricated
        org.junit.Assert.assertNull(noTrim.estimatedRangeKm)

        val plusTrim = gasTracker.update(gas, speedKmh = 60.0, rpm = 2000.0, mafGramsPerSec = 10.0, mapKpa = 55.0, iatCelsius = 35.0, lambda = null, throttlePct = 20.0, stftPct = 5.0, ltftPct = 5.0, nowMs = 11_000L)
        assertTrue(plusTrim.fuelFlowLitersPerHour > noTrim.fuelFlowLitersPerHour * 1.08)

        gasTracker.applyRefuelAdded(addedVolumeLiters = 15.0, tankCapacityLiters = 45.0)
        val afterFill = gasTracker.update(gas, speedKmh = 60.0, rpm = 2000.0, mafGramsPerSec = 10.0, mapKpa = 55.0, iatCelsius = 35.0, lambda = null, throttlePct = 20.0, nowMs = 12_000L)
        assertTrue(afterFill.estimatedRemainingFuelLiters > 34.0)
        assertTrue((afterFill.estimatedRangeKm ?: 0.0) > 300.0)
    }

    @Test
    fun `Willans physics torque clamps near zero at idle and computes positive under load`() {
        val avanza = VehicleProfile(displacementLiters = 1.3, weightKg = 1175.0, drivetrainLossPct = 15.0)
        val (_, idleHp) = PerformanceCalculator.estimatePhysicsTorqueAndHp(
            profile = avanza, rpm = 780.0, speedKmh = 0.0, mafGramsPerSec = 1.6,
            actualTorquePct = null, refTorqueNm = null, engineLoadPct = 15.0,
            timingAdvanceDeg = 7.5, baroKpa = 100.0, iatCelsius = 69.0
        )
        assertTrue((idleHp ?: 99.0) <= 5.0)

        val (wotTq, wotHp) = PerformanceCalculator.estimatePhysicsTorqueAndHp(
            profile = avanza, rpm = 5200.0, speedKmh = 95.0, mafGramsPerSec = 65.0,
            actualTorquePct = null, refTorqueNm = null, engineLoadPct = 85.0,
            timingAdvanceDeg = 22.0, baroKpa = 100.0, iatCelsius = 35.0, imuAccelG = 0.25
        )
        assertTrue((wotHp ?: 0.0) in 45.0..120.0)
        assertTrue((wotTq ?: 0.0) in 70.0..170.0)
    }

    @Test
    fun `DerivedSensorCatalog filters dynamically and uses updated approved short names`() {
        val avanzaPids = setOf("04", "05", "06", "07", "0B", "0C", "0D", "0E", "0F", "10", "11", "14", "2E", "33", "42", "45")
        val kplSpec = DerivedSensorCatalog.all.first { it.key == "kpl_road_avg" }
        val tripSpec = DerivedSensorCatalog.all.first { it.key == "kpl_trip_avg" }
        val instSpec = DerivedSensorCatalog.all.first { it.key == "kpl_instant" }
        val effSpec = DerivedSensorCatalog.all.first { it.key == "efficiency_score" }
        val boostSpec = DerivedSensorCatalog.all.first { it.key == "boost_kpa" }
        val veSpec = DerivedSensorCatalog.all.first { it.key == "ve_pct" }
        val tripRangeSpec = DerivedSensorCatalog.all.first { it.key == "trip_range" }
        val tripTimeSpec = DerivedSensorCatalog.all.first { it.key == "trip_time" }
        val tripAvgSpdSpec = DerivedSensorCatalog.all.first { it.key == "trip_avg_speed" }

        assertEquals("KPL · AVG", kplSpec.shortName)
        assertEquals("KPL · TRIP", tripSpec.shortName)
        assertEquals("KPL · INST", instSpec.shortName)
        assertEquals("Eff.", effSpec.shortName)
        assertEquals(SensorQuantity.DISTANCE_KM, tripRangeSpec.quantity)
        assertEquals(SensorQuantity.DURATION_SEC, tripTimeSpec.quantity)
        assertEquals(SensorQuantity.SPEED, tripAvgSpdSpec.quantity)
        assertTrue(DerivedSensorCatalog.isAvailable(kplSpec, avanzaPids))
        assertTrue(DerivedSensorCatalog.isAvailable(veSpec, avanzaPids))
        assertTrue(DerivedSensorCatalog.isAvailable(boostSpec, avanzaPids))

        val basicCarPids = setOf("05", "0C", "0D")
        assertFalse(DerivedSensorCatalog.isAvailable(boostSpec, basicCarPids))
        assertFalse(DerivedSensorCatalog.isAvailable(veSpec, basicCarPids))
    }

    @Test
    fun `UnitConverter accurately converts speed, distance, volume, pressure, fuel economy, and Electric units`() {
        val imperial = UnitPreferences(
            speed = SpeedUnit.MPH,
            temperature = TempUnit.FAHRENHEIT,
            pressure = PressureUnit.PSI,
            fuelEconomy = FuelEconomyUnit.L_PER_100KM,
            volume = VolumeUnit.GALLONS_US
        )
        assertEquals(62.137, UnitConverter.convert(100.0, SensorQuantity.SPEED, imperial).first, 0.05)
        assertEquals("mi", UnitConverter.convert(100.0, SensorQuantity.DISTANCE_KM, imperial).second)
        assertEquals(62.137, UnitConverter.convert(100.0, SensorQuantity.DISTANCE_KM, imperial).first, 0.05)
        assertEquals("gal", UnitConverter.convert(10.0, SensorQuantity.VOLUME_L, imperial).second)
        assertEquals(2.64, UnitConverter.convert(10.0, SensorQuantity.VOLUME_L, imperial).first, 0.05)
        assertEquals(194.0, UnitConverter.convert(90.0, SensorQuantity.TEMPERATURE, imperial).first, 0.05)
        assertEquals(14.50, UnitConverter.convert(100.0, SensorQuantity.BOOST_KPA, imperial).first, 0.05)
        assertEquals(10.0, UnitConverter.convert(10.0, SensorQuantity.FUEL_ECONOMY_KPL, imperial).first, 0.01)

        val metric = UnitPreferences()
        assertEquals("kWh", UnitConverter.convert(40.0, SensorQuantity.VOLUME_L, metric, FuelType.ELECTRIC).second)
        assertEquals("km/kWh", UnitConverter.convert(6.5, SensorQuantity.FUEL_ECONOMY_KPL, metric, FuelType.ELECTRIC).second)
    }

    @Test
    fun `Dual-window EWMA DTE stays empty before fuel avg data and blends recent highway vs traffic consumption`() {
        val tracker = FuelEconomyTracker()
        val avanza = VehicleProfile(
            id = 11L,
            name = "Avanza 1.3L",
            tankCapacityLiters = 45.0,
            estimatedRemainingFuelLiters = 20.0
        )

        // Fresh idle / zero consumption history -> DTE must remain null
        val idleOnly = tracker.update(avanza, speedKmh = 0.0, rpm = 780.0, mafGramsPerSec = 1.6, mapKpa = 30.0, iatCelsius = 40.0, lambda = 1.0, throttlePct = 10.0, nowMs = 1_000L)
        org.junit.Assert.assertNull(idleOnly.estimatedRangeKm)

        // Efficient highway cruise steps -> produces real EWMA DTE estimate
        var t = 1_000L
        var lastSnap = idleOnly
        repeat(10) {
            t += 20_000L
            lastSnap = tracker.update(avanza, speedKmh = 80.0, rpm = 2400.0, mafGramsPerSec = 7.5, mapKpa = 45.0, iatCelsius = 35.0, lambda = 1.0, throttlePct = 18.0, nowMs = t)
        }
        val highwayRange = lastSnap.estimatedRangeKm!!
        assertTrue(highwayRange > 250.0)
        assertTrue((lastSnap.dteEconomyKpl ?: 0.0) > 13.0)

        // Heavy city load drops recent EWMA and reduces DTE smoothly without violent spikes
        repeat(15) {
            t += 20_000L
            lastSnap = tracker.update(avanza, speedKmh = 25.0, rpm = 2200.0, mafGramsPerSec = 11.0, mapKpa = 75.0, iatCelsius = 45.0, lambda = 1.0, throttlePct = 35.0, nowMs = t)
        }
        assertTrue(lastSnap.estimatedRangeKm!! < highwayRange)
    }

    @Test
    fun `TripRunCalculator remains idle until recording starts and computes all 5 trip metrics accurately`() {
        val standby = TripRunMetrics()
        val startupMap = mapOf("rpm" to 800.0, "kpl_trip_avg" to 11.2, "fuel_used_l" to 0.4)
        val idleLive = TripRunCalculator.applyToLiveValues(startupMap, standby)
        org.junit.Assert.assertNull(idleLive["kpl_trip_avg"])
        org.junit.Assert.assertNull(idleLive["fuel_used_l"])
        org.junit.Assert.assertNull(idleLive["trip_range"])
        org.junit.Assert.assertNull(idleLive["trip_avg_speed"])
        assertEquals(0.0, idleLive["trip_time"] ?: -1.0, 0.001)

        var step = TripRunCalculator.computeStep(
            previousDistanceKm = 0.0,
            gpsDeltaKm = null,
            gpsHasRealFix = false,
            msSinceLastGpsMovement = 0L,
            useSimulator = true,
            speedKmh = 60.0,
            dtMs = 5_000L,
            elapsedMs = 5_000L,
            fuelDeltaLiters = 0.01
        )
        assertTrue(step.distanceKm > 0.08)

        // With active real GPS fix (fresh tick <2.5s and no new GPS coordinate yet), do NOT double-count speed
        val noDoubleCount = TripRunCalculator.computeStep(
            previousDistanceKm = 1.0,
            gpsDeltaKm = null,
            gpsHasRealFix = true,
            msSinceLastGpsMovement = 250L,
            useSimulator = false,
            speedKmh = 60.0,
            dtMs = 250L,
            elapsedMs = 60_000L,
            fuelDeltaLiters = 0.1
        )
        assertEquals(1.0, noDoubleCount.distanceKm, 0.0001)
        assertEquals(5L, step.elapsedSec)
        assertEquals("30:00", TripRunCalculator.formatDurationClock(1800L))
        assertEquals("1:02:05", TripRunCalculator.formatDurationClock(3725L))

        val finalSnap = TripRunMetrics(
            activeOrCompleted = true,
            isRecording = false,
            distanceKm = 21.0,
            fuelUsedLiters = 1.4,
            elapsedSec = 1458L,
            avgKpl = 15.0,
            avgSpeedKmh = 51.85
        )
        val recordedLive = TripRunCalculator.applyToLiveValues(startupMap, finalSnap)
        assertEquals(21.0, recordedLive["trip_range"] ?: 0.0, 0.01)
        assertEquals(1.4, recordedLive["fuel_used_l"] ?: 0.0, 0.01)
        assertEquals(15.0, recordedLive["kpl_trip_avg"] ?: 0.0, 0.01)
        assertEquals(51.85, recordedLive["trip_avg_speed"] ?: 0.0, 0.01)
        assertEquals(1458.0, recordedLive["trip_time"] ?: 0.0, 0.01)
    }

    @Test
    fun `TelemetryDisplaySmoother interpolates rapid gauges smoothly while leaving discrete metrics exact`() {
        val raw0 = mapOf("rpm" to 800.0, "rel_throttle" to 0.0, "trip_time" to 12.0, "coolant" to 88.0)
        val d0 = TelemetryDisplaySmoother.step(emptyMap(), raw0, dtMs = 16L)
        assertEquals(800.0, d0["rpm"]!!, 0.01)

        val rawSpike = mapOf("rpm" to 3200.0, "rel_throttle" to 55.0, "trip_time" to 13.0, "coolant" to 89.0)
        val d1 = TelemetryDisplaySmoother.step(d0, rawSpike, dtMs = 20L, tauMs = 90.0)
        assertTrue(d1["rpm"]!! in 1100.0..1800.0)
        assertTrue(d1["rel_throttle"]!! in 5.0..25.0)
        // Non-smoothed exact values never lag
        assertEquals(13.0, d1["trip_time"]!!, 0.001)
        assertEquals(89.0, d1["coolant"]!!, 0.001)
        // Confirm raw input map wasn't mutated
        assertEquals(3200.0, rawSpike["rpm"]!!, 0.001)

        // AdaptiveSession modes: RAW_INSTANT returns target immediately; ADAPTIVE_GLIDE ramps continuously across period
        val session = TelemetryDisplaySmoother.AdaptiveSession()
        val rawPass = session.step(rawSpike, dtMs = 16L, nowMs = 1000L, mode = GaugeSmoothingMode.RAW_INSTANT)
        assertEquals(3200.0, rawPass["rpm"]!!, 0.001)

        session.reset()
        session.step(mapOf("obd_speed" to 40.0), dtMs = 16L, nowMs = 1000L, mode = GaugeSmoothingMode.ADAPTIVE_GLIDE)
        val firstStep = session.step(mapOf("obd_speed" to 30.0), dtMs = 16L, nowMs = 1200L, mode = GaugeSmoothingMode.ADAPTIVE_GLIDE)
        val midStep = session.step(mapOf("obd_speed" to 30.0), dtMs = 80L, nowMs = 1280L, mode = GaugeSmoothingMode.ADAPTIVE_GLIDE)
        val endStep = session.step(mapOf("obd_speed" to 30.0), dtMs = 300L, nowMs = 1580L, mode = GaugeSmoothingMode.ADAPTIVE_GLIDE)
        assertEquals(40.0, firstStep["obd_speed"]!!, 0.01)
        assertTrue(midStep["obd_speed"]!! in 32.0..38.0)
        assertEquals(30.0, endStep["obd_speed"]!!, 0.01)

        // Long steady hold on throttle/load/speed (2000ms without change) must not inflate subsequent glide duration
        session.reset()
        session.step(mapOf("throttle" to 10.0, "engine_load" to 20.0), dtMs = 16L, nowMs = 1000L, mode = GaugeSmoothingMode.ADAPTIVE_GLIDE)
        session.step(mapOf("throttle" to 10.0, "engine_load" to 20.0), dtMs = 16L, nowMs = 3000L, mode = GaugeSmoothingMode.ADAPTIVE_GLIDE)
        val snapStart = session.step(mapOf("throttle" to 90.0, "engine_load" to 85.0), dtMs = 16L, nowMs = 3016L, mode = GaugeSmoothingMode.ADAPTIVE_GLIDE)
        assertEquals(10.0, snapStart["throttle"]!!, 0.5)
        val fastFollow = session.step(mapOf("throttle" to 90.0, "engine_load" to 85.0), dtMs = 110L, nowMs = 3126L, mode = GaugeSmoothingMode.ADAPTIVE_GLIDE)
        assertTrue("Expected rapid >65% climb by 110ms, got ${fastFollow["throttle"]}", fastFollow["throttle"]!! > 65.0)
        val settled = session.step(mapOf("throttle" to 90.0, "engine_load" to 85.0), dtMs = 60L, nowMs = 3186L, mode = GaugeSmoothingMode.ADAPTIVE_GLIDE)
        assertEquals(90.0, settled["throttle"]!!, 0.01)
        assertEquals(85.0, settled["engine_load"]!!, 0.01)
    }

    @Test
    fun `AT low-RPM city coasting suppresses false 99-9 DFCO while real overrun and 0103 cut engage it`() {
        val tracker = FuelEconomyTracker()
        val avanzaAt = VehicleProfile(
            name = "Avanza 1.3L 4AT",
            transmission = TransmissionType.AUTOMATIC
        )
        // Real observed city coast condition: 30 km/h, 1300 RPM, RelThr 0.0%, MAP 28 kPa -> must NOT fake 99.9!
        val cityCoast = tracker.update(
            profile = avanzaAt,
            speedKmh = 30.0,
            rpm = 1300.0,
            mafGramsPerSec = 2.8,
            mapKpa = 28.0,
            iatCelsius = 55.0,
            lambda = null,
            throttlePct = 11.0,
            relThrottlePct = 0.0,
            baroKpa = 100.0,
            coolantCelsius = 85.0,
            fuelCoastingMode = FuelCoastingMode.REALISTIC_DFCO,
            nowMs = 10_000L
        )
        assertFalse(cityCoast.dfcoActive)
        assertTrue(cityCoast.instantKpl in 20.0..50.0)

        // Real higher-RPM downhill overrun on 4AT: 58 km/h, 2100 RPM, MAP 22 kPa -> true DFCO 99.9
        val downhillCut = tracker.update(
            profile = avanzaAt,
            speedKmh = 58.0,
            rpm = 2100.0,
            mafGramsPerSec = 3.2,
            mapKpa = 22.0,
            iatCelsius = 55.0,
            lambda = null,
            throttlePct = 11.0,
            relThrottlePct = 0.0,
            baroKpa = 100.0,
            coolantCelsius = 85.0,
            fuelCoastingMode = FuelCoastingMode.REALISTIC_DFCO,
            nowMs = 11_000L
        )
        assertTrue(downhillCut.dfcoActive)
        assertEquals(99.9, downhillCut.instantKpl, 0.01)

        // Torque-style AIRFLOW_CONTINUOUS stays airflow-driven even on downhill
        val airflowOnly = tracker.update(
            profile = avanzaAt,
            speedKmh = 58.0,
            rpm = 2100.0,
            mafGramsPerSec = 3.2,
            mapKpa = 22.0,
            iatCelsius = 55.0,
            lambda = null,
            throttlePct = 11.0,
            relThrottlePct = 0.0,
            baroKpa = 100.0,
            coolantCelsius = 85.0,
            fuelCoastingMode = FuelCoastingMode.AIRFLOW_CONTINUOUS,
            nowMs = 12_000L
        )
        assertFalse(airflowOnly.dfcoActive)
        assertTrue(airflowOnly.instantKpl < 65.0)
    }

    @Test
    fun `All-time total includes idle fuel, cold enrichment works under positive LTFT, and refuel trim calibration stays bounded`() {
        val tracker = FuelEconomyTracker()
        val profile = VehicleProfile(
            name = "Avanza 1.3L",
            transmission = TransmissionType.AUTOMATIC,
            tankCapacityLiters = 45.0,
            estimatedRemainingFuelLiters = 40.0
        )
        // Warmup vs warm coolant both with stored +3.5% LTFT
        val coldSnap = tracker.update(
            profile = profile, speedKmh = 0.0, rpm = 950.0, mafGramsPerSec = 3.0, mapKpa = 35.0,
            iatCelsius = 30.0, lambda = null, throttlePct = 12.0, coolantCelsius = 30.0, ltftPct = 3.5, nowMs = 10_000L
        )
        val warmSnap = tracker.update(
            profile = profile, speedKmh = 0.0, rpm = 950.0, mafGramsPerSec = 3.0, mapKpa = 35.0,
            iatCelsius = 30.0, lambda = null, throttlePct = 12.0, coolantCelsius = 85.0, ltftPct = 3.5, nowMs = 11_000L
        )
        assertTrue(coldSnap.fuelFlowLitersPerHour > warmSnap.fuelFlowLitersPerHour * 1.08)

        // Drive then idle 36s -> allTimeTotalAvgKpl drops cleanly below allTimeRoadAvgKpl and counts full-refuel burn
        val driven = tracker.update(
            profile = profile, speedKmh = 50.0, rpm = 2000.0, mafGramsPerSec = 8.0, mapKpa = 52.0,
            iatCelsius = 40.0, lambda = null, throttlePct = 20.0, coolantCelsius = 85.0, nowMs = 47_000L
        )
        val afterIdle = tracker.update(
            profile = profile, speedKmh = 0.0, rpm = 800.0, mafGramsPerSec = 2.4, mapKpa = 32.0,
            iatCelsius = 45.0, lambda = null, throttlePct = 11.0, coolantCelsius = 85.0, nowMs = 83_000L
        )
        assertTrue(afterIdle.allTimeTotalAvgKpl < driven.allTimeRoadAvgKpl)
        assertEquals(afterIdle.allTimeTotalFuelLiters, afterIdle.fuelUsedSinceFullRefuelLiters, 0.0001)
        assertTrue((afterIdle.dteEconomyKpl ?: 99.0) <= 28.0)

        // Full tank refuel calibration: 30 L pump vs 26 L OBD (~1.15 ratio) nudges trim upward safely within [75..135]
        val nextTrim = FuelEconomyTracker.computeCalibratedTrimPct(100.0, actualPumpLiters = 30.0, obdFuelUsedSinceFullLiters = 26.0)
        assertTrue(nextTrim in 104.0..108.0)
        // Too little OBD fuel tracked (< 5L) keeps current trim untouched
        assertEquals(100.0, FuelEconomyTracker.computeCalibratedTrimPct(100.0, actualPumpLiters = 20.0, obdFuelUsedSinceFullLiters = 2.0), 0.001)
    }

    @Test
    fun `2024 Avanza 1-5G MT without 0110 MAF or 010B MAP still computes fuel, KPL, HP, and DFCO from 0104 Engine Load`() {
        val avanza24Pids = setOf(
            "01", "03", "04", "05", "06", "07", "0C", "0D", "0E", "0F", "11", "13", "15", "1C", "1F",
            "20", "21", "24", "2E", "30", "31", "33", "34", "3C", "3E", "40", "42", "44", "45", "46",
            "47", "49", "4A", "4C", "4D", "4E", "51"
        )
        assertTrue(DerivedSensorCatalog.isAvailable(DerivedSensorCatalog.all.first { it.key == "kpl_instant" }, avanza24Pids))
        assertTrue(DerivedSensorCatalog.isAvailable(DerivedSensorCatalog.all.first { it.key == "hp" }, avanza24Pids))
        assertFalse(DerivedSensorCatalog.isAvailable(DerivedSensorCatalog.all.first { it.key == "boost_kpa" }, avanza24Pids))
        assertFalse(DerivedSensorCatalog.isAvailable(DerivedSensorCatalog.all.first { it.key == "ve_pct" }, avanza24Pids))

        val tracker = FuelEconomyTracker()
        val avanza24 = VehicleProfile(
            name = "Avanza 1.5G MT",
            displacementLiters = 1.496,
            engineCc = 1496,
            transmission = TransmissionType.MANUAL,
            weightKg = 1190.0
        )
        // Exact observed 2024 Avanza idle from dump: 695 RPM, Load 33.7%, IAT 40C, Baro 100 kPa, Coolant 67C
        val idle = tracker.update(
            profile = avanza24,
            speedKmh = 0.0,
            rpm = 695.0,
            mafGramsPerSec = null,
            mapKpa = null,
            iatCelsius = 40.0,
            lambda = 1.0,
            throttlePct = 18.4,
            relThrottlePct = 0.0,
            baroKpa = 100.0,
            coolantCelsius = 67.0,
            engineLoadPct = 33.7,
            nowMs = 10_000L
        )
        assertFalse(idle.dfcoActive)
        assertTrue(idle.fuelFlowLitersPerHour in 0.6..1.5)

        // Overrun in 5th gear MT: 55 km/h, 2000 RPM, closed throttle, low Load (15% -> inferred MAP ~18 kPa)
        val coastCut = tracker.update(
            profile = avanza24,
            speedKmh = 55.0,
            rpm = 2000.0,
            mafGramsPerSec = null,
            mapKpa = null,
            iatCelsius = 40.0,
            lambda = 1.9,
            throttlePct = 10.5,
            relThrottlePct = 0.0,
            baroKpa = 100.0,
            coolantCelsius = 85.0,
            engineLoadPct = 15.0,
            nowMs = 12_000L
        )
        // Coast cut in 5th gear
        assertTrue(coastCut.dfcoActive)
        assertEquals(99.9, coastCut.instantKpl, 0.01)
    }

    @Test
    fun `1 Hz gps_speed glides smoothly across full interval in LIGHT_DAMPER and snaps quickly to zero on stops`() {
        val session = TelemetryDisplaySmoother.AdaptiveSession()
        session.step(mapOf("gps_speed" to 20.0), dtMs = 16L, nowMs = 1_000L, mode = GaugeSmoothingMode.LIGHT_DAMPER)
        session.step(mapOf("gps_speed" to 28.0), dtMs = 16L, nowMs = 2_000L, mode = GaugeSmoothingMode.LIGHT_DAMPER)

        // At +400ms into a 1Hz update, value must still be mid-glide rather than frozen at 28
        var cur = 20.0
        for (i in 1..25) {
            cur = session.step(mapOf("gps_speed" to 28.0), dtMs = 16L, nowMs = 2_000L + i * 16L, mode = GaugeSmoothingMode.LIGHT_DAMPER)["gps_speed"]!!
        }
        assertTrue("Expected smooth mid-interval glide (22..26.5), got $cur", cur in 22.0..26.5)

        // Next 1Hz update settles smoothly near 28 and glides toward 36
        for (i in 26..62) {
            cur = session.step(mapOf("gps_speed" to 28.0), dtMs = 16L, nowMs = 2_000L + i * 16L, mode = GaugeSmoothingMode.LIGHT_DAMPER)["gps_speed"]!!
        }
        assertEquals(28.0, cur, 0.25)

        // Hard stop to 0 km/h clears promptly within ~220ms
        session.step(mapOf("gps_speed" to 0.0), dtMs = 16L, nowMs = 3_000L, mode = GaugeSmoothingMode.LIGHT_DAMPER)
        val afterStop = session.step(mapOf("gps_speed" to 0.0), dtMs = 220L, nowMs = 3_220L, mode = GaugeSmoothingMode.LIGHT_DAMPER)
        assertEquals(0.0, afterStop["gps_speed"]!!, 0.01)
    }

    @Test
    fun `Conservative DTE caps high-tank recent spikes to all-time avg and reserves 10 pct safety fuel`() {
        // Real K3-VE scenario: 45 L tank, 41.48 L left, 11.645 km/L all-time avg, 13.8 km/L stale highway recent
        val dteKpl = FuelEconomyTracker.computeConservativeDteKpl(
            allTimeTotalAvgKpl = 11.645,
            recentDteKpl = 13.8,
            remainingFuelLiters = 41.48,
            tankCapacityLiters = 45.0
        )!!
        assertEquals(11.645, dteKpl, 0.001)

        val reserve = FuelEconomyTracker.computeReserveLiters(45.0)
        assertEquals(4.5, reserve, 0.001)

        val estKm = FuelEconomyTracker.computeEstimatedRangeKm(
            remainingFuelLiters = 41.48,
            tankCapacityLiters = 45.0,
            dteKpl = dteKpl
        )!!
        assertEquals((41.48 - 4.5) * 11.645, estKm, 0.1)
        assertTrue(estKm < 440.0)

        // When recent city traffic runs worse (9.5 km/L), blended DTE pulls range downward cleanly
        val cityDteKpl = FuelEconomyTracker.computeConservativeDteKpl(
            allTimeTotalAvgKpl = 11.645,
            recentDteKpl = 9.5,
            remainingFuelLiters = 41.48,
            tankCapacityLiters = 45.0
        )!!
        assertTrue(cityDteKpl < 11.645)
    }

    @Test
    fun `500ms engine_load and kpl_road_avg glide continuously through 400ms without early freeze and liveMicroMotion stays bounded`() {
        val session = TelemetryDisplaySmoother.AdaptiveSession()
        // Seed 500ms ECU cadence
        session.step(mapOf("rpm" to 2000.0, "engine_load" to 30.0, "kpl_road_avg" to 11.0), dtMs = 16L, nowMs = 1_000L, mode = GaugeSmoothingMode.LIGHT_DAMPER)
        session.step(mapOf("rpm" to 2100.0, "engine_load" to 35.0, "kpl_road_avg" to 11.2), dtMs = 16L, nowMs = 1_500L, mode = GaugeSmoothingMode.LIGHT_DAMPER)
        session.step(mapOf("rpm" to 2200.0, "engine_load" to 42.0, "kpl_road_avg" to 11.5), dtMs = 16L, nowMs = 2_000L, mode = GaugeSmoothingMode.LIGHT_DAMPER)

        var snap400 = emptyMap<String, Double>()
        for (i in 1..25) {
            snap400 = session.step(
                mapOf("rpm" to 2200.0, "engine_load" to 42.0, "kpl_road_avg" to 11.5),
                dtMs = 16L,
                nowMs = 2_000L + i * 16L,
                mode = GaugeSmoothingMode.LIGHT_DAMPER
            )
        }
        // At +400ms into a 500ms poll cycle, engine_load and kpl_road_avg must still be actively gliding toward targets
        assertTrue("Expected engine_load < 41.8 while gliding at 400ms, got ${snap400["engine_load"]}", snap400["engine_load"]!! in 36.0..41.8)
        assertTrue("Expected kpl_road_avg < 11.48 while gliding at 400ms, got ${snap400["kpl_road_avg"]}", snap400["kpl_road_avg"]!! in 11.21..11.49)

        // Optional UI micro-motion pulses cleanly without exceeding bounds or mutating input
        val steadyRaw = mapOf("rpm" to 2200.0, "engine_load" to 100.0, "rel_throttle" to 0.0, "kpl_road_avg" to 11.5)
        val pulseA = session.step(steadyRaw, dtMs = 16L, nowMs = 5_000L, mode = GaugeSmoothingMode.LIGHT_DAMPER, liveMicroMotion = true)
        val pulseB = session.step(steadyRaw, dtMs = 16L, nowMs = 5_064L, mode = GaugeSmoothingMode.LIGHT_DAMPER, liveMicroMotion = true)
        assertTrue(pulseA["engine_load"]!! <= 100.0)
        assertEquals(0.0, pulseA["rel_throttle"]!!, 0.001)
        assertTrue(kotlin.math.abs(pulseA["kpl_road_avg"]!! - pulseB["kpl_road_avg"]!!) > 0.0)
        assertEquals(11.5, steadyRaw["kpl_road_avg"]!!, 0.0001)
    }
}
