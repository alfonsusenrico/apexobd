package com.apexobd.app

import com.apexobd.app.platform.QuickRefuelDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuickRefuelDetectorTest {

    @Test
    fun `detectFuelLevelJump ignores minor slosh and triggers on real refuel jump`() {
        // +2.5 L slosh on a 43 L tank (threshold 4.73 L) must NOT trigger
        val slosh = QuickRefuelDetector.detectFuelLevelJump(
            vehicleId = 1L,
            beforeLiters = 18.0,
            afterLiters = 20.5,
            tankCapacityLiters = 43.0,
            odometerKm = 12450.0,
            nowMs = 1_000_000L,
            lastPromptedAtMs = null
        )
        assertNull(slosh)

        // +24.0 L fill-up triggers cleanly
        val refuel = QuickRefuelDetector.detectFuelLevelJump(
            vehicleId = 1L,
            beforeLiters = 18.0,
            afterLiters = 42.0,
            tankCapacityLiters = 43.0,
            odometerKm = 12450.0,
            nowMs = 1_000_000L,
            lastPromptedAtMs = null
        )
        assertNotNull(refuel)
        assertEquals(24.0, refuel!!.addedLiters, 0.01)

        // Cooldown blocks repeat prompt within 10 minutes
        val blocked = QuickRefuelDetector.detectFuelLevelJump(
            vehicleId = 1L,
            beforeLiters = 18.0,
            afterLiters = 42.0,
            tankCapacityLiters = 43.0,
            odometerKm = 12451.0,
            nowMs = 1_000_000L + 5 * 60_000L,
            lastPromptedAtMs = 1_000_000L
        )
        assertNull(blocked)
    }

    @Test
    fun `estimateQuickRefuelLiters prefers sensor delta then OBD burned then tank headroom`() {
        // Native sensor car with jump: uses after - before
        val fromSensor = QuickRefuelDetector.estimateQuickRefuelLiters(
            hasNativeFuelSensor = true,
            beforeFuelLiters = 14.0,
            currentFuelLiters = 41.5,
            obdFuelUsedSinceFullLiters = 30.0,
            tankCapacityLiters = 43.0
        )
        assertEquals(27.5, fromSensor, 0.01)

        // Non-sensor car (e.g. Avanza K3VE): uses OBD fuel used since last full tank
        val fromObd = QuickRefuelDetector.estimateQuickRefuelLiters(
            hasNativeFuelSensor = false,
            beforeFuelLiters = null,
            currentFuelLiters = 12.0,
            obdFuelUsedSinceFullLiters = 31.2,
            tankCapacityLiters = 45.0
        )
        assertEquals(31.2, fromObd, 0.01)

        // Fresh profile with no OBD burned yet: uses tank headroom
        val fromHeadroom = QuickRefuelDetector.estimateQuickRefuelLiters(
            hasNativeFuelSensor = false,
            beforeFuelLiters = null,
            currentFuelLiters = 20.0,
            obdFuelUsedSinceFullLiters = 0.0,
            tankCapacityLiters = 45.0
        )
        assertEquals(25.0, fromHeadroom, 0.01)
    }
}
