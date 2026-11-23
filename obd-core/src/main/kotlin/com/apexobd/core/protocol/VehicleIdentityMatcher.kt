package com.apexobd.core.protocol

/**
 * Snapshot of the connected ECU's read-only identity emitted once per connection discovery:
 * - [vin]: Mode 09 02 Vehicle Identification Number (when supported by the ECU, e.g. 2024 Avanza W100)
 * - [mode01Pids]: Discovered standard Mode 01 PID hex codes (e.g. "04", "05", "0C", "0D", ...)
 * - [allCapabilities]: Full set including OEM tokens (`7C0_2129`, `7C0_21E1`, `7E0_2131`, ...)
 */
data class EcuIdentitySnapshot(
    val vin: String? = null,
    val mode01Pids: Set<String> = emptySet(),
    val allCapabilities: Set<String> = emptySet()
)

data class VehicleIdentityCandidate(
    val id: Long,
    val name: String,
    val vin: String?,
    val savedPids: Set<String>
)

data class VehicleMatchResult(
    val matchedVehicleId: Long,
    val reason: MatchReason
) {
    enum class MatchReason { VIN, PID_FINGERPRINT }
}

/**
 * Matches a connected OBD-II ECU against saved vehicle profiles so telemetry (odometer, fuel level,
 * supported PIDs) is never written into the wrong car profile when switching vehicles.
 */
object VehicleIdentityMatcher {

    private val OEM_PREFIXES = listOf("7C0_", "7E0_")

    fun normalizeVin(raw: String?): String? {
        val cleaned = raw?.trim()?.uppercase()?.filter { it.isLetterOrDigit() } ?: return null
        return cleaned.takeIf { it.length >= 11 }
    }

    fun extractMode01Pids(pids: Set<String>): Set<String> =
        pids.filter { token ->
            token.length == 2 && OEM_PREFIXES.none { token.startsWith(it) }
        }.map { it.uppercase() }.toSet()

    fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    /**
     * Returns a [VehicleMatchResult] when the connected ECU clearly matches a saved vehicle profile.
     * Priority:
     * 1. Exact VIN match (if VIN is reported by ECU and saved on a profile).
     * 2. Mode 01 PID fingerprint match (`Jaccard >= 0.85`, requiring `>= 6` Mode 01 PIDs).
     *    If the currently active vehicle already matches `>= 0.85` (and has no conflicting VIN),
     *    returns the current active vehicle to avoid false switching between identical car models.
     */
    fun matchVehicle(
        identity: EcuIdentitySnapshot,
        candidates: List<VehicleIdentityCandidate>,
        currentActiveId: Long?
    ): VehicleMatchResult? {
        if (candidates.isEmpty()) return null

        val normVin = normalizeVin(identity.vin)
        if (normVin != null) {
            val vinMatch = candidates.firstOrNull { normalizeVin(it.vin) == normVin }
            if (vinMatch != null) {
                return VehicleMatchResult(vinMatch.id, VehicleMatchResult.MatchReason.VIN)
            }
        }

        val liveMode01 = extractMode01Pids(identity.mode01Pids.ifEmpty { identity.allCapabilities })
        if (liveMode01.size < 6) return null

        val scored = candidates.mapNotNull { cand ->
            val savedMode01 = extractMode01Pids(cand.savedPids)
            if (savedMode01.size < 6) return@mapNotNull null
            val candVin = normalizeVin(cand.vin)
            // If both live ECU and candidate have a valid VIN and they disagree, skip this candidate
            if (normVin != null && candVin != null && candVin != normVin) return@mapNotNull null
            cand to jaccardSimilarity(liveMode01, savedMode01)
        }
        if (scored.isEmpty()) return null

        val currentScore = scored.firstOrNull { it.first.id == currentActiveId }?.second ?: 0.0
        if (currentActiveId != null && currentScore >= 0.85) {
            return VehicleMatchResult(currentActiveId, VehicleMatchResult.MatchReason.PID_FINGERPRINT)
        }

        val best = scored.maxByOrNull { it.second } ?: return null
        if (best.second >= 0.85 && (best.first.id == currentActiveId || best.second - currentScore >= 0.20)) {
            return VehicleMatchResult(best.first.id, VehicleMatchResult.MatchReason.PID_FINGERPRINT)
        }
        return null
    }
}
