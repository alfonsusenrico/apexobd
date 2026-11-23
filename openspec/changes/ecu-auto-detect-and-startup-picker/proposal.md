# Proposal: Automatic ECU Vehicle Detection, Fresh-Open Vehicle Picker, and True Background Exit

## Approval
- Approved by owner in session on 2026-11-23.

## Why
1. When switching between two vehicles (e.g., 2014 Avanza K3-VE and 2024 Avanza 2NR-VE), connecting the OBD-II adapter while the previous car's profile is still active overwrote the wrong car's `supportedPidsCsv`, `estimatedRemainingFuelLiters`, and odometer because `HudViewModel` bound `connectedDiscoveryVehicleId` to whichever vehicle was active without verifying the connected ECU's identity.
2. Manually switching to the correct vehicle profile while already connected left `connectedDiscoveryVehicleId` pointed at the previous vehicle and did not re-bind the live `7C0_2129` / `7C0_21E1` capabilities to the newly selected vehicle, causing the 2024 2NR-VE to falsely display the non-sensor calibration card.
3. Turning off the background listener service previously left the app process and task alive in memory, so reopening the app was not a clean cold start and did not prompt to confirm the active vehicle.

## Expected Outcome
- **Automatic ECU Vehicle Detection on Connect:** `ObdPollingEngine` reads Mode `09 02` VIN (when supported) alongside Mode 01 + OEM PID discovery and emits `EcuIdentitySnapshot` before persisting any vehicle data. `VehicleIdentityMatcher` matches the connected ECU against saved vehicle profiles by exact VIN first, then by Mode 01 PID fingerprint similarity (`>= 0.85`). When a different saved car matches, `HudViewModel` automatically switches to that car before saving any PIDs, odometer, or fuel level.
- **Re-Bind Live Capabilities on Manual Vehicle Switch:** Switching vehicles while already connected cleanly re-binds `connectedDiscoveryVehicleId` and applies the live engine's discovered capabilities (`supportedPids`, `7C0_2129`, `7C0_21E1`, `vin`) to the newly selected car.
- **Fresh-Open Vehicle Picker & True App Kill:** When 2+ vehicles exist on a fresh cold start (and no paused run is active), `ApexAppRoot` shows a fast 1-tap Vehicle Picker before connecting. Stopping the background service flushes vehicle stats, safely checkpoints any active run to SQLite, stops `LoggingForegroundService`, calls `finishAndRemoveTask()`, and terminates the app process so the next open is a true cold start.

## What Changes
- **Capabilities affected:** `obd-protocol`, `logging-tracking` (no new capability added).
- **`obd-core`:** Add `EcuIdentitySnapshot`, `VehicleIdentityCandidate`, and `VehicleIdentityMatcher`, and emit `ecuIdentity` from `ObdPollingEngine` on connect.
- **`app`:**
  - Add `vin: String? = null` to `VehicleProfileEntity` via Room `MIGRATION_10_11` (`version = 11`).
  - Auto-switch vehicle profile on ECU identity match in `HudViewModel` before persisting telemetry; re-bind live capabilities on manual `selectVehicle` while connected.
  - Add fresh-open `StartupVehiclePickerDialog` in `HomeSheets.kt` / `HudCanvasScreen.kt`, ECU identity badge in `VehicleProfileSheet`, and true app exit on `terminateBackgroundService()` wired to `MainActivity.finishAndRemoveTask()`.
  - Bump `versionCode = 5`, `versionName = "1.2.0"` in `app/build.gradle.kts`.
