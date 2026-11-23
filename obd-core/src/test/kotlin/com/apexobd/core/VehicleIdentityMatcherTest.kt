package com.apexobd.core

import com.apexobd.core.protocol.EcuIdentitySnapshot
import com.apexobd.core.protocol.VehicleIdentityCandidate
import com.apexobd.core.protocol.VehicleIdentityMatcher
import com.apexobd.core.protocol.VehicleMatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleIdentityMatcherTest {

    private val k3vePids = setOf(
        "04", "05", "06", "07", "0B", "0C", "0D", "0E", "0F", "10", "11", "14",
        "20", "2E", "33", "40", "42", "45", "60", "7E0_2131", "80", "A0", "C0", "E0"
    )

    private val w100Pids = setOf(
        "01", "03", "04", "05", "06", "07", "0C", "0D", "0E", "0F", "11", "13",
        "15", "1C", "1F", "20", "21", "24", "2E", "30", "31", "33", "34", "3C",
        "3E", "40", "42", "44", "45", "46", "47", "49", "4A", "4C", "4D", "4E",
        "51", "7C0_2121", "7C0_2122", "7C0_2123", "7C0_2129", "7C0_21E1"
    )

    @Test
    fun `matches 2024 2NR-VE by Mode 01 PID fingerprint when K3-VE is mistakenly active`() {
        val candidates = listOf(
            VehicleIdentityCandidate(id = 5L, name = "Avanza K3VE", vin = null, savedPids = k3vePids),
            VehicleIdentityCandidate(id = 6L, name = "BAM", vin = null, savedPids = w100Pids)
        )
        val live2nr = EcuIdentitySnapshot(
            vin = null,
            mode01Pids = VehicleIdentityMatcher.extractMode01Pids(w100Pids),
            allCapabilities = w100Pids
        )
        val match = VehicleIdentityMatcher.matchVehicle(live2nr, candidates, currentActiveId = 5L)
        assertNotNull(match)
        assertEquals(6L, match!!.matchedVehicleId)
        assertEquals(VehicleMatchResult.MatchReason.PID_FINGERPRINT, match.reason)
    }

    @Test
    fun `matches 2014 K3-VE by Mode 01 PID fingerprint when BAM is mistakenly active`() {
        val candidates = listOf(
            VehicleIdentityCandidate(id = 5L, name = "Avanza K3VE", vin = null, savedPids = k3vePids),
            VehicleIdentityCandidate(id = 6L, name = "BAM", vin = null, savedPids = w100Pids)
        )
        val liveK3ve = EcuIdentitySnapshot(
            vin = null,
            mode01Pids = VehicleIdentityMatcher.extractMode01Pids(k3vePids),
            allCapabilities = k3vePids
        )
        val match = VehicleIdentityMatcher.matchVehicle(liveK3ve, candidates, currentActiveId = 6L)
        assertNotNull(match)
        assertEquals(5L, match!!.matchedVehicleId)
        assertEquals(VehicleMatchResult.MatchReason.PID_FINGERPRINT, match.reason)
    }

    @Test
    fun `exact VIN match overrides PID similarity even between two identical car models`() {
        val candidates = listOf(
            VehicleIdentityCandidate(id = 1L, name = "Avanza White", vin = "MHKAA1BA1RK000111", savedPids = w100Pids),
            VehicleIdentityCandidate(id = 2L, name = "Avanza Black", vin = "MHKAA1BA1RK000222", savedPids = w100Pids)
        )
        val live = EcuIdentitySnapshot(
            vin = "mhkaa1ba1rk000222",
            mode01Pids = VehicleIdentityMatcher.extractMode01Pids(w100Pids),
            allCapabilities = w100Pids
        )
        val match = VehicleIdentityMatcher.matchVehicle(live, candidates, currentActiveId = 1L)
        assertNotNull(match)
        assertEquals(2L, match!!.matchedVehicleId)
        assertEquals(VehicleMatchResult.MatchReason.VIN, match.reason)
    }

    @Test
    fun `returns null when no saved vehicle has enough PID history`() {
        val candidates = listOf(
            VehicleIdentityCandidate(id = 1L, name = "Brand New", vin = null, savedPids = emptySet())
        )
        val live = EcuIdentitySnapshot(
            vin = null,
            mode01Pids = VehicleIdentityMatcher.extractMode01Pids(k3vePids),
            allCapabilities = k3vePids
        )
        assertNull(VehicleIdentityMatcher.matchVehicle(live, candidates, currentActiveId = 1L))
    }
}
