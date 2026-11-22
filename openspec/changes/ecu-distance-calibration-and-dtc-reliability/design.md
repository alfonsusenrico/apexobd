# Design: ECU Distance Baseline Calibration (0131/2131), Full-to-Full Refuel Cycle KPL, and Multi-ECU DTC Reliability

## Context
Real-world road testing on two vehicles exposed three concrete requirements:
1. **2014 Toyota Avanza K3-VE (`F650`):** Has no CAN instrument cluster (`7C0 -> NO DATA`) and no Mode 01 `012F` fuel level PID, so hardware `fuel_level` and `ecu_odometer` are unavailable. However, its Engine ECM (`7E0`) supports `7E0 2131` (`Distance Traveled Since Codes Cleared`, 16-bit unsigned integer km `0..65535`) which increments every kilometer even when the phone is not in the car.
2. **Full-to-Full Refuel Cycle KPL:** Partial refuels cannot measure tank depletion accurately, whereas full-tank refuels (including any intermediate partial top-ups since the last full tank) give an exact physical KPL (`ΔOdometer / TotalFuelAddedSinceLastFull`) that should govern virtual tank depletion and DTE range for the next cycle while keeping real-time OBD All-Time KPL and Trip KPL untouched.
3. **DTC Scan & Clear Reliability:** On the 2014 Avanza K3-VE, `0101` is unsupported in Mode 01 (readiness lives at `7E0 2106`), `03` returns `NO DATA` when 0 confirmed codes exist, and `07` returns `47 00`. Previously, `scanDiagnostics()` treated `confirmed.isEmpty() && pending.isEmpty() && permanent.isEmpty() && readiness == null` as an offline/non-responding ECU.

## Decisions

### Decision 1: ECU Distance Counter Discovery & Polling (`0131` and `7E0 2131`)
- During connection discovery in `ObdPollingEngine`:
  - If `"31"` is in `discovered` Mode 01 PIDs, read `01311` (`dist_since_clear`) immediately.
  - Otherwise, if `handshake.isCanProtocol`, probe `7E0 2131` (`ATSH7E0`, `ATCRA7E8`, `2131`, then `restoreDefaultCanHeaderAndFilter`). If `61 31 <A> <B>` responds with 2 bytes, add capability token `7E0_2131` and decode `((A shl 8) or B).toDouble()`.
- Expose `ecuDistanceSinceClearKm: StateFlow<Double?>` from `ObdPollingEngine` and refresh it on connect and every 60 seconds during active polling.
- *Rejected alternative:* Relying only on GPS distance while the app is open. Rejected because trips taken by other drivers without the phone connected are missed completely.

### Decision 2: One-Time Baseline Calibration & Automatic Unlogged Distance Catch-Up
- Add 4 nullable columns to `vehicle_profiles` (Room `MIGRATION_8_9`):
  - `ecuDistanceBaselineKm: Double?` (`E₀`)
  - `odometerAtBaselineKm: Double?` (`O₀`)
  - `lastSeenEcuDistanceKm: Double?` (`E_last`)
  - `fullRefuelCycleKpl: Double?` (`KPL_cycle`)
- When the user calibrates the baseline while connected (`E_now != null`):
  - `ecuDistanceBaselineKm = E_now`
  - `odometerAtBaselineKm = confirmedOdometerKm`
  - `lastSeenEcuDistanceKm = E_now`
  - `allTimeOdometerKm = confirmedOdometerKm`
  - `estimatedRemainingFuelLiters = confirmedFuelLiters.coerceIn(0.0, tankCapacityLiters)`
- On every subsequent `ecuDistanceSinceClearKm` emission (`E_now`):
  - If the vehicle has native cluster odometer (`7C0_21E1`) or native fuel sensor (`7C0_2129` / `2F`), native sensors remain authoritative.
  - Otherwise, if `ecuDistanceBaselineKm != null && odometerAtBaselineKm != null`:
    - **Rollover / Reset Guard:** If `lastSeenEcuDistanceKm != null && E_now < lastSeenEcuDistanceKm - 0.5` (e.g. Mode 04 DTC clear or battery reset reset `2131` to `0`), re-anchor `odometerAtBaselineKm = current.allTimeOdometerKm`, `ecuDistanceBaselineKm = E_now`, `lastSeenEcuDistanceKm = E_now`.
    - **Normal Advance:**
      - `targetOdoKm = odometerAtBaselineKm + (E_now - ecuDistanceBaselineKm).coerceAtLeast(0.0)`
      - Calculate `unloggedKm = (targetOdoKm - current.allTimeOdometerKm)`
      - If `unloggedKm >= 0.8`:
        - Determine economy rate for catch-up: `catchUpKpl = current.fullRefuelCycleKpl?.takeIf { it > 1.0 } ?: current.allTimeAvgKpl().takeIf { it > 1.0 } ?: current.recentDteKpl?.takeIf { it > 1.0 } ?: 11.5`
        - Deduct `unloggedFuelL = unloggedKm / catchUpKpl` from `estimatedRemainingFuelLiters` (clamped to `0.0..tankCapacityLiters`).
        - Advance `allTimeOdometerKm = maxOf(current.allTimeOdometerKm, targetOdoKm)`.
      - Update `lastSeenEcuDistanceKm = E_now`.

