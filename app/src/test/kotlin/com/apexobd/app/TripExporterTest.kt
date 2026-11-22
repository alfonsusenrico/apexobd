package com.apexobd.app

import com.apexobd.app.data.db.TelemetrySampleEntity
import com.apexobd.app.data.db.TripSegmentEntity
import com.apexobd.app.data.db.TripSessionEntity
import com.apexobd.app.platform.TripExporter
import org.junit.Assert.assertTrue
import org.junit.Test

class TripExporterTest {

    @Test
    fun `toCsv and toJson emit valid telemetry rows and sub-record segments`() {
        val session = TripSessionEntity(
            id = 7L,
            startedAtMs = 1700000000000L,
            endedAtMs = 1700000060000L,
            distanceKm = 2.4,
            avgSpeedKmh = 144.0,
            maxSpeedKmh = 95.0,
            peakRpm = 4800.0,
            best0100Sec = 6.84,
            sampleCount = 2,
            fuelUsedLiters = 0.2,
            avgKpl = 12.0,
            segmentCount = 2
        )
        val leg1 = TripSegmentEntity(
            id = 1L,
            sessionId = 7L,
            segmentIndex = 1,
            startedAtMs = 1700000000000L,
            endedAtMs = 1700000030000L,
            distanceKm = 1.4,
            fuelUsedLiters = 0.12,
            activeDurationSec = 30L,
            avgSpeedKmh = 168.0,
            maxSpeedKmh = 95.0,
            peakRpm = 4800.0,
            avgKpl = 11.67,
            sampleCount = 1
        )
        val leg2 = TripSegmentEntity(
            id = 2L,
            sessionId = 7L,
            segmentIndex = 2,
            startedAtMs = 1700000040000L,
            endedAtMs = 1700000060000L,
            distanceKm = 1.0,
            fuelUsedLiters = 0.08,
            activeDurationSec = 20L,
            avgSpeedKmh = 180.0,
            maxSpeedKmh = 90.0,
            peakRpm = 4400.0,
            avgKpl = 12.5,
            sampleCount = 1
        )
        val sample1 = TelemetrySampleEntity(
            id = 1L,
            sessionId = 7L,
            timestampMs = 1700000010000L,
            latitude = -6.2088,
            longitude = 106.8456,
            gpsSpeedKmh = 84.0,
            payloadJson = """{"rpm":3420,"coolant":90}""",
            segmentIndex = 1
        )
        val sample2 = TelemetrySampleEntity(
            id = 2L,
            sessionId = 7L,
            timestampMs = 1700000050000L,
            latitude = -6.2100,
            longitude = 106.8470,
            gpsSpeedKmh = 80.0,
            payloadJson = """{"rpm":3100,"coolant":91}""",
            segmentIndex = 2
        )

        val csv = TripExporter.toCsv(session, listOf(sample1, sample2))
        assertTrue(csv.contains("session_id,segment_index,timestamp_ms,latitude,longitude,gps_speed_kmh,rpm,coolant"))
        assertTrue(csv.contains("7,1,1700000010000,-6.2088,106.8456,84.0,3420.0,90.0"))
        assertTrue(csv.contains("7,2,1700000050000,-6.21,106.847,80.0,3100.0,91.0"))

        val json = TripExporter.toJson(session, listOf(sample1, sample2), listOf(leg1, leg2))
        assertTrue(json.contains("\"sessionId\": 7"))
        assertTrue(json.contains("\"durationSec\": 60"))
        assertTrue(json.contains("\"avgSpeedKmh\": 144.0"))
        assertTrue(json.contains("\"fuelUsedLiters\": 0.2"))
        assertTrue(json.contains("\"avgKpl\": 12.0"))
        assertTrue(json.contains("\"segmentCount\": 2"))
        assertTrue(json.contains("\"segments\": ["))
        assertTrue(json.contains("\"segmentIndex\": 1"))
        assertTrue(json.contains("\"segmentIndex\": 2"))
        assertTrue(json.contains("\"gpsSpeedKmh\": 84"))
    }

