# Verification — `hud-themes-bars-centralized-trip`

Verified on working tree of branch `feat/torque-evo-obd-app` on `2026-11-17`.

## Automated Checks Passed

- **Unit Tests (`:obd-core:test --rerun-tasks`):** Passed (`27/27` tests, `0` failures, `0` skipped)
  - `FormulaEvaluatorTest`: 3/3
  - `DtcDecoderTest`: 4/4
  - `ObdPidRegistryTest`: 5/5
  - `TelemetryCalculatorTest`: 9/9 (includes `DerivedSensorCatalog` labels & specs, `TripRunCalculator` standby/integration/no-double-count checks, and `TelemetryDisplaySmoother` lerp clamping/target/reset checks)
  - `EngineAndPerformanceTest`: 6/6
- **Unit Tests (`:app:testDebugUnitTest --rerun-tasks`):** Passed (`9/9` tests, `0` failures, `0` skipped)
  - `HudGridLayoutTest`: 3/3
  - `TripExporterTest`: 6/6 (includes trip summary JSON export fields, 7 HUD theme presets + mix-and-match resolver, `OfflineMapManager` bounds/PMTiles style, `LocationTracker` reset logic, and `fitDigitsFontSizeSp` / `fitLabelFontSizeSp` sizing bounds)
- **Android Lint (`:app:lintDebug`):** Passed (`0` errors/fatal, `51` warnings)
- **Debug APK Build (`:app:assembleDebug`):** Passed (`app/build/outputs/apk/debug/app-debug.apk`, `59M`)
- **OpenSpec Strict Validation (`openspec validate --all --strict`):** Passed (`2/2` changes passed, `0` failed)

## On-Device Verification (`RMX1921` / `3646fbc7`, in-place `adb install -r`, zero `pm clear`)

- **Real Commute Log Preserved & Verified:** `Last Saved Run · 17 Nov` (`11.2 km · 12.1 km/L · 37 min`) and `Saved Runs (1)` detailed card (`17 Nov 16:45`, `11.2 km · 37:59 · 12.1 km/L`, `Fuel 0.93 L · Max 48 km/h · Peak 2641 RPM · 9027 pts`) plus vector map blue route polyline observed on Home & Saved Runs (`v4-01-saved-runs.png`, `v4-04-settings-top.png`).
- **HUD Layout, Font Sizing, Dial & High-Contrast Nav Map:** Verified live in Demo Simulator (`v4-05c-hud-sim-fit.png`): `1×1` cells show untruncated labels (`REL THR`, `ABS THR`, `GPS SPD`, `ABS LOAD`, `VOLTAGE`) and complete digits (`40.8 %`, `49.0 %`, `14.2 V`, `105 km/h`), `KPL · AVG` fits `10.1 km/L` alongside its circular shutter toggle without clipping, `RPM` radial dial centers `3688 RPM` cleanly inside the arc mouth, and heading-up `42°` tilted MapLibre canvas shows crisp bright street lines.
- **Themes, Borders & Centralized Trip Stop Modal:** Verified all 7 presets + backdrop + card style + `Show Component Borders` controls in Settings (`v4-03c-settings-appearance.png`) and HUD dialog (`v4-08a-theme-dialog-fixed.png`), live `Glassy Dimmed` (`v4-08b-hud-glassy-live.png`), live borderless `Motorsport Crimson` (`v4-08c-hud-crimson-noborder.png`), active `REC · 00:03` trip metric activation (`v4-07a-rec-active.png`), and Stop `Save Recorded Trip Log?` modal with `Discard Trip` restoring `1 Run(s)` (`v4-07b-save-modal.png`, `v4-09b-home-check.png`).
- **Remaining Manual Step on Unplugged Device:** `3646fbc7` disconnected right after restoring themes (`v4-09b-home-check.png` still showed `192.168.0.10:35000 · SIM` on Home, and the pre-patch Simulator pass had incremented Home card odometer/fuel before `activeEngineIsSimulator` guard was installed in the final APK); owner should toggle `Demo OBD Simulator Mode` back OFF in `Configure ->` before connecting to the car adapter.
