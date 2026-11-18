# Proposal

Status: **Approved by owner on `2026-11-18`** (map style presets and camera modes implemented as selectable options in Settings with `Dark Contrast` + `3D Nav 45°` as default).

## Why

Extended real-car driving and saved-trip export exposed eight friction and defect items across responsiveness, layout geometry, recording workflow, map clarity, and sharing:

1. **Export CSV / JSON Crash (Bug):** Tapping `CSV` or `JSON` in `Saved Runs` immediately crashes with `android.util.AndroidRuntimeException: Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag` (captured on `RMX1921` at `11-17 23:24:15.431`) because `HudViewModel` passes `Application` context to `TripExporter.shareFile` on `Dispatchers.IO` without `Intent.FLAG_ACTIVITY_NEW_TASK` or `ClipData`.
2. **Wait-Then-Burst Smoothing & Map Stutter:** Exponential smoothing (`tau = 95 ms`) drops most of the delta during the first `~95 ms` after an OBD poll arrives and then sits idle for the remainder of the `~250–500 ms` poll interval (`40 - [pause] - 39..30`), while too many Fuel/Math dependencies in `PollTier.HIGH` inflate single-PID CAN round-trips. On the map, the raw GPS puck snaps instantaneously on every `500–1000 ms` fix while `mlMap.animateCamera(..., 420)` lags independently and restarts mid-flight on recomposition.
3. **Missing Top OBD2 Adapter Status Toast:** When finding, connecting, or disconnected from the ELM327 adapter, there is no glanceable flashing status banner at the top of the HUD that auto-dismisses once live telemetry begins (as in Torque).
4. **Wasted Bottom Space in Radial Gauges:** `AnalogDialWidget` centers its circle at `50%` height using `minOf(w, h) * 0.43`, leaving `~25–30%` empty below the `250°` arc opening and forcing tall multi-row tiles (`RPM`) that starve space below.
5. **Single Start/Stop Toggle Prevents Multi-Stop Runs:** Stopping briefly along a route currently ends and fragments the trip unless recording continues idling at a stop. Adding an explicit `Pause / Resume` state keeps multi-stop trips in one continuous log while excluding parked gaps from active elapsed time, average speed, and trail teleports.
6. **Limited GPS Map Styling & Config Previews:** Users cannot pick from lighter/high-visibility OpenFreeMap vector themes (including `Liberty` 3D buildings, `Bright`, `Fiord`, `Positron`) or configure navigation pitch/zoom/road-accent contrast.
7. **Horizontal Swipe on Vertical Page Indicator:** The HUD page indicator dots are vertical on the right edge, whereas `HorizontalPager` requires horizontal swipes that clash with gauge dragging across landscape and portrait screens.
8. **Missing Strava-Style Shareable Image Export & Car Identity Details:** Saved runs cannot export a shareable PNG route graphic overlayed on the car's photo, and `VehicleProfileEntity` lacks `photoUri`, exact `engineCc`, `transmission`, `carType`, and `modelYear` (fields that both enrich share cards and feed `Cd×A`, drivetrain loss, and exact displacement into physics calculations).

## What Changes

- **Fix CSV & JSON Export Crash (`TripExporter` / `HudViewModel`):** Launch chooser intents on `Dispatchers.Main` with `Intent.FLAG_ACTIVITY_NEW_TASK`, `ClipData` URI permission propagation, and streaming export for large runs (plus optional Save-to-Device fallback toast on no chooser target).
- **Hardware-First Fast CAN Polling + Light Needle Damper (`ObdPollingEngine`, `Elm327Client`, `ObdPidRegistry`, `TelemetryDisplaySmoother`, `DualMapCanvas`):**
  - Cut per-cycle ELM327 serial commands from ~8–12 PIDs (~500–1,500 ms total delay on lift-off) down to a **2–4 PID Priority-1 fast loop** (`rpm`, `rel_throttle`/`throttle`, `obd_speed`, optional `maf`), rotate helper math channels (`engine_load`, `timing_advance`, `map`, `baro`, temps, trims) via staggered background rotation so slow PIDs never block RPM/Throttle, enable `ATAT2` + `ATST 14` tight CAN timing, and restrict multi-PID CAN batches to `<= 7` response bytes (single CAN frame, zero ISO-TP multi-frame stall) with `01C0` PID block discovery for 2024 ECUs.
  - Replace lag-adding retrospective value generation on gauges with a user-selectable `Gauge Smoothing` setting (`Light 40ms Needle Damper` recommended for visual smoothness at 12–20 Hz, `Raw Instant 0ms` for zero added lag, or `Adaptive Fluid`), keeping raw numbers unfiltered for SQLite `telemetry_samples` and fuel/trip math.
  - Synchronize map puck position and shortest-angle bearing inside a single 60 Hz frame interpolator (calling `moveCamera` lockstep with the puck GeoJSON feature) with bounded speed/heading dead-reckoning between 1–2 Hz GPS fixes.
