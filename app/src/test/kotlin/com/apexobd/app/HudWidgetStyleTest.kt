package com.apexobd.app

import androidx.compose.ui.graphics.Color
import com.apexobd.app.data.prefs.HudThemePreset
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
}
