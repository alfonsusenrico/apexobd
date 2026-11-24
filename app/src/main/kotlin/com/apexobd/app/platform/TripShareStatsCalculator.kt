package com.apexobd.app.platform

import com.apexobd.app.data.db.TelemetrySampleEntity
import com.apexobd.app.data.db.TripSessionEntity
import com.apexobd.app.data.prefs.ShareCardMetric
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.UnitConverter
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.protocol.TripRunCalculator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class RenderedShareMetric(
    val metric: ShareCardMetric,
    val label: String,
    val value: String
)

/**
 * Aggregates saved telemetry samples into formatted Share Route Card tiles without Android UI dependencies.
 */
object TripShareStatsCalculator {

    data class AggregatedStats(
        val peakRpm: Double,
        val avgEfficiencyPct: Double?,
        val peakHp: Double?,
        val avgHp: Double?,
        val peakTorqueNm: Double?,
        val movingKpl: Double?,
        val peakCoolantC: Double?,
        val maxThrottlePct: Double?,
        val maxGForce: Double?,
        val avgVoltage: Double?,
        val idleDurationSec: Long
    )

    fun summarize(
        session: TripSessionEntity,
        samples: List<TelemetrySampleEntity>
    ): AggregatedStats = aggregate(session, samples)

    fun computeMetricsFromStats(
        selectedMetrics: List<ShareCardMetric>,
        session: TripSessionEntity,
        stats: AggregatedStats,
        unitPrefs: UnitPreferences,
        fuelType: FuelType
    ): List<RenderedShareMetric> {
        val activeMetrics = selectedMetrics
            .distinct()
            .take(ShareCardMetric.MAX_SELECTED)
            .ifEmpty { ShareCardMetric.DEFAULT_SELECTION }

        return activeMetrics.map { metric ->
            RenderedShareMetric(
                metric = metric,
                label = metric.cardHeader,
                value = formatMetric(metric, session, stats, unitPrefs, fuelType)
            )
        }
    }

    fun computeMetrics(
        selectedMetrics: List<ShareCardMetric>,
        session: TripSessionEntity,
        samples: List<TelemetrySampleEntity>,
        unitPrefs: UnitPreferences,
        fuelType: FuelType
    ): List<RenderedShareMetric> = computeMetricsFromStats(
        selectedMetrics = selectedMetrics,
        session = session,
        stats = aggregate(session, samples),
        unitPrefs = unitPrefs,
        fuelType = fuelType
    )

    private fun aggregate(
        session: TripSessionEntity,
        samples: List<TelemetrySampleEntity>
    ): AggregatedStats {
        var peakRpm = session.peakRpm
        var effSum = 0.0
        var effCount = 0
        var peakHp: Double? = null
        var hpSum = 0.0
        var hpCount = 0
        var peakTorque: Double? = null
        var peakCoolant: Double? = null
        var maxThrottle: Double? = null
        var maxG: Double? = null
        var voltSum = 0.0
        var voltCount = 0
        var lastMovingKpl: Double? = null
        var movingFuelL = 0.0
        var movingTimeSec = 0.0
        var idleTimeSec = 0.0

        for (i in samples.indices) {
            val sample = samples[i]
            val p = TripExporter.decodePayload(sample.payloadJson)

            p["rpm"]?.let { if (it > peakRpm) peakRpm = it }
            p["efficiency_score"]?.takeIf { it in 0.0..100.0 }?.let {
                effSum += it
                effCount++
            }
            p["hp"]?.takeIf { it >= 0.0 }?.let { hp ->
                peakHp = peakHp?.let { max(it, hp) } ?: hp
                hpSum += hp
                hpCount++
            }
            p["torque"]?.takeIf { it >= 0.0 }?.let { trq ->
                peakTorque = peakTorque?.let { max(it, trq) } ?: trq
            }
            p["coolant"]?.let { c ->
                peakCoolant = peakCoolant?.let { max(it, c) } ?: c
            }
            (p["rel_throttle"] ?: p["throttle"])?.takeIf { it in 0.0..100.0 }?.let { thr ->
                maxThrottle = maxThrottle?.let { max(it, thr) } ?: thr
            }
            p["accel_g"]?.let { g ->
                val ag = abs(g)
                maxG = maxG?.let { max(it, ag) } ?: ag
            }
            p["voltage"]?.takeIf { it > 1.0 }?.let { v ->
                voltSum += v
                voltCount++
            }
            p["kpl_moving_avg"]?.takeIf { it > 0.1 }?.let { lastMovingKpl = it }

            if (i > 0) {
                val dtSec = ((sample.timestampMs - samples[i - 1].timestampMs) / 1000.0)
                if (dtSec in 0.05..15.0) {
                    val speedKmh = p["obd_speed"] ?: sample.gpsSpeedKmh ?: 0.0
                    if (speedKmh >= 2.0) {
                        movingTimeSec += dtSec
                        val flow = p["fuel_flow_lph"]
                        if (flow != null && flow >= 0.0) {
                            movingFuelL += (flow / 3600.0) * dtSec
                        }
                    } else {
                        idleTimeSec += dtSec
                    }
                }
            }
        }

        val computedMovingKpl = when {
            movingFuelL > 0.002 && session.distanceKm > 0.05 -> (session.distanceKm / movingFuelL).coerceIn(0.5, 99.99)
            lastMovingKpl != null -> lastMovingKpl
            session.avgKpl > 0.05 && movingTimeSec > 0.0 -> session.avgKpl
            else -> null
        }

        return AggregatedStats(
            peakRpm = peakRpm,
            avgEfficiencyPct = if (effCount > 0) effSum / effCount else null,
            peakHp = peakHp,
            avgHp = if (hpCount > 0) hpSum / hpCount else null,
            peakTorqueNm = peakTorque,
            movingKpl = computedMovingKpl,
            peakCoolantC = peakCoolant,
            maxThrottlePct = maxThrottle,
            maxGForce = maxG,
            avgVoltage = if (voltCount > 0) voltSum / voltCount else null,
            idleDurationSec = idleTimeSec.roundToInt().toLong().coerceIn(0L, session.effectiveDurationSec())
        )
    }

