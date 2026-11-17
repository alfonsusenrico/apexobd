package com.apexobd.core.protocol

import com.apexobd.core.model.FuelEconomyUnit
import com.apexobd.core.model.PressureUnit
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.SpeedUnit
import com.apexobd.core.model.TempUnit
import com.apexobd.core.model.UnitConverter
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.model.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCalculatorTest {

    @Test
    fun `FuelEconomyTracker excludes stationary idle and manual pause from roadOnlyAvgKpl`() {
        val tracker = FuelEconomyTracker()
        val avanza = VehicleProfile(name = "Avanza 1.3L", displacementLiters = 1.3, weightKg = 1175.0)

        // Initial seed
        tracker.update(avanza, speedKmh = 60.0, rpm = 2200.0, mafGramsPerSec = 10.0, mapKpa = 55.0, iatCelsius = 40.0, lambda = 1.0, throttlePct = 20.0, nowMs = 10_000L)
        // Drive 10 minutes at 60 km/h
        val driving = tracker.update(avanza, speedKmh = 60.0, rpm = 2200.0, mafGramsPerSec = 10.0, mapKpa = 55.0, iatCelsius = 40.0, lambda = 1.0, throttlePct = 20.0, nowMs = 46_000L)
        assertTrue(driving.roadOnlyAvgKpl > 10.0)
        val savedRoadAvg = driving.roadOnlyAvgKpl

        // Now idle stationary (speed = 0) for 36 seconds -> tripAvg drops, roadOnlyAvg stays untouched!
        val idling = tracker.update(avanza, speedKmh = 0.0, rpm = 780.0, mafGramsPerSec = 1.6, mapKpa = 29.0, iatCelsius = 65.0, lambda = 1.0, throttlePct = 10.0, nowMs = 82_000L)
        assertTrue(idling.tripAvgKpl < savedRoadAvg)
        assertEquals(savedRoadAvg, idling.roadOnlyAvgKpl, 0.01)
        assertTrue(idling.idleFuelUsedLiters > 0.0)

        // Manual pause while creeping/resting -> neither trip nor road avg changes
        tracker.paused = true
        val pausedSnap = tracker.update(avanza, speedKmh = 10.0, rpm = 1200.0, mafGramsPerSec = 6.0, mapKpa = 45.0, iatCelsius = 50.0, lambda = 1.0, throttlePct = 15.0, nowMs = 118_000L)
        assertEquals(idling.totalFuelUsedLiters, pausedSnap.totalFuelUsedLiters, 0.0001)
        assertEquals(savedRoadAvg, pausedSnap.roadOnlyAvgKpl, 0.01)
    }

    @Test
    fun `DerivedSensorCatalog filters dynamically based on connected car PIDs`() {
        val avanzaPids = setOf("04", "05", "06", "07", "0B", "0C", "0D", "0E", "0F", "10", "11", "14", "2E", "33", "42", "45")
        val kplSpec = DerivedSensorCatalog.all.first { it.key == "kpl_road_avg" }
        val veSpec = DerivedSensorCatalog.all.first { it.key == "ve_pct" }
        val boostSpec = DerivedSensorCatalog.all.first { it.key == "boost_kpa" }

        assertTrue(DerivedSensorCatalog.isAvailable(kplSpec, avanzaPids))
        assertTrue(DerivedSensorCatalog.isAvailable(veSpec, avanzaPids))
        assertTrue(DerivedSensorCatalog.isAvailable(boostSpec, avanzaPids))

        // On a basic car with only RPM + Coolant (no MAF and no MAP), boost & VE are hidden
        val basicCarPids = setOf("05", "0C", "0D")
        assertFalse(DerivedSensorCatalog.isAvailable(boostSpec, basicCarPids))
        assertFalse(DerivedSensorCatalog.isAvailable(veSpec, basicCarPids))
    }

    @Test
    fun `UnitConverter accurately converts speed, temperature, pressure, and fuel economy`() {
        val imperial = UnitPreferences(
            speed = SpeedUnit.MPH,
            temperature = TempUnit.FAHRENHEIT,
            pressure = PressureUnit.PSI,
            fuelEconomy = FuelEconomyUnit.L_PER_100KM
        )
        assertEquals(62.137, UnitConverter.convert(100.0, SensorQuantity.SPEED, imperial).first, 0.05)
        assertEquals(194.0, UnitConverter.convert(90.0, SensorQuantity.TEMPERATURE, imperial).first, 0.05)
        assertEquals(14.50, UnitConverter.convert(100.0, SensorQuantity.BOOST_KPA, imperial).first, 0.05)
        assertEquals(10.0, UnitConverter.convert(10.0, SensorQuantity.FUEL_ECONOMY_KPL, imperial).first, 0.01)
    }
}
