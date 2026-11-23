# Design: Automatic ECU Vehicle Detection, Fresh-Open Vehicle Picker, and True Background Exit

## Decisions

### Decision 1: Two-Layer ECU Identification (`VIN` + Mode 01 PID Fingerprint)
- During `ObdPollingEngine.start()` discovery, read `DtcDecoder.readVin(client)` (Mode `09 02`) once alongside Mode 01 PID discovery and Toyota `7C0`/`7E0` probes, then emit `EcuIdentitySnapshot(vin, mode01Pids, allCapabilities)`.
- `VehicleIdentityMatcher.matchVehicle(...)` evaluates all saved vehicle profiles:
  1. **Exact VIN match** (normalized uppercase alphanumeric, length >= 11) takes highest priority.
  2. **Mode 01 PID Fingerprint (Jaccard similarity)** compares `identity.mode01Pids` against each profile's saved Mode 01 PIDs (excluding `7C0_*`/`7E0_*` OEM tokens, requiring `>= 6` Mode 01 PIDs):
     - If the currently active vehicle has no conflicting VIN and matches `>= 0.85`, keep it.
     - Otherwise, if another saved vehicle matches `>= 0.85` and beats the currently active vehicle by at least `0.20`, auto-switch to that vehicle.
- Telemetry persistence (`supportedPids`, `ecuOdometerKm`, `ecuFuelLiters`, `ecuDistanceSinceClearKm`) waits for the identity check on connect so a mismatched car never corrupts the previously selected vehicle's odometer or fuel level.

### Decision 2: Live Capability Re-Binding When Switching Vehicles While Connected
- In `HudViewModel.selectVehicle(vehicleId)`:
  - When `_connectionState.value is ConnectionState.Connected`, re-bind `connectedDiscoveryVehicleId = vehicleId`, `activeEngineVehicleId = vehicleId`, and immediately persist/apply the live engine's `supportedPids`, `ecuOdometerKm`, `ecuFuelLiters`, `ecuDistanceSinceClearKm`, and `vin` to the newly selected vehicle.

### Decision 3: Fresh-Open Vehicle Picker & True App Kill on Stop Background Service
- `HudViewModel` exposes `showStartupVehiclePicker: StateFlow<Boolean>`. On cold start (`init`), if `vehicles.size >= 2` and no paused recording was restored, `_showStartupVehiclePicker.value = true` and auto-connect pauses until the user taps a car (`confirmStartupVehicle(vehicleId)`).
- `MainActivity` registers an app-exit handler (`finishAndRemoveTask()`). When `terminateBackgroundService()` is triggered (via ongoing notification tap or Adapter sheet), `HudViewModel` checkpoints any active run, flushes vehicle stats, stops `engine` and `LoggingForegroundService`, invokes `finishAndRemoveTask()`, and calls `Process.killProcess(Process.myPid())`.