    private fun formatMetric(
        metric: ShareCardMetric,
        session: TripSessionEntity,
        stats: AggregatedStats,
        unitPrefs: UnitPreferences,
        fuelType: FuelType
    ): String = when (metric) {
        ShareCardMetric.DISTANCE -> {
            val (dVal, dSym) = UnitConverter.convertDistanceKm(session.distanceKm, unitPrefs)
            "%.1f %s".format(dVal, dSym)
        }
        ShareCardMetric.TRIP_TIME -> TripRunCalculator.formatDurationClock(session.effectiveDurationSec())
        ShareCardMetric.AVG_ECONOMY -> {
            val (kVal, kSym) = UnitConverter.convertFuelEconomy(session.avgKpl, unitPrefs, fuelType)
            if (session.avgKpl > 0.05) "%.1f %s".format(kVal, kSym) else "— %s".format(kSym)
        }
        ShareCardMetric.AVG_TOP_SPEED -> {
            val (avgSpd, spdSym) = UnitConverter.convert(
                session.effectiveAvgSpeedKmh(),
                SensorQuantity.SPEED,
                unitPrefs,
                fuelType
            )
            val (maxSpd, _) = UnitConverter.convert(
                session.maxSpeedKmh,
                SensorQuantity.SPEED,
                unitPrefs,
                fuelType
            )
            "%.0f / %.0f %s".format(avgSpd, maxSpd, spdSym)
        }
        ShareCardMetric.PEAK_RPM -> "%.0f RPM".format(stats.peakRpm)
        ShareCardMetric.TRIP_EFFICIENCY -> stats.avgEfficiencyPct?.let { "${it.roundToInt()}%" } ?: "—"
        ShareCardMetric.PEAK_AVG_HP -> {
            val pk = stats.peakHp
            val avg = stats.avgHp
            if (pk != null && avg != null) {
                val (pkConv, sym) = UnitConverter.convert(pk, SensorQuantity.POWER_HP, unitPrefs, fuelType)
                val (avgConv, _) = UnitConverter.convert(avg, SensorQuantity.POWER_HP, unitPrefs, fuelType)
                "%.0f / %.0f %s".format(pkConv, avgConv, sym)
            } else "—"
        }
        ShareCardMetric.PEAK_TORQUE -> stats.peakTorqueNm?.let { trq ->
            val (conv, sym) = UnitConverter.convert(trq, SensorQuantity.TORQUE_NM, unitPrefs, fuelType)
            "%.0f %s".format(conv, sym)
        } ?: "—"
        ShareCardMetric.FUEL_USED -> {
            val (fVal, fSym) = UnitConverter.convertVolume(session.fuelUsedLiters, unitPrefs, fuelType)
            "%.2f %s".format(fVal, fSym)
        }
        ShareCardMetric.MOVING_KPL -> stats.movingKpl?.let { mk ->
            val (valConv, sym) = UnitConverter.convertFuelEconomy(mk, unitPrefs, fuelType)
            "%.1f %s".format(valConv, sym)
        } ?: "—"
        ShareCardMetric.COOLANT_PEAK -> stats.peakCoolantC?.let { c ->
            val (conv, sym) = UnitConverter.convert(c, SensorQuantity.TEMPERATURE, unitPrefs, fuelType)
            "%.0f %s".format(conv, sym)
        } ?: "—"
        ShareCardMetric.MAX_THROTTLE -> stats.maxThrottlePct?.let { "${it.roundToInt()}%" } ?: "—"
        ShareCardMetric.MAX_G_FORCE -> stats.maxGForce?.let { "%.2f g".format(it) } ?: "—"
        ShareCardMetric.AVG_VOLTAGE -> stats.avgVoltage?.let { "%.1f V".format(it) } ?: "—"
        ShareCardMetric.IDLE_TIME -> TripRunCalculator.formatDurationClock(stats.idleDurationSec)
    }
}
