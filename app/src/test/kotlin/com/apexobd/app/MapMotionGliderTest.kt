package com.apexobd.app

import com.apexobd.app.ui.components.MapMotionGlider
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMotionGliderTest {

    @Test
    fun `glider advances continuously across entire 1000ms gap between 1Hz GPS fixes without stalling`() {
        val startLat = -6.200000
        val startLon = 106.800000
        val glider = MapMotionGlider(startLat, startLon, 0f)

        // Move northbound at 54 km/h (15 m/s) for 60 frames (~1000 ms) on a single 1 Hz fix
        var midLat = startLat
        for (frame in 1..60) {
            glider.step(
                targetLat = startLat,
                targetLon = startLon,
                targetBearingDeg = 0f,
                speedKmh = 54.0,
                fixTimeMs = 1000L,
                dtMs = 16.666
            )
            if (frame == 30) midLat = glider.dispLat
        }
        val endLat = glider.dispLat

        val firstHalfMeters = (midLat - startLat) * 111_320.0
        val secondHalfMeters = (endLat - midLat) * 111_320.0

        // Both halves of the 1-second interval must advance steadily (no second-half stall!)
        assertTrue("First half should advance smoothly, got $firstHalfMeters m", firstHalfMeters > 3.0)
        assertTrue("Second half should keep gliding without stalling, got $secondHalfMeters m", secondHalfMeters > 5.0)
    }

    @Test
    fun `glider bounds extrapolation when GPS signal drops instead of drifting indefinitely`() {
        val startLat = -6.200000
        val startLon = 106.800000
        val glider = MapMotionGlider(startLat, startLon, 0f)

        // Simulate 5 seconds without a new GPS fix
        for (frame in 1..300) {
            glider.step(
                targetLat = startLat,
                targetLon = startLon,
                targetBearingDeg = 0f,
                speedKmh = 54.0,
                fixTimeMs = 1000L,
                dtMs = 16.666
            )
        }
        val driftedMeters = (glider.dispLat - startLat) * 111_320.0
        // 15 m/s * 1.4 s max extrapolation = ~21 m cap (must stay well below 30 m)
        assertTrue("Extrapolation must be bounded, got $driftedMeters m", driftedMeters in 15.0..26.0)
    }
}