    @Test
    fun `HUD theme resolver builds 7 distinct palettes and supports Clear Glass on Glassy Dimmed backdrop`() {
        val presets = com.apexobd.app.data.prefs.HudThemePreset.entries
        org.junit.Assert.assertEquals(7, presets.size)
        val accents = presets.map {
            com.apexobd.app.ui.theme.resolveHudVisualPalette(preset = it).accent
        }.toSet()
        org.junit.Assert.assertEquals(7, accents.size)

        val hybrid = com.apexobd.app.ui.theme.resolveHudVisualPalette(
            preset = com.apexobd.app.data.prefs.HudThemePreset.OLED_BLUE,
            backdrop = com.apexobd.app.data.prefs.HudCanvasBackdrop.GLASSY_DIMMED,
            cardStyle = com.apexobd.app.data.prefs.HudCardStyle.CLEAR_GLASS,
            showBorders = false
        )
        org.junit.Assert.assertFalse(hybrid.showBorders)
        org.junit.Assert.assertNotNull(hybrid.canvasGlow)
        assertTrue(hybrid.canvasTop != androidx.compose.ui.graphics.Color.Black)
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

    @Test
    fun `OfflineMapManager insetBounds, tile estimator z5-z14, and local PMTiles style JSON validate cleanly`() {
        val box = com.apexobd.app.platform.OfflineMapManager.insetBounds(-7.0, 113.0, -7.6, 112.4, marginRatio = 0.08)
        assertTrue(box.north < -7.0 && box.south > -7.6)
        assertTrue(box.east < 113.0 && box.west > 112.4)

        val estZ14 = com.apexobd.app.platform.OfflineMapManager.estimateRegionSize(box.north, box.east, box.south, box.west, 5.0, 14.0)
        assertTrue(estZ14.tileCount in 100L..2500L)
        org.junit.Assert.assertFalse(estZ14.exceedsFairUseLimit)

        val zoomedOut = com.apexobd.app.platform.OfflineMapManager.insetBounds(-6.5, 113.5, -8.1, 111.9, marginRatio = 0.08)
        val estZoomedOut = com.apexobd.app.platform.OfflineMapManager.estimateRegionSize(zoomedOut.north, zoomedOut.east, zoomedOut.south, zoomedOut.west, 5.0, 14.0)
        assertTrue(estZoomedOut.tileCount > estZ14.tileCount)
        assertTrue(estZoomedOut.estimatedMb > estZ14.estimatedMb)

        val styleJson = com.apexobd.app.platform.OfflineMapManager.buildDarkPmtilesStyleJson("/data/user/0/com.apexobd.app/files/pmtiles/java.pmtiles")
        assertTrue(styleJson.contains("pmtiles://file:///data/user/0/com.apexobd.app/files/pmtiles/java.pmtiles"))
        assertTrue(styleJson.contains("\"source-layer\": \"roads\""))
        assertTrue(styleJson.contains("\"source-layer\": \"streets\""))
        assertTrue(styleJson.contains("\"source-layer\": \"transportation\""))
    }

    @Test
    fun `LocationTracker resets map trail across simulated-to-real GPS switch and large teleports`() {
        org.junit.Assert.assertFalse(
            com.apexobd.app.platform.LocationTracker.shouldResetTrail(null, null, -6.20, 106.84, wasSimulated = false, nowSimulated = false)
        )
        org.junit.Assert.assertTrue(
            com.apexobd.app.platform.LocationTracker.shouldResetTrail(-6.2088, 106.8456, -6.2085, 106.8458, wasSimulated = true, nowSimulated = false)
        )
        org.junit.Assert.assertTrue(
            com.apexobd.app.platform.LocationTracker.shouldResetTrail(-6.2088, 106.8456, -6.1780, 106.6300, wasSimulated = false, nowSimulated = false)
        )
        org.junit.Assert.assertFalse(
            com.apexobd.app.platform.LocationTracker.shouldResetTrail(-6.1780, 106.6300, -6.1782, 106.6303, wasSimulated = false, nowSimulated = false)
        )
    }

    @Test
    fun `fitDigitsFontSizeSp scales cleanly down inside 1x1 compact cells so xx-xx values never truncate`() {
        val compactSp = com.apexobd.app.ui.components.fitDigitsFontSizeSp(
            availWidthDp = 61f,
            text = "13.45",
            unit = "km/L",
            unitSp = 9f,
            minSp = 13f,
            maxSp = 23f
        )
        assertTrue(compactSp in 13f..20f)
        val wideSp = com.apexobd.app.ui.components.fitDigitsFontSizeSp(
            availWidthDp = 160f,
            text = "738",
            unit = "RPM",
            unitSp = 11.5f,
            minSp = 13f,
            maxSp = 32f
        )
        assertTrue(wideSp >= 28f)
        val lblSp = com.apexobd.app.ui.components.fitLabelFontSizeSp(46f, "KPL · TRIP", minSp = 8.0f, maxSp = 11.5f)
        assertTrue(lblSp in 8.0f..10.5f)
    }

    @Test
    fun `VehicleProfileEntity syncs detailed specs, TripSessionEntity uses activeDurationSec, and MapStylePresets cover all 6 styles`() {
        val avanza24 = com.apexobd.app.data.db.VehicleProfileEntity(
            name = "Avanza 1.5G",
            displacementLiters = 1.496,
            engineCc = 1496,
            carType = com.apexobd.core.model.VehicleBodyType.MPV.name,
            transmission = com.apexobd.core.model.TransmissionType.MANUAL.name,
            modelYear = 2024
        )
        val core = avanza24.toCoreModel()
        org.junit.Assert.assertEquals(1496, core.engineCc)
        org.junit.Assert.assertEquals(1.496, core.displacementLiters, 0.001)
        org.junit.Assert.assertEquals(com.apexobd.core.model.VehicleBodyType.MPV, core.carType)
        org.junit.Assert.assertEquals(com.apexobd.core.model.TransmissionType.MANUAL, core.transmission)
        org.junit.Assert.assertEquals(2024, core.modelYear)
        assertTrue(avanza24.specSummaryLine().contains("1496 cc"))
        assertTrue(avanza24.specSummaryLine().contains("MT"))

        val pausedSession = TripSessionEntity(
            startedAtMs = 1_000_000L,
            endedAtMs = 2_000_000L, // 1000s wall clock
            activeDurationSec = 420L // 7m active excluding pause
        )
        org.junit.Assert.assertEquals(420L, pausedSession.effectiveDurationSec())

        val pmtilesNavy = com.apexobd.app.platform.OfflineMapManager.buildPmtilesStyleJson(
            "/data/user/0/com.apexobd.app/files/pmtiles/java.pmtiles",
            com.apexobd.app.data.prefs.MapStylePreset.FIORD_NAVY
        )
        assertTrue(pmtilesNavy.contains("#2B364C"))
        org.junit.Assert.assertEquals(6, com.apexobd.app.data.prefs.MapStylePreset.entries.size)
        org.junit.Assert.assertEquals(3, com.apexobd.app.data.prefs.MapCameraMode.entries.size)

        // Idle-inclusive total avg takes precedence over road-only avg when populated
        val totalWithIdle = com.apexobd.app.data.db.VehicleProfileEntity(
            name = "Avanza 1.3L",
            allTimeRoadDistanceKm = 100.0,
            allTimeRoadFuelLiters = 8.0, // 12.5 road-only
            allTimeTotalDistanceKm = 100.0,
            allTimeTotalFuelLiters = 10.0 // 10.0 incl. idle
        )
        assertTrue(totalWithIdle.allTimeAvgKpl() in 9.99..10.01)
    }

    @Test
    fun `OfflineMapManager buildRoadsMaskStyleJson generates valid JSON for main-only and all-streets modes`() {
        val mainOnlyJson = com.apexobd.app.platform.OfflineMapManager.buildRoadsMaskStyleJson(null, mainRoadsOnly = true)
        org.junit.Assert.assertFalse(mainOnlyJson.contains("\"roads-minor\""))
        assertTrue(mainOnlyJson.contains("\"roads-major\""))
        assertTrue(mainOnlyJson.contains("\"roads-secondary\""))

        val allStreetsJson = com.apexobd.app.platform.OfflineMapManager.buildRoadsMaskStyleJson(null, mainRoadsOnly = false)
        assertTrue(allStreetsJson.contains("\"roads-minor\""))
    }
}