### Decision 3: Full-to-Full Refuel Cycle KPL Calculation
- Add `cycleFuelLiters: Double?` to `refuel_logs` in Room `MIGRATION_8_9`.
- When adding a refuel checkpoint at `odoKm` with `volumeL` and `isFullTank`:
  - Query all prior refuel logs for the vehicle ordered by `odometerKm DESC, timestampMs DESC`.
  - Find the most recent prior **Full Tank** entry (`prevFull = history.firstOrNull { it.isFullTank }`).
  - If `isFullTank == true` and `prevFull != null` and `odoKm > prevFull.odometerKm`:
    - Sum any intermediate partial refuels (`!it.isFullTank`) recorded after `prevFull.timestampMs`.
    - `totalCycleFuelL = partialVolumeSincePrevFull + volumeL`
    - `cycleDistKm = odoKm - prevFull.odometerKm`
    - `cycleAvgKpl = (cycleDistKm / totalCycleFuelL).coerceIn(0.5, 99.9)`
    - Store `cycleDistanceKm = cycleDistKm`, `cycleFuelLiters = totalCycleFuelL`, `cycleAvgKpl = cycleAvgKpl` on the new `RefuelLogEntity`.
    - Update `vehicle.fullRefuelCycleKpl = cycleAvgKpl` (or a weighted blend if prior full cycles exist: `0.7 * cycleAvgKpl + 0.3 * prevCycleKpl` so one-off traffic anomalies remain smooth while reflecting the latest cycle).
  - If `isFullTank == false` (partial refuel):
    - Store `cycleDistanceKm = null`, `cycleFuelLiters = null`, `cycleAvgKpl = null` so partial fills never produce misleading KPL numbers.
    - Add `volumeL` to `estimatedRemainingFuelLiters` (clamped to `tankCapacityLiters`).
  - If `isFullTank == true` and `ecuDistanceSinceClearKm.value != null` on a calibrated car:
    - Automatically refresh `odometerAtBaselineKm = odoKm`, `ecuDistanceBaselineKm = ecuDistNow`, and `lastSeenEcuDistanceKm = ecuDistNow` so the odometer baseline stays tight at every full-tank checkpoint.
- **Isolation from Realtime OBD KPL:**
  - `allTimeRoadDistanceKm`, `allTimeRoadFuelLiters`, `allTimeTotalDistanceKm`, `allTimeTotalFuelLiters`, `kpl_road_avg`, `kpl_moving_avg`, and `kpl_trip_avg` are never modified by `fullRefuelCycleKpl`.
  - When `fullRefuelCycleKpl != null`, `FuelEconomyTracker` uses `fullRefuelCycleKpl` as the primary DTE economy rate (`dteEconomyKpl`) for `range_dte` and unlogged distance fuel depletion.

### Decision 4: Universal Multi-ECU DTC Scan & Clear Reliability
- In `DtcDecoder`:
  - Update `parseDtcResponse` to decode both single-frame (`43 01 01 71` or legacy `43 01 71`) and ISO-TP multi-frame (`014` / `0: 43 06 ...` / `1: ...`) responses, including multi-ECU line sets when `7E8` and `7E9` both respond.
  - Add `ecuResponded: Boolean = true` to `DiagnosticReport`.
  - In `DtcDecoder.scanAll(client)`:
    - Mark `anyResponded = true` whenever `03`, `07`, `0A`, or `0101` returns `ObdCommandResult.Success`.
    - If `0101` returns `NoData` (as on the 2014 Avanza K3-VE), probe Toyota Mode `2106` (`61 06 <A> <B> <C> <D>...`) and decode readiness via `parseReadinessPayload(payload.take(4))`.
    - If `anyResponded` is still `false` (e.g., an ECU returns `NO DATA` for `03`, `07`, `0A`, `0101` when 0 codes exist), send `01001` (or `0100`) to verify whether the Engine ECU is online with Ignition ON. If `0100` succeeds, set `ecuResponded = true` (meaning 0 trouble codes exist).
  - In `DtcDecoder.clearDtcs(client)`:
    - Send standard `04`. If `04` returns `Success`, return `true`. If `04` returns `NoData` on a CAN ECU that only accepts physical addressing, send `04` to `7E0` (`ATSH7E0` / `ATCRA7E8` / `04` / restore default header).
    - In `HudViewModel.clearDtcs()`, after a successful clear, if the active vehicle uses `0131`/`2131` calibration, re-anchor `odometerAtBaselineKm = allTimeOdometerKm`, `ecuDistanceBaselineKm = 0.0`, `lastSeenEcuDistanceKm = 0.0`.
