# Proposal: ECU Distance Baseline Calibration (0131/2131), Full-to-Full Refuel Cycle KPL, and Multi-ECU DTC Reliability

## Approval
- Approved by owner in session on 2026-11-22.

## Why
1. Vehicles without a CAN-connected instrument cluster or Mode 01 `012F` fuel float sensor (such as the 2014 Toyota Avanza K3-VE F650) cannot report live hardware fuel tank percentage (`fuel_level`) or cluster odometer (`ecu_odometer`), and virtual fuel depletion drifts whenever the vehicle is driven without the phone connected. However, the Engine ECM (`7E0`) maintains an internal kilometer counter (`7E0 2131` / `0131` Distance Since Codes Cleared) that increments every kilometer regardless of who drives the vehicle.
2. Full-tank refuels provide an exact pump-measured fuel-to-full cycle economy (`km/L`) across all driving (including unlogged drives and partial top-ups in between) that should be recorded per full-tank cycle and used for fuel left and range (DTE) estimation without altering real-time OBD All-Time KPL or Trip KPL metrics.
3. On ECUs that do not support `0101` in Mode 01 and return `NO DATA` on `03` when 0 confirmed DTCs exist (while responding `47 00` on `07` and `61 06 ...` on `7E0 2106`), `scanDiagnostics()` falsely reported `"ECU did not respond to diagnostic query"` and disabled the Mode 04 clear action.

## Expected Outcome
- `Fuel Level (%)` (`fuel_level`) and `Cluster Odometer` (`ecu_odometer`) remain strictly reserved for real hardware sensors (`012F`, `7C0 2129`, `7C0 21E1`).
- For vehicles with native sensors (e.g. 2024 Avanza W100), Vehicle Profile renders as-is. For vehicles without native sensors (e.g. 2014 Avanza K3-VE), Vehicle Profile shows an **ECU Distance & Fuel Baseline Calibration** section (with a `?` info guide) that pairs the live `0131` / `7E0 2131` counter with a one-time user-confirmed dashboard odometer and starting fuel amount (percentage or volume).
- On every subsequent OBD connection, calibrated vehicles automatically advance their odometer and catch up unlogged fuel depletion using the `0131` / `7E0 2131` delta, with automatic rollover protection if `2131` resets after a Mode 04 clear or battery disconnect.
- Refuel checkpoints record true **Full-to-Full Cycle KPL** (summing any partial refuels since the previous full-tank checkpoint) and use that cycle KPL to drive Estimated Fuel Left and Estimated Range (DTE) for the next tank cycle without touching real-time OBD All-Time KPL or Trip KPL.
- Fault code scanning and Mode 04 clearing work reliably across all vehicles (including multi-frame ISO-TP DTC lists, `7E0 2106` readiness fallback, and ECUs returning `NO DATA` on empty Mode 03).

## What Changes
- **Capabilities affected:** `obd-protocol`, `logging-tracking`, `diagnostics` (no new capability added).
- **`obd-core`:**
  - Probe `0131` and `7E0 2131` (`TOKEN_ECM_DIST_2131 = "7E0_2131"`) during discovery and periodic slow polling so `ecuDistanceSinceClearKm` is available on both standard Mode 01 and Toyota Mode 21 ECUs.
  - Enhance `DtcDecoder.scanAll` and `parseDtcResponse` to handle multi-frame ISO-TP DTC payloads, track `ecuResponded: Boolean`, query `7E0 2106` readiness fallback when `0101` is unsupported, and confirm ECU liveness via `0100` if all DTC modes return empty/NO DATA.
  - Update `FuelEconomyTracker` to accept `fullRefuelCycleKpl` for DTE and unlogged distance fuel depletion without modifying `allTimeTotalAvgKpl`, `allTimeRoadAvgKpl`, or `tripAvgKpl`.
  - Scale `ObdPollingEngine` reconnect backoff in `backgroundIdleMode` up to 30s (vs 1–5s in foreground) and add `triggerImmediateReconnect()` on foreground resume so disconnected background waiting does not flood outbound Wi-Fi packets.
- **`app`:**
  - Add Room `MIGRATION_8_9` adding `ecuDistanceBaselineKm`, `odometerAtBaselineKm`, `lastSeenEcuDistanceKm`, and `fullRefuelCycleKpl` to `vehicle_profiles`, plus `cycleFuelLiters` to `refuel_logs`.
  - Add calibration logic in `HudViewModel` (`calibrateVehicleBaseline`, automatic connect-time `ecuDistanceSinceClearKm` odometer & fuel catch-up, and Mode 04 clear re-anchoring).
  - Update `addRefuelCheckpoint` to accumulate partial refuels since the last full-tank checkpoint and compute `cycleAvgKpl` only on Full-Tank refuels (`(odoNow - prevFullOdo) / (sumPartials + currentVolume)`).
  - Update `VehicleProfileSheet` and `RefuelLogSheet` in `HomeSheets.kt` with the conditional Calibration Card, `?` guidance dialog, percentage/volume fuel input, and Full-to-Full Cycle KPL badge.
  - **Background Power & Deep-Sleep Optimization:**
    - Acquire `PARTIAL_WAKE_LOCK` (`ApexOBD:ObdPollingLock`) and `WifiLock` (`ApexOBD:WifiLowLatency`) strictly when `connectionState is ConnectionState.Connected || isRecording`, releasing both immediately when disconnected so Android `SystemSuspend` can enter Deep Sleep.
    - Deduplicate foreground notification `#4201` updates, omit per-attempt `#N` counter churn from background notification text, and suppress non-transition notification posts while `PowerManager.isInteractive == false`.
    - Remove unconditional `locationTracker.startUpdates()` from `MainActivity.permissionLauncher` so GNSS only runs when `activeHud || isRecording`, and request `FOREGROUND_SERVICE_TYPE_LOCATION` only when location tracking is active.

## Non-Goals
- Do not fabricate `fuel_level` (`%`) or `ecu_odometer` HUD sensors when the vehicle lacks hardware sensors.
- Do not alter real-time OBD `All-Time Avg KPL` (`kpl_road_avg`) or `Trip KPL` (`kpl_trip_avg`) with manual refuel cycle KPL.
