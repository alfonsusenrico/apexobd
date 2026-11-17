package com.apexobd.core.protocol

import com.apexobd.core.model.FuelEconomyUnit
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.PressureUnit
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.SpeedUnit
import com.apexobd.core.model.TempUnit
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
        val plusTrim = gasTracker.update(gas, speedKmh = 60.0, rpm = 2000.0, mafGramsPerSec = 10.0, mapKpa = 55.0, iatCelsius = 35.0, lambda = null, throttlePct = 20.0, stftPct = 5.0, ltftPct = 5.0, nowMs = 11_000L)
        assertTrue(plusTrim.fuelFlowLitersPerHour > noTrim.fuelFlowLitersPerHour * 1.08)

        gasTracker.applyRefuelAdded(addedVolumeLiters = 15.0, tankCapacityLiters = 45.0)
        val afterFill = gasTracker.update(gas, speedKmh = 60.0, rpm = 2000.0, mafGramsPerSec = 10.0, mapKpa = 55.0, iatCelsius = 35.0, lambda = null, throttlePct = 20.0, nowMs = 12_000L)
        assertTrue(afterFill.estimatedRemainingFuelLiters > 34.0)
        assertTrue(afterFill.estimatedRangeKm > 300.0)
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
        val effSpec = DerivedSensorCatalog.all.first { it.key == "efficiency_score" }
        val boostSpec = DerivedSensorCatalog.all.first { it.key == "boost_kpa" }
        val veSpec = DerivedSensorCatalog.all.first { it.key == "ve_pct" }

        assertEquals("KPL", kplSpec.shortName)
        assertEquals("KPL Trip", tripSpec.shortName)
        assertEquals("Eff.", effSpec.shortName)
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
}
