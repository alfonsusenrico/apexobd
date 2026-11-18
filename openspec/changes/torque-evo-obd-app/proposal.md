# Proposal: Torque Evo Android OBD-II App

Approved in session: 2026-11-16 (initial scaffold), revised and approved 2026-11-17 (2-Page Home + Blank Canvas, Dual Offline MapLibre + Optional Google Maps, Refuel Checkpoint Log, Multi-Sensor Derived Physics, Persistent Idle Fuel Pause).

## Why
The owner uses Torque as a secondary digital HUD dashboard and DTC scanner, but Torque's UI/UX is outdated, its polling stalls slowly on multi-PID frames, its stationary idle calculation distorts all-time fuel average, and it lacks modern offline map tracking and independent refuel-cycle checkpoints. The owner wants a high-efficiency Android Kotlin/Jetpack Compose replacement tailored to real ECU support (including Toyota Avanza CAN `7E8`) while adapting cleanly to any OBD-II vehicle.

## Expected Outcome
An installable Android Kotlin + Jetpack Compose application (`ApexOBD`) that:
- Connects to Wi-Fi ELM327 adapters (`192.168.0.10:35000`) with automatic Wi-Fi socket binding + simultaneous Cellular Mobile Data request for online apps/maps, plus a built-in Demo Simulator.
- Queries SAE J1979 Mode `01` bitmasks and shows **only supported live PIDs & available derived calculations** in the component picker, using `2x` high-priority RPM interleaving and blocking TCP reads for minimal latency and battery draw.
- Separates **Page 1 Home Menu** (static/summary only: elevated Car Profile with `Gasoline/Diesel/Electric`, Tank Capacity & Remaining Fuel/Range, `All time avg`, All-Time Odometer, `Best 0–100`, compact Strava Last Saved Run Map + `Navigate ↗`, **Refuel Log** checkpoint tracker, Fault Codes, Run Logs & Offline Maps, ECU PIDs, elevated Settings) from **Page 2 Blank-Default HUD Canvas** (swipeable N-page `#000000` canvas, zero visible chrome, hold-tap anywhere for hidden context menu, `8×4`/`4×8` Grid Snap + Freeform XY drag, persistent Idle Fuel Pause, refined horizontal-unit automotive components).
- Provides dual optional map backends (`Offline MapLibre Vector` with user-defined bounding-box & size downloader, optional `Google Maps Online`, and `OLED Track Path Only`), background Foreground Service run recording, and global unit switching (`km/h`/`mph`, `L`/`gal`/`kWh`, `bar`/`psi`/`kPa`, etc.).

## What Changes
- New Android multi-module Gradle project (`app` + `obd-core` pure Kotlin/JVM protocol library so protocol/PID/DTC/formula tests run fast on JVM without an emulator).
- `obd-core`: ELM327 AT command engine, `WifiTcpTransport` + `SimulatedObdTransport`, bitmask discovery (`0100`–`01A0`), ~100+ Mode `01` PIDs, Mode `02` Freeze Frame, Mode `03`/`07`/`0A` DTC decoder + SAE code dictionary, Mode `04` Clear DTCs, Mode `05`/`06` Monitors, Mode `09` VIN/CALID, UDS Mode `22` + arithmetic expression evaluator (`A`, `B`, `C`, `D` bytes), priority polling scheduler.
- `app`: Jetpack Compose `Torque Evo` UI (`HorizontalPager` N-page canvas, custom `Canvas` Analog/Semi-Digital/Bar/Chart components, Google Maps Compose Mini-Map with graceful fallback when `MAPS_API_KEY` is absent, drag-and-drop edit mode, Component Picker, Room persistence, Foreground logging service, CSV/JSON share-sheet export, 0–100 km/h timer, HP/Torque calculator).

## Non-Goals
- iOS or cross-platform build (Android-only per owner decision).
- Bluetooth Classic SPP or BLE in initial build (architected via `ObdTransport` interface for future addition; Wi-Fi TCP + Simulator are in scope now).
- Modifying ECU calibration / flashing (Mode `04` clear DTCs and standard diagnostic reads only).

## Capabilities Affected
New capabilities added (matching `AGENTS.md` `Capabilities:`):
- `obd-protocol` (with `backend-engineering`-style protocol boundary & error handling rigor)
- `hud-canvas` (with `ui-ux-quality` standards: OLED contrast, tabular numerals, zero color-only meaning, driving glanceability)
- `diagnostics`
- `logging-tracking`
- `custom-pids`

## Impact
- Introduces Android Gradle build, Kotlin `obd-core` library, Jetpack Compose UI, Room database, and Google Maps Compose dependency.
- Requires `MAPS_API_KEY` in `local.properties` for live Google Map tiles (falls back cleanly to a live vector GPS route canvas when no API key is configured).