- **Torque-Style Top Connection Indicator (`HudCanvasScreen`):** Add a compact pulsing/flashing banner at top-center while `ConnectionState` is `Connecting`, `Reconnecting`, or `Disconnected`, briefly confirming `Connected` before auto-hiding completely so `#000000` driving view remains chrome-free.
- **Maximized Radial Gauge Geometry (`AnalogDialWidget`):** Compute maximum circle radius from the actual `250°` arc bounding box (`w / 2.12`, `h / 1.72`) and shift center down (`~0.59h`), expanding ring diameter up to `+35%` and reclaiming bottom height so extra widgets fit below compact radial dials.
- **Multi-Stop Trip Pause / Resume (`HudViewModel`, `MiniMapWidget`, `ApexDatabase`):** Track `RecordingState` (`IDLE`, `RECORDING`, `PAUSED`) with dual circular controls on `MiniMapWidget` (`Pause/Resume` amber/blue + `Stop/End` red), persist `activeDurationSec` on `TripSessionEntity` (`MIGRATION_5_6`), pause telemetry/GPS logging during stops, and reset segment anchor on resume so parked intervals do not inflate trip time or drag straight route lines.
- **Selectable Map Style Presets & Camera Config (`AppPreferences`, `OfflineMapManager`, `DualMapCanvas`):** Support selectable presets (`Dark Contrast`, `Fiord Deep Navy`, `Liberty 3D`, `Bright Nav`, `Positron Minimal`, `OLED Track Only`) plus Camera Angle (`45° Nav Tilt`, `55° Chase Tilt`, `0° Flat Top-Down`) and High-Contrast Roads toggle. Offline `.pmtiles` packs also receive matching dark/navy/light palettes.
- **Vertical Canvas Pager (`HudCanvasScreen`):** Switch `HorizontalPager` to `VerticalPager` in both landscape and portrait orientations so vertical swipe matches the right-edge dot column.
- **Strava-Style Trip Image Card Export & Expanded Vehicle Profile (`TripExporter`, `VehicleProfileEntity`, `HomeSheets`, `ApexDatabase`):** Add photo picker (`photoUri`), `engineCc`, `carType`, `transmission`, and `modelYear` (`MIGRATION_5_6`) with intelligent physics presets (`displacementLiters = cc/1000`, `Cd×A` from `carType`, `drivetrainLossPct` from `transmission`), and render high-resolution Strava-style transparent/photo-backdrop PNG route cards (`distance`, `time`, `avg KPL`, `fuel`, `avg/max speed`, `peak RPM`, route polyline, vehicle badge) shareable from `Saved Runs` and the Stop summary modal.

## Capabilities

### Modified Capabilities
- `obd-protocol`: Interval-paced 60 Hz display smoothing and streamlined high-tier fast PID polling for rapid channels.
- `hud-canvas`: Flashing auto-hide top OBD2 connection banner, vertical-pager canvas navigation, bottom-reclaiming radial gauge sizing, selectable map style & camera config, and synchronous 60 Hz map puck/camera interpolation.
- `logging-tracking`: Crash-free CSV/JSON export, Pause/Resume trip state (`activeDurationSec`), Strava-style PNG trip route share card with optional car photo background, and extended `VehicleProfileEntity` specs (`photoUri`, `engineCc`, `carType`, `transmission`, `modelYear`).

## Impact

- `obd-core`: Updates to `TelemetryCalculator.kt` (interval-paced smoother), `ObdPidRegistry.kt` (`PollTier` refinement for non-critical rapid channels), `ObdPollingEngine.kt`, `VehicleAndUnits.kt` (`BodyType`, `TransmissionType`, aero/drivetrain preset mapping), and unit tests.
- `app`: Room `MIGRATION_5_6` in `ApexDatabase.kt`, `AppPreferences.kt` (`MapStylePreset`, `MapCameraMode`), `OfflineMapManager.kt`, `DualMapCanvas.kt`, `TripExporter.kt` (+ Strava PNG canvas generator), `TorqueEvoWidgets.kt`, `HudCanvasScreen.kt`, `HomeSheets.kt`, `HomeMenuScreen.kt`, and `HudViewModel.kt`.
