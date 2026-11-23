# Proposal: Reliable Multi-ECU PID Discovery and 60 FPS Predictive Map Gliding

## Approval
- Approved by owner in session on 2026-11-23.

## Why
1. On the 2024 Avanza (`2NR-VE`, CAN), `rel_throttle` (`0145`), `voltage` (`0142`), and `ambient_temp` (`0146`) — all located in Mode 01 Block `0140` — had no values for an entire drive. Three compounding bugs caused this:
   - `Elm327Client.initialize()` set tight `ATST19` (100 ms) *before* one-time PID discovery (`0100`/`0120`/`0140`), `7C0` cluster discovery, and `0902` VIN queries ran.
   - `ObdPidRegistry.discoverSupportedPids()` only parsed the first `41 <block>` response line (ignoring additional ECUs when multiple CAN modules respond) and immediately `break`ed on the first missed block with zero retries even when the prior block advertised that the next block exists.
   - `HudViewModel.persistDiscoveredPidsToVehicle()` overwrote a vehicle's saved `supportedPidsCsv` even when a glitchy connect returned a strict subset of previously confirmed PIDs.
2. Live HUD map movement felt stepped (burst -> brake -> pause every ~1 second) because `DualMapCanvas` exponentially chased a static `1 Hz` GPS coordinate with `tau = 160 ms` (reaching 95% within 480 ms and stalling for the remaining 520 ms of each second) instead of using constant-velocity dead-reckoning with soft correction like Google Maps.

## Expected Outcome
- Mode 01 PID discovery runs under generous default `ATAT1` timing, unions bitmasks across all responding CAN ECUs, retries advertised blocks up to 3 times, and only enables tight `ATST19` live-polling timing *after* discovery finishes.
- `HudViewModel` never shrinks a vehicle's saved `supportedPidsCsv` to a strict subset on a partial connect.
- `DualMapCanvas` glides smoothly at 60 FPS across 1 Hz GPS fixes using a pure, unit-tested constant-velocity predictive glider (`MapMotionGlider`) with bounded dead-reckoning (`<= 1.4 s`), smooth heading interpolation, and zero stationary GeoJSON churn.

## What Changes
- **Capabilities affected:** `obd-protocol`, `hud-canvas` (no new capability added).
- **`obd-core`:**
  - `Elm327Client.kt`: Add `extractAllPidPayloads()`, move `ATST19` out of `initialize()` into `applyLivePollingTiming()`.
  - `ObdPidRegistry.kt`: Union all responding ECUs' bitmasks per block and retry advertised blocks up to 3 times (with single-frame `"01XX1"` fallback).
  - `ObdPollingEngine.kt`: Call `client.applyLivePollingTiming(...)` strictly after `discoverSupportedPids`, `ToyotaMeterProtocol.discover`, and `DtcDecoder.readVin`.
- **`app`:**
  - `HudViewModel.kt`: Preserve previously verified PIDs when a partial connect returns a strict subset.
  - `LocationTracker.kt`: Add `fixTimeMs: Long` to `GpsSnapshot` and use `0f` minDistance on high-frequency hardware GNSS so 1 Hz fixes arrive cleanly.
  - `DualMapCanvas.kt` & `TorqueEvoWidgets.kt`: Add `MapMotionGlider` (constant-velocity dead-reckoning + soft phase-locked exponential correction) driven by `speedKmh`, `bearingDeg`, and `fixTimeMs`.
  - Bump `versionCode = 6`, `versionName = "1.2.1"` in `app/build.gradle.kts`.
