# Proposal: Torque Evo Android OBD-II App

Approved in session: 2026-11-16 (owner selected "Torque Evo" design concept).

## Why
The owner uses Torque as a secondary digital HUD dashboard and DTC scanner, but Torque's UI/UX is outdated. The owner wants a modern Android-only Kotlin/Jetpack Compose replacement that preserves Torque's driving-first zero-navigation-chrome multi-page canvas and full component freedom while adding modern OLED aesthetics, embedded Google Mini-Map GPS tracking, high-frequency logging, and UDS Mode 22 support.

## Expected Outcome
An installable Android Kotlin + Jetpack Compose application (`ApexOBD / Torque Evo`) that:
- Connects to Wi-Fi ELM327 adapters (`192.168.0.10:35000`) with automatic Android Wi-Fi network binding, plus a built-in Demo Simulator transport for offline testing.
- Discovers and decodes full SAE J1979 Modes `01`–`0A` and UDS Mode `22` / Torque-CSV custom PIDs.
- Opens directly onto a zero-chrome, swipeable N-page `#000000` OLED canvas with `#00A8FF` blue borderless dials, semi-digital arcs, digital readouts, bars, charts, embedded Google Mini-Map, DTC Checker, 0–100 km/h timer, and HP/Torque gauges.
- Supports dynamic page creation/deletion, drag-and-drop widget reordering, live display-type morphing, resizing, HUD Mirror Mode, and Landscape/Portrait layouts.
- Logs high-frequency telemetry + GPS routes to Room/SQLite with CSV/JSON export.

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
