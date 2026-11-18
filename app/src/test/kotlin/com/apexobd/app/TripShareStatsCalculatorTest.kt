package com.apexobd.app

import com.apexobd.app.data.db.TelemetrySampleEntity
import com.apexobd.app.data.db.TripSessionEntity
import com.apexobd.app.data.prefs.MapCursorStyle
import com.apexobd.app.data.prefs.ShareCardBackgroundMode
import com.apexobd.app.data.prefs.ShareCardMetric
import com.apexobd.app.data.prefs.TripCardConfig
import com.apexobd.app.platform.TripShareStatsCalculator
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.UnitPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripShareStatsCalculatorTest {

    @Test
    fun `legacy run with avgSpeedKmh=0 derives average speed and renders efficiency as percentage`() {
        val legacySession = TripSessionEntity(
            id = 2L,
            startedAtMs = 1_000_000L,
            endedAtMs = 3_279_000L, // 2279 s = 37:59
            distanceKm = 11.247,
            avgSpeedKmh = 0.0,
            maxSpeedKmh = 47.7,
            peakRpm = 2641.0,
            fuelUsedLiters = 0.93,
            avgKpl = 12.1
        )
        assertEquals(17.766, legacySession.effectiveAvgSpeedKmh(), 0.05)

        val samples = listOf(
            TelemetrySampleEntity(
                id = 1L,
                sessionId = 2L,
                timestampMs = 1_001_000L,
                latitude = -6.20,
                longitude = 106.84,
                gpsSpeedKmh = 20.0,
                payloadJson = """{"efficiency_score":66.0,"hp":15.0,"torque":55.0,"obd_speed":20.0,"fuel_flow_lph":2.0}"""
            ),
            TelemetrySampleEntity(
                id = 2L,
                sessionId = 2L,
                timestampMs = 1_002_000L,
                latitude = -6.201,
                longitude = 106.841,
                gpsSpeedKmh = 35.0,
                payloadJson = """{"efficiency_score":70.0,"hp":22.0,"torque":85.0,"obd_speed":35.0,"fuel_flow_lph":2.4}"""
            )
        )

        val rendered = TripShareStatsCalculator.computeMetrics(
            selectedMetrics = ShareCardMetric.DEFAULT_SELECTION,
            session = legacySession,
            samples = samples,
            unitPrefs = UnitPreferences(),
            fuelType = FuelType.GASOLINE
        )

        assertEquals(6, rendered.size)
        val byMetric = rendered.associateBy { it.metric }
        assertEquals("11.2 km", byMetric[ShareCardMetric.DISTANCE]?.value)
        assertEquals("37:59", byMetric[ShareCardMetric.TRIP_TIME]?.value)
        assertEquals("12.1 km/L", byMetric[ShareCardMetric.AVG_ECONOMY]?.value)
        assertEquals("AVG / TOP SPEED", byMetric[ShareCardMetric.AVG_TOP_SPEED]?.label)
        assertEquals("18 / 48 km/h", byMetric[ShareCardMetric.AVG_TOP_SPEED]?.value)
        assertEquals("2641 RPM", byMetric[ShareCardMetric.PEAK_RPM]?.value)
        assertEquals("68%", byMetric[ShareCardMetric.TRIP_EFFICIENCY]?.value)
    }

    @Test
    fun `custom ShareCardMetric picker enforces max 6 limit and calculates optional telemetry stats`() {
        val rawTooMany = "PEAK_AVG_HP,PEAK_TORQUE,COOLANT_PEAK,MAX_THROTTLE,MAX_G_FORCE,AVG_VOLTAGE,IDLE_TIME,PEAK_TORQUE"
        val parsed = ShareCardMetric.parseStored(rawTooMany)
        assertEquals(6, parsed.size)
        assertEquals(
            listOf(
                ShareCardMetric.PEAK_AVG_HP,
                ShareCardMetric.PEAK_TORQUE,
                ShareCardMetric.COOLANT_PEAK,
                ShareCardMetric.MAX_THROTTLE,
                ShareCardMetric.MAX_G_FORCE,
                ShareCardMetric.AVG_VOLTAGE
            ),
            parsed
        )
        assertEquals(ShareCardMetric.DEFAULT_SELECTION, ShareCardMetric.parseStored("INVALID_TOKEN"))
        assertEquals(3, MapCursorStyle.entries.size)

        val session = TripSessionEntity(
            id = 8L,
            startedAtMs = 1_000_000L,
            endedAtMs = 1_020_000L,
            distanceKm = 0.25,
            avgSpeedKmh = 30.0,
            maxSpeedKmh = 60.0,
            peakRpm = 4200.0,
            fuelUsedLiters = 0.04,
            avgKpl = 6.25
        )
        val samples = listOf(
            TelemetrySampleEntity(
                sessionId = 8L,
                timestampMs = 1_000_000L,
                latitude = null,
                longitude = null,
                gpsSpeedKmh = 0.0,
                payloadJson = """{"obd_speed":0.0,"hp":10.0,"torque":40.0,"coolant":88.0,"rel_throttle":18.0,"accel_g":-0.12,"voltage":14.0,"fuel_flow_lph":0.9}"""
            ),
            TelemetrySampleEntity(
                sessionId = 8L,
                timestampMs = 1_005_000L,
                latitude = null,
                longitude = null,
                gpsSpeedKmh = 0.0,
                payloadJson = """{"obd_speed":0.0,"hp":12.0,"torque":42.0,"coolant":89.0,"rel_throttle":20.0,"accel_g":0.15,"voltage":14.2,"fuel_flow_lph":0.9}"""
            ),
            TelemetrySampleEntity(
                sessionId = 8L,
                timestampMs = 1_015_000L,
                latitude = null,
                longitude = null,
                gpsSpeedKmh = 50.0,
                payloadJson = """{"obd_speed":50.0,"hp":38.0,"torque":128.0,"coolant":92.0,"rel_throttle":74.0,"accel_g":0.44,"voltage":14.1,"fuel_flow_lph":7.2}"""
            )
        )

        val rendered = TripShareStatsCalculator.computeMetrics(
            selectedMetrics = parsed,
            session = session,
            samples = samples,
            unitPrefs = UnitPreferences(),
            fuelType = FuelType.GASOLINE
        ).associateBy { it.metric }

        assertEquals("38 / 20 HP", rendered[ShareCardMetric.PEAK_AVG_HP]?.value)
        assertEquals("128 Nm", rendered[ShareCardMetric.PEAK_TORQUE]?.value)
        assertEquals("92 °C", rendered[ShareCardMetric.COOLANT_PEAK]?.value)
        assertEquals("74%", rendered[ShareCardMetric.MAX_THROTTLE]?.value)
        assertEquals("0.44 g", rendered[ShareCardMetric.MAX_G_FORCE]?.value)
        assertEquals("14.1 V", rendered[ShareCardMetric.AVG_VOLTAGE]?.value)

        val movingAndIdle = TripShareStatsCalculator.computeMetrics(
            selectedMetrics = listOf(ShareCardMetric.MOVING_KPL, ShareCardMetric.IDLE_TIME, ShareCardMetric.FUEL_USED),
            session = session,
            samples = samples,
            unitPrefs = UnitPreferences(),
            fuelType = FuelType.GASOLINE
        ).associateBy { it.metric }
        assertTrue(movingAndIdle[ShareCardMetric.MOVING_KPL]?.value?.endsWith("km/L") == true)
        assertEquals("00:05", movingAndIdle[ShareCardMetric.IDLE_TIME]?.value)
        assertEquals("0.04 L", movingAndIdle[ShareCardMetric.FUEL_USED]?.value)

        val defaultConfig = TripCardConfig()
        assertEquals(ShareCardBackgroundMode.CAR_PHOTO, defaultConfig.backgroundMode)
        assertTrue(defaultConfig.showAppMark && defaultConfig.showVehicleName && defaultConfig.showDate && defaultConfig.showTime)
        assertTrue(defaultConfig.showRouteLine && defaultConfig.showStartEndMarkers && defaultConfig.showMetricsPanelBg && defaultConfig.showMetrics)

        val transparentCustom = defaultConfig.copy(
            backgroundMode = ShareCardBackgroundMode.TRANSPARENT,
            showAppMark = false,
            showDate = false,
            showMetricsPanelBg = false
        )
        assertEquals(ShareCardBackgroundMode.TRANSPARENT, transparentCustom.backgroundMode)
        assertEquals(false, transparentCustom.showAppMark)
        assertEquals(false, transparentCustom.showDate)
        assertEquals(false, transparentCustom.showMetricsPanelBg)

        val summary = TripShareStatsCalculator.summarize(session, samples)
        val fromSummary = TripShareStatsCalculator.computeMetricsFromStats(
            selectedMetrics = parsed,
            session = session,
            stats = summary,
            unitPrefs = UnitPreferences(),
            fuelType = FuelType.GASOLINE
        ).associateBy { it.metric }
        assertEquals(rendered[ShareCardMetric.PEAK_AVG_HP]?.value, fromSummary[ShareCardMetric.PEAK_AVG_HP]?.value)
        assertEquals(rendered[ShareCardMetric.MAX_G_FORCE]?.value, fromSummary[ShareCardMetric.MAX_G_FORCE]?.value)
    }
}
