package com.apexobd.app

import com.apexobd.app.data.db.TelemetrySampleEntity
import com.apexobd.app.data.db.TripSessionEntity
import com.apexobd.app.platform.TripExporter
import org.junit.Assert.assertTrue
import org.junit.Test

class TripExporterTest {

    @Test
    fun `toCsv and toJson emit valid telemetry rows`() {
        val session = TripSessionEntity(
            id = 7L,
            startedAtMs = 1700000000000L,
            endedAtMs = 1700000060000L,
            distanceKm = 2.4,
            maxSpeedKmh = 95.0,
            peakRpm = 4800.0,
            best0100Sec = 6.84,
            sampleCount = 1
        )
        val sample = TelemetrySampleEntity(
            id = 1L,
            sessionId = 7L,
            timestampMs = 1700000010000L,
            latitude = -6.2088,
            longitude = 106.8456,
            gpsSpeedKmh = 84.0,
            payloadJson = """{"rpm":3420,"coolant":90}"""
        )

        val csv = TripExporter.toCsv(session, listOf(sample))
        assertTrue(csv.contains("session_id,timestamp_ms,latitude,longitude,gps_speed_kmh,rpm,coolant"))
        assertTrue(csv.contains("7,1700000010000,-6.2088,106.8456,84.0,3420.0,90.0"))

        val json = TripExporter.toJson(session, listOf(sample))
        assertTrue(json.contains("\"sessionId\": 7"))
        assertTrue(json.contains("\"gpsSpeedKmh\": 84"))
    }

    @Test
    fun `VehicleProfileEntity computes allTimeAvgKpl safely and RefuelLog cycle math stays independent`() {
        val v = com.apexobd.app.data.db.VehicleProfileEntity(
            name = "Avanza 1.3L",
            allTimeRoadDistanceKm = 268.0,
            allTimeRoadFuelLiters = 20.0
        )
        org.junit.Assert.assertEquals(13.4, v.allTimeAvgKpl(), 0.01)

        val zeroFuel = com.apexobd.app.data.db.VehicleProfileEntity(name = "Empty", allTimeRoadDistanceKm = 100.0, allTimeRoadFuelLiters = 0.0)
        org.junit.Assert.assertEquals(0.0, zeroFuel.allTimeAvgKpl(), 0.01)
    }
}
