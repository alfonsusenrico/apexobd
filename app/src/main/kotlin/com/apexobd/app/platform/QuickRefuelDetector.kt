package com.apexobd.app.platform

import kotlin.math.max

data class DetectedRefuelJump(
    val vehicleId: Long,
    val beforeLiters: Double,
    val afterLiters: Double,
    val addedLiters: Double,
    val odometerKm: Double,
    val tankCapacityLiters: Double,
    val detectedAtMs: Long
)

object QuickRefuelDetector {

    private const val COOLDOWN_MS = 10L * 60L * 1000L

    /**
     * Detects a genuine refuel jump from native fuel level sensor readings while ignoring
     * hill-parking slosh or normal gauge damping noise.
     */
    fun detectFuelLevelJump(
        vehicleId: Long,
        beforeLiters: Double,
        afterLiters: Double,
        tankCapacityLiters: Double,
        odometerKm: Double,
        nowMs: Long,
        lastPromptedAtMs: Long?
    ): DetectedRefuelJump? {
        if (tankCapacityLiters < 10.0) return null
        if (!beforeLiters.isFinite() || !afterLiters.isFinite()) return null
        if (beforeLiters < 0.0 || afterLiters > tankCapacityLiters + 0.5) return null
        if (lastPromptedAtMs != null && (nowMs - lastPromptedAtMs) < COOLDOWN_MS) return null

        val delta = afterLiters - beforeLiters
        val threshold = max(4.5, tankCapacityLiters * 0.11)
        if (delta < threshold) return null

        return DetectedRefuelJump(
            vehicleId = vehicleId,
            beforeLiters = beforeLiters,
            afterLiters = afterLiters,
            addedLiters = delta.coerceIn(1.0, tankCapacityLiters),
            odometerKm = odometerKm.coerceAtLeast(0.0),
            tankCapacityLiters = tankCapacityLiters,
            detectedAtMs = nowMs
        )
    }

    /**
     * Estimates added refuel volume in liters for a 1-tap draft capture.
     * Prefers native fuel-sensor delta when available, then OBD fuel burned since last full tank,
     * then remaining tank headroom.
     */
    fun estimateQuickRefuelLiters(
        hasNativeFuelSensor: Boolean,
        beforeFuelLiters: Double?,
        currentFuelLiters: Double?,
        obdFuelUsedSinceFullLiters: Double,
        tankCapacityLiters: Double
    ): Double {
        val cap = tankCapacityLiters.coerceAtLeast(1.0)
        if (hasNativeFuelSensor && beforeFuelLiters != null && currentFuelLiters != null) {
            val delta = currentFuelLiters - beforeFuelLiters
            if (delta >= 1.5) {
                return delta.coerceIn(1.0, cap)
            }
        }
        if (obdFuelUsedSinceFullLiters.isFinite() && obdFuelUsedSinceFullLiters >= 1.0) {
            return obdFuelUsedSinceFullLiters.coerceIn(1.0, cap)
        }
        if (currentFuelLiters != null && currentFuelLiters.isFinite() && (cap - currentFuelLiters) >= 1.5) {
            return (cap - currentFuelLiters).coerceIn(1.0, cap)
        }
        return 0.0
    }
}
