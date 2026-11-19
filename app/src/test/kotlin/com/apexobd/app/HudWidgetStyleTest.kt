package com.apexobd.app

import androidx.compose.ui.graphics.Color
import com.apexobd.app.data.prefs.HudThemePreset
import com.apexobd.app.ui.components.formatTelemetryValue
import com.apexobd.app.ui.components.segmentedBoxFillFraction
import com.apexobd.app.ui.components.segmentedSpectrumColor
import com.apexobd.app.ui.theme.resolveHudVisualPalette
import org.junit.Assert.assertEquals
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
}
