# Discussion & Audit: ECU Distance Calibration (2131/0131), Full-to-Full Cycle KPL, and Multi-ECU DTC Reliability

- **Date:** 2026-11-22
- **Approved by:** Owner in session (`2026-11-22`)

## 1. Evidence from Real Vehicles
- **2024 Toyota Avanza BAM (`id=6`, W100 2NR-VE):**
  - Combination Meter (`7C0`/`7C8`) answers Mode `2129` (`0.5 L` digital fuel tank float) and Mode `21E1` (`32-bit` cluster odometer in km).
  - Engine ECM (`7E0`/`7E8`) answers Mode `0101` readiness, Mode `03` (`4300`), Mode `07` (`4700`), and Mode `0A` (`4A00`).
- **2014 Toyota Avanza K3-VE (`id=5`, F650 1.3L AT):**
  - Combination Meter is not on the OBD-II CAN bus (`7C0 -> NO DATA`) and Engine ECM does not support `012F` or `0101`.
  - Engine ECM (`7E0`/`7E8`) supports Toyota Mode 21 (`2100..21E0`), including:
    - `7E0 2131 -> 61 31 2A 8A` (`10,890 km`, 16-bit integer distance traveled since DTCs cleared, incrementing every kilometer regardless of whether the phone is connected).
    - `7E0 2106 -> 00C | 0:61060007E100 | 1:03060000000000` (bytes `0..3` = `00 07 E1 00` carry the SAE J1979 `0101` MIL, DTC count, and readiness monitor bitmask).
  - For DTC queries with 0 faults, the 2014 K3-VE ECM answers `07 -> 4700` (0 pending codes) and `03 -> NO DATA`, `0A -> NO DATA`. Because `0101` also returned `NO DATA`, `HudViewModel.scanDiagnostics()` previously treated `confirmed.isEmpty() && pending.isEmpty() && permanent.isEmpty() && readiness == null` as `"ECU did not respond to diagnostic query"` and disabled the Mode 04 clear button.

## 2. Approved Design Decisions
1. **Strict Hardware Sensors for `fuel_level` (`Fuel Level %`) and `ecu_odometer` (`Cluster Odometer`):**
   - Never fabricate `fuel_level` or `ecu_odometer` on vehicles without a hardware fuel float (`012F` / `7C0 2129`) or cluster odometer (`7C0 21E1`).
2. **One-Time ECU Distance & Fuel Baseline Calibration (`0131` / `7E0 2131`):**
   - On vehicles with native cluster sensors (`7C0_2129` / `7C0_21E1` / `2F`), show Vehicle Profile as-is.
   - On vehicles without native cluster sensors, show the **ECU Distance & Fuel Baseline Calibration** section in Vehicle Profile with a `?` help dialog explaining the calibration workflow.
   - Requires an active OBD connection reading `0131` or `7E0 2131` (`ecuDistanceSinceClearKm`).
   - User enters their confirmed dashboard **Odometer** and **Current Tank Fuel** (either by percentage presets `25%`, `50%`, `75%`, `100% Full` or exact fuel volume/percentage input).
   - Stores `ecuDistanceBaselineKm`, `odometerAtBaselineKm`, and `lastSeenEcuDistanceKm`.
   - On every future OBD connection, automatically computes `allTimeOdometerKm = max(allTimeOdometerKm, odometerAtBaselineKm + (currentEcuDistKm - ecuDistanceBaselineKm))` and deducts unlogged fuel (`unloggedDeltaKm / activeDteKpl`) from `estimatedRemainingFuelLiters`.
   - If `currentEcuDistKm < lastSeenEcuDistanceKm` (e.g. after Mode 04 DTC clear or 12V battery reset), automatically re-anchors `odometerAtBaselineKm = allTimeOdometerKm` and `ecuDistanceBaselineKm = currentEcuDistKm` so no mileage is lost.
3. **Full-to-Full Refuel Cycle KPL (Independent from Realtime All-Time KPL & Trip KPL):**
   - Partial refuels (`isFullTank = false`) add volume to `estimatedRemainingFuelLiters` and record a partial refuel checkpoint without computing a noisy partial KPL.
   - Full-tank refuels (`isFullTank = true`) reset `estimatedRemainingFuelLiters = tankCapacityLiters` and compute the true **Full-to-Full Cycle KPL** from the previous full-tank checkpoint (`cycleDistanceKm = odoNow - prevFullOdo`, `cycleFuelLiters = sum(partialRefuelsSincePrevFull) + currentRefuelVolumeLiters`, `cycleAvgKpl = cycleDistanceKm / cycleFuelLiters`).
   - Persists `lastFullRefuelCycleKpl` on the vehicle profile and uses it as the cycle baseline for **Estimated Fuel Left** (unlogged distance catch-up) and **Estimated Range (DTE)** without altering realtime OBD **All-Time KPL (`kpl_road_avg`)** or **Trip KPL (`kpl_trip_avg`)**.
4. **Universal Multi-ECU DTC Scan & Clear Reliability:**
   - Track `ecuResponded: Boolean` in `DiagnosticReport` across `03`, `07`, `0A`, `0101`, `7E0 2106`, and `0100` fallback verification.
   - Parse multi-frame ISO-TP DTC responses (`flattenIsoTpBytes` when multi-frame `0:`/`1:` headers are present) as well as single-frame responses across multiple ECUs (`7E8`, `7E9`).
   - Support Toyota Mode `2106` readiness fallback when `0101` is unsupported (`61 06 <A> <B> <C> <D>`).
   - After `Mode 04` (`04`) clear, if `7E0` or `7E1` is present and standard `04` is rejected or after `04` succeeds, also re-anchor the vehicle's `2131` distance baseline if calibrated.
