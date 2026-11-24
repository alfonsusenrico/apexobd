package com.apexobd.app

import androidx.compose.ui.graphics.Color
import com.apexobd.app.data.prefs.HudThemePreset
import com.apexobd.app.ui.HudLiveValueStore
import com.apexobd.app.ui.components.MetricAlertLevel
import com.apexobd.app.ui.components.ShiftLampStage
import com.apexobd.app.ui.components.computeShiftLampStage
import com.apexobd.app.ui.components.defaultAlertBelowForSensor
import com.apexobd.app.ui.components.evaluateMetricAlert
import com.apexobd.app.ui.components.formatTelemetryValue
import com.apexobd.app.ui.components.isRpmSensorKey
import com.apexobd.app.ui.components.rpmDialMaxForRedline
import com.apexobd.app.ui.components.segmentedBoxFillFraction
import com.apexobd.app.ui.components.segmentedSpectrumColor
import com.apexobd.app.ui.components.shouldLiftUnitToHeader
import com.apexobd.app.ui.theme.resolveHudVisualPalette
import com.apexobd.core.model.AirflowUnit
import com.apexobd.core.model.FuelEconomyUnit
import com.apexobd.core.model.PowerUnit
import com.apexobd.core.model.PressureUnit
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.SpeedUnit
import com.apexobd.core.model.TempUnit
import com.apexobd.core.model.TorqueUnit
import com.apexobd.core.model.UnitConverter
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.model.VolumeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudWidgetStyleTest {

    @Test
    fun `segmentedSpectrumColor interpolates smoothly from blue through green, yellow, orange to red`() {
        assertEquals(Color(0xFF00A8FF), segmentedSpectrumColor(0.0f))
        assertEquals(Color(0xFF22C55E), segmentedSpectrumColor(0.25f))
        assertEquals(Color(0xFFFACC15), segmentedSpectrumColor(0.50f))
        assertEquals(Color(0xFFF97316), segmentedSpectrumColor(0.75f))
        assertEquals(Color(0xFFEF4444), segmentedSpectrumColor(1.0f))

        // Mid-point between yellow and orange keeps warm high green + red channels
        val warmMid = segmentedSpectrumColor(0.625f)
        assertTrue(warmMid.red > 0.9f)
        assertTrue(warmMid.green in 0.50f..0.75f)
    }

    @Test
    fun `resolveHudVisualPalette provides high-contrast popped labelColor across every theme`() {
        HudThemePreset.entries.forEach { preset ->
            val p = resolveHudVisualPalette(preset)
            // Perceived luminance of component titles stays high on dark HUD surfaces
            val luma = 0.2126f * p.labelColor.red + 0.7152f * p.labelColor.green + 0.0722f * p.labelColor.blue
            assertTrue("Low label luminance $luma on ${preset.name}", luma > 0.78f)
        }
    }

    @Test
    fun `segmentedBoxFillFraction stays empty at zero and fills leading active block smoothly`() {
        // Default 0 / nearly-zero speed shows empty dark boxes
        for (idx in 0 until 12) {
            assertEquals(0.0f, segmentedBoxFillFraction(0.0f, idx, 12), 0.001f)
            assertEquals(0.0f, segmentedBoxFillFraction(0.001f, idx, 12), 0.001f)
        }

        // At 27.5 km/h out of 220 km/h (pct = 0.125 -> 1.50 segments out of 12): box 0 full, box 1 50%, box 2+ empty
        assertEquals(1.0f, segmentedBoxFillFraction(0.125f, 0, 12), 0.001f)
        assertEquals(0.5f, segmentedBoxFillFraction(0.125f, 1, 12), 0.001f)
        assertEquals(0.0f, segmentedBoxFillFraction(0.125f, 2, 12), 0.001f)
    }

    @Test
    fun `formatTelemetryValue truncates gps_speed to integers and keeps 2 decimals on dynamic percentages and KPL`() {
        assertEquals("42", formatTelemetryValue(42.99, 0.0, 220.0, unit = "km/h", sensorKey = "gps_speed"))
        assertEquals("0", formatTelemetryValue(0.95, 0.0, 220.0, unit = "km/h", sensorKey = "gps_speed"))
        assertEquals("99", formatTelemetryValue(99.84, 0.0, 220.0, unit = "km/h", sensorKey = "obd_speed"))

        assertEquals("24.15", formatTelemetryValue(24.154, 0.0, 100.0, unit = "%", sensorKey = "engine_load"))
        assertEquals("1.46", formatTelemetryValue(1.462, 0.0, 100.0, unit = "%", sensorKey = "rel_throttle"))
        assertEquals("11.65", formatTelemetryValue(11.648, 0.0, 30.0, unit = "km/L", sensorKey = "kpl_road_avg"))
        assertEquals("2259", formatTelemetryValue(2258.6, 0.0, 8000.0, unit = "RPM", sensorKey = "rpm"))
    }

    @Test
    fun `HudLiveValueStore isolates channel state slots and preserves unchanged refs`() {
        val store = HudLiveValueStore()
        val rpmState = store.valueStateFor("rpm")
        val coolantState = store.valueStateFor("coolant")
        val coolantHistState = store.historyStateFor("coolant")

        store.updateValuesFrom(mapOf("rpm" to 1200.0, "coolant" to 86.0))
        assertEquals(1200.0, rpmState.value!!, 0.001)
        assertEquals(86.0, coolantState.value!!, 0.001)

        // Updating rpm alone leaves coolant untouched and clears omitted keys cleanly
        store.updateValuesFrom(mapOf("rpm" to 1450.5, "coolant" to 86.0))
        assertEquals(1450.5, rpmState.value!!, 0.001)
        assertEquals(86.0, coolantState.value!!, 0.001)

        store.updateValuesFrom(mapOf("rpm" to 1500.0))
        assertEquals(1500.0, rpmState.value!!, 0.001)
        assertEquals(null, coolantState.value)

        val h1 = listOf(84.0, 85.0, 86.0)
        store.updateHistoryFrom(mapOf("coolant" to h1))
        assertEquals(h1, coolantHistState.value)
        store.updateHistoryFrom(emptyMap())
        assertTrue(coolantHistState.value.isEmpty())
    }

    @Test
    fun `computeShiftLampStage stays off unless configured and progresses green yellow red flash`() {
        assertEquals(ShiftLampStage.OFF, computeShiftLampStage(6800.0, null, 6500))
        assertEquals(ShiftLampStage.OFF, computeShiftLampStage(6800.0, 0.0, 6500))
        assertEquals(ShiftLampStage.OFF, computeShiftLampStage(null, 6000.0, 6500))

        // Trigger = min(6000, 6500) = 6000 -> 60% = 3600, 80% = 4800, 100% = 6000
        assertEquals(ShiftLampStage.OFF, computeShiftLampStage(3599.0, 6000.0, 6500))
        assertEquals(ShiftLampStage.GREEN, computeShiftLampStage(3600.0, 6000.0, 6500))
        assertEquals(ShiftLampStage.GREEN, computeShiftLampStage(4799.0, 6000.0, 6500))
        assertEquals(ShiftLampStage.YELLOW, computeShiftLampStage(4800.0, 6000.0, 6500))
        assertEquals(ShiftLampStage.YELLOW, computeShiftLampStage(5999.0, 6000.0, 6500))
        assertEquals(ShiftLampStage.RED_FLASH, computeShiftLampStage(6000.0, 6000.0, 6500))

        // Shift configured above redline still clamps trigger to redline
        assertEquals(ShiftLampStage.RED_FLASH, computeShiftLampStage(6500.0, 7200.0, 6500))
    }

    @Test
    fun `evaluateMetricAlert respects optional null thresholds and explicit above or below direction`() {
        assertEquals(MetricAlertLevel.NONE, evaluateMetricAlert(115.0, null, null, alertBelow = false))
        assertEquals(MetricAlertLevel.NONE, evaluateMetricAlert(null, 95.0, 105.0, alertBelow = false))

        // High-threshold coolant example (Above >=)
        assertEquals(MetricAlertLevel.NONE, evaluateMetricAlert(94.9, 95.0, 105.0, alertBelow = false))
        assertEquals(MetricAlertLevel.WARN, evaluateMetricAlert(95.0, 95.0, 105.0, alertBelow = false))
        assertEquals(MetricAlertLevel.CRITICAL, evaluateMetricAlert(105.0, 95.0, 105.0, alertBelow = false))

        // Low-threshold battery/fuel example (Below <=)
        assertEquals(MetricAlertLevel.NONE, evaluateMetricAlert(12.6, 12.2, 11.6, alertBelow = true))
        assertEquals(MetricAlertLevel.WARN, evaluateMetricAlert(12.2, 12.2, 11.6, alertBelow = true))
        assertEquals(MetricAlertLevel.CRITICAL, evaluateMetricAlert(11.6, 12.2, 11.6, alertBelow = true))

        // Low-fuel percentage (value >= 12.5 still triggers Below <= 15% without old heuristic bug)
        assertEquals(MetricAlertLevel.WARN, evaluateMetricAlert(14.5, 15.0, 8.0, alertBelow = true))
    }

    @Test
    fun `rpmDialMaxForRedline and sensor helpers classify rpm and low-alert sensors cleanly`() {
        assertEquals(6000.0, rpmDialMaxForRedline(5000), 0.01)
        assertEquals(8000.0, rpmDialMaxForRedline(6500), 0.01)
        assertEquals(9000.0, rpmDialMaxForRedline(8200), 0.01)
        assertEquals(10000.0, rpmDialMaxForRedline(9000), 0.01)
        assertEquals(12000.0, rpmDialMaxForRedline(11000), 0.01)

        assertTrue(isRpmSensorKey("rpm"))
        assertTrue(isRpmSensorKey("cluster_rpm"))
        assertFalse(isRpmSensorKey("coolant"))

        assertTrue(defaultAlertBelowForSensor("voltage"))
        assertTrue(defaultAlertBelowForSensor("fuel_level"))
        assertFalse(defaultAlertBelowForSensor("coolant"))
        assertFalse(defaultAlertBelowForSensor("boost_bar"))
    }

    @Test
    fun `UnitConverter toInternalMetric round-trips non-metric display thresholds`() {
        val imperial = UnitPreferences(
            speed = SpeedUnit.MPH,
            temperature = TempUnit.FAHRENHEIT,
            pressure = PressureUnit.PSI,
            fuelEconomy = FuelEconomyUnit.MPG_US,
            volume = VolumeUnit.GALLONS_US,
            airflow = AirflowUnit.KG_PER_H,
            power = PowerUnit.KW,
            torque = TorqueUnit.LB_FT
        )
        val cases = listOf(
            SensorQuantity.TEMPERATURE to 212.0,
            SensorQuantity.SPEED to 75.0,
            SensorQuantity.PRESSURE_KPA to 18.5,
            SensorQuantity.BOOST_KPA to 14.7,
            SensorQuantity.FUEL_ECONOMY_KPL to 32.0,
            SensorQuantity.VOLUME_L to 11.5,
            SensorQuantity.AIRFLOW_GS to 120.0,
            SensorQuantity.POWER_HP to 95.0,
            SensorQuantity.TORQUE_NM to 140.0
        )
        cases.forEach { (qty, disp) ->
            val metric = UnitConverter.toInternalMetric(disp, qty, imperial)
            val roundTripped = UnitConverter.convert(metric, qty, imperial).first
            assertEquals("Round-trip mismatch for $qty", disp, roundTripped, 0.01)
        }

        val l100Prefs = UnitPreferences(fuelEconomy = FuelEconomyUnit.L_PER_100KM)
        val kpl = UnitConverter.toInternalMetric(8.0, SensorQuantity.FUEL_ECONOMY_KPL, l100Prefs)
        assertEquals(12.5, kpl, 0.01)
        assertEquals(8.0, UnitConverter.convert(kpl, SensorQuantity.FUEL_ECONOMY_KPL, l100Prefs).first, 0.01)
    }

    @Test
    fun `formatCalibrationFuelPresetLabel escapes percent sign safely for 25 to 100 percent presets`() {
        assertEquals("25% (11.3 L)", com.apexobd.app.ui.formatCalibrationFuelPresetLabel(25, 11.25, "L"))
        assertEquals("50% (22.5 L)", com.apexobd.app.ui.formatCalibrationFuelPresetLabel(50, 22.5, "L"))
        assertEquals("75% (33.8 L)", com.apexobd.app.ui.formatCalibrationFuelPresetLabel(75, 33.75, "L"))
        assertEquals("100% Full (45.0 L)", com.apexobd.app.ui.formatCalibrationFuelPresetLabel(100, 45.0, "L"))
    }

    @Test
    fun `shouldLiftUnitToHeader moves unit to header on compact tiles so XX_XX values stay uncrushed`() {
        assertTrue(shouldLiftUnitToHeader(110f, "km/L"))
        assertTrue(shouldLiftUnitToHeader(150f, "%"))
        assertFalse(shouldLiftUnitToHeader(180f, "km/L"))
        assertFalse(shouldLiftUnitToHeader(110f, ""))
    }
}
