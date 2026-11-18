# Verification — `realtime-smoothing-map-styles-strava`

Verified on working tree of branch `feat/torque-evo-obd-app` on `2026-11-18`.

## Automated Checks Passed

- **Unit Tests (`:obd-core:test`):** Passed (`28/28` tests, `0` failures, `0` skipped)
  - `FormulaEvaluatorTest`: 3/3
  - `DtcDecoderTest`: 4/4
  - `ObdPidRegistryTest`: 6/6 (includes `01C0` block scan and single-frame `<= 7`-byte CAN batching checks)
  - `TelemetryCalculatorTest`: 9/9 (includes `GaugeSmoothingMode` / `TelemetryDisplaySmoother.AdaptiveSession` checks)
  - `EngineAndPerformanceTest`: 6/6
- **Unit Tests (`:app:testDebugUnitTest`):** Passed (`10/10` tests, `0` failures, `0` skipped)
  - `HudGridLayoutTest`: 3/3
  - `TripExporterTest`: 7/7 (includes `FLAG_ACTIVITY_NEW_TASK` share intent, CSV/JSON exports, and `MapStylePreset` / `MapCameraMode` checks)
- **Android Lint (`:app:lintDebug`):** Passed (`0` errors, `51` warnings)
- **Debug APK Build (`:app:assembleDebug`):** Passed (`app/build/outputs/apk/debug/app-debug.apk`, `59M`)
- **OpenSpec Strict Validation (`openspec validate --all --strict`):** Passed (`3/3` changes passed, `0` failed)

## On-Device Verification (`RMX1921` / `3646fbc7`, in-place `adb install -r`, zero `pm clear`)

- **Room Migration (`5 -> 6`) & Real Data Preserved:** Verified on Home (`v5-08a-home.png`, `v5-10-final-home.png`) that `Avanza 1.3L` migrated cleanly to `1300 cc · AT · MPV · 1175 kg · Tank 44.8/45 L`, preserving `116949 km` odometer, `12.3 km/L`, `158 MB` `mbgl-offline.db`, all 4 HUD pages, and all 5 saved commute runs (`v5-03-runlogs.png`).
- **CSV, JSON & Strava Route Card Exports Fixed:** Verified live on real Trip #7 (`v5-04a-export-csv.png`, `v5-04c-share-card.png`) generating `apex_trip_7.csv` (`588,125` B), `apex_trip_7.json` (`1,221,376` B), and `apex_route_7.png` (`122,808` B) and opening the Android system share sheet cleanly without crash.
- **Settings — Map Styles, Camera & Smoothing Options:** Verified live in `Adapter, Units, Maps & Polling` (`v5-08b-settings-top.png`, `v5-08b-settings-mapstyles.png`): all 6 `GPS Map Display Style (6 Options)` (`Dark Contrast`, `Fiord Navy`, `Liberty 3D`, `Bright Nav`, `Positron Minimal`, `OLED Track`), 3 `Map Camera Perspective` chips (`3D Nav 45°`, `Low Chase 55°`, `Flat Top-Down`), `High-Contrast Road Lines (Dark Maps)`, and 3 `Gauge Display Smoothing (Visual Only)` options (`Raw Instant (0ms)`, `Light Damper (40ms)`, `Adaptive Glide`).
- **Extended Vehicle Profile (`Vehicle Profile` sheet):** Verified (`v5-08c-after-apply.png`) `Car Photo Backdrop (Strava Card)` photo picker plus 9 `Body Type` (`Cd×A`) chips and 5 `Transmission` (`Drivetrain Loss`) chips.
- **Top Flashing Connection Banner & Auto-Hide:** Verified (`v5-09h-hud-banner-connecting.png`) opaque `OBD2 RECONNECTING (#2)...` amber pill when adapter is unreachable in Wi-Fi mode, and clean auto-hide leaving pure `#000000` once Simulator connects (`v5-09a-hud-connected-clean.png`).
- **Maximized Radial Dial & Vertical Swipe (`VerticalPager`):** Verified (`v5-09a-hud-connected-clean.png`) `1273 RPM` centered inside the arc mouth with reclaimed height, and vertical swipe switching to Page 2 (`v5-09e-vertical-page2.png`) with the 4-dot vertical indicator.
- **Multi-Stop Pause / Resume / End -> Discard:** Verified (`v5-09b-hud-rec-dual.png`, `v5-09c-hud-paused.png`, `v5-09d-save-modal.png`) transitioning `REC · 00:03 · 0.0 km` to `PAUSED · 01:37 · 2.0 km` (amber Pause pill, blue `▶` Resume button, frozen `TRIP TIME 01:37`), resuming to `01:39` in `Save Recorded Trip Log?`, and discarding so `5 Run(s)` and `116949 km` remain intact on final Home (`192.168.0.10:35000 · Wi-Fi`, `v5-10-final-home.png`). `adb -s 3646fbc7 logcat -b crash -d` returned empty output.
- **Remaining Physical Car Check:** Live ECU telemetry polling rate on both the `2014 Avanza 1.3L 4AT` (`K3-VE`) and `2024 Avanza 1.5G MT` (plus running `ECU PIDs -> Deep ECU Scan` for `01C0` PIDs on the 2024 ECU) awaits physical adapter connection inside the vehicles.
