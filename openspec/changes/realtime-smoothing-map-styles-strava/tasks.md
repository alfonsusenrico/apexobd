# Tasks

> Status: Approved by owner (`2026-11-18`) and verified across automated tests, lint, debug APK build, and live `RMX1921` testing (`verification.md`).

## Phase 1 — Export Crash Fix, Smoothing Engine & Fast Poll Tuning
- [x] 1. Fix `TripExporter.shareFile` (`FLAG_ACTIVITY_NEW_TASK`, `ClipData`, `Dispatchers.Main` chooser launch, `runCatching` fallback) and add regression unit/intent tests verifying flag & export integrity.
  - Expected Result: `CSV` and `JSON` export opens the system share chooser cleanly on `RMX1921` without crashing.
- [x] 2. Reduce real-world CAN latency in `Elm327Client`, `ObdPidRegistry`, and `ObdPollingEngine` (`ATAT2` + CAN-gated `ATST19` 100ms, single-frame `<= 7`-byte CAN batching, focused `PollTier.HIGH` for `rpm`/`rel_throttle`/`obd_speed`/`maf` vs staggered background rotation, `01C0` block scan), add `Gauge Display Smoothing` setting (`Raw Instant (0ms)` / `Light Damper (40ms)` / `Adaptive Glide`), and remove duplicate `AnalogDialWidget` animation lag while keeping raw logs pure.
  - Expected Result: Unit tests verify `PollTier` partitioning, `<= 7`-byte CAN chunking, `01C0` bitmask scan, and display smoother modes; real-world adapter delay on throttle lift drops from ~0.5–1.5s toward ~100ms class responsiveness.

## Phase 2 — Map Smoothing, Map Styles, Top Banner, Vertical Pager & Maximized Radial
- [x] 3. Implement selected `MapStylePreset` (`Dark Contrast`, `Fiord Deep Navy`, `Liberty 3D`, `Bright Nav`, `Positron Minimal`, `OLED Track Only`) and `MapCameraMode` (`3D Nav 45°`, `Low Chase 55°`, `Flat 0°`) in `AppPreferences`, `OfflineMapManager`, `DualMapCanvas`, and Settings/Map sheets, plus 60 Hz lockstep puck + camera interpolation in `DualMapCanvas`.
  - Expected Result: All map styles render cleanly with smooth frame-synced marker and camera movement.
- [x] 4. Add flashing auto-hide top OBD2 connection status banner in `HudCanvasScreen`, switch `HorizontalPager` to `VerticalPager`, and update `AnalogDialWidget` radius/center math to reclaim bottom height in compact tiles.
  - Expected Result: Top pill flashes while connecting/disconnected and vanishes on connect; vertical swipe flips pages cleanly; radial dials occupy less vertical height without empty bottom slack.

## Phase 3 — Pause/Resume Recording, Vehicle Metadata (`MIGRATION_5_6`) & Strava PNG Export
- [x] 5. Implement `RecordingState` (`IDLE`, `RECORDING`, `PAUSED`) across `HudViewModel`, `MiniMapWidget` (`Pause/Resume` + `End` controls), `ApexDatabase` (`MIGRATION_5_6` adding `activeDurationSec` on `trip_sessions` and `photoUri`, `engineCc`, `carType`, `transmission`, `modelYear` on `vehicle_profiles`), and `VehicleAndUnits` physics presets (`Cd×A`, drivetrain loss, exact cc displacement).
  - Expected Result: Multi-stop trips pause and resume into one continuous run with accurate active duration/average speed and clean database migration (`5 -> 6`) preserving existing runs on `RMX1921`.
- [x] 6. Build `TripCardRenderer` (offscreen high-res Strava-style PNG generator with optional copied car photo backdrop) and wire `VehicleProfileSheet` photo picker & extended metadata fields + `Card PNG` share actions in `Saved Runs` and Stop modal.
  - Expected Result: Sharing a trip generates a crisp Strava-style PNG route card with car photo & vehicle specs via `FileProvider`.

## Phase 4 — Automated Checks, Live Verification & Archive
- [x] 7. Run `:obd-core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and `openspec validate --all --strict`; install in-place on `RMX1921` (`adb install -r`, zero `pm clear`) and verify live exports, themes, map styles, smoothing, radial dial, vertical swipe, Pause/Resume, and top status banner via `logcat` + screenshots.
  - Expected Result: Zero crashes, all checks pass, `verification.md` complete with evidence.
