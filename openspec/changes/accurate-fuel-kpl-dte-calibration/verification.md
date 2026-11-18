# Verification — `accurate-fuel-kpl-dte-calibration`

Verified on working tree of branch `feat/torque-evo-obd-app` on `2026-11-18`.

## Automated Checks Passed

- **Unit Tests (`:obd-core:test`):** Passed (`31/31` tests, `0` failures, `0` skipped)
  - `FormulaEvaluatorTest`: 3/3
  - `DtcDecoderTest`: 4/4
  - `ObdPidRegistryTest`: 7/7 (added `0103` Fuel System Status 2-byte and `0134` WR Lambda 4-byte tests)
  - `TelemetryCalculatorTest`: 11/11 (added AT vs MT DFCO suppression at `1300 RPM`, `AIRFLOW_CONTINUOUS` check, idle-inclusive `allTimeTotalAvgKpl` & DTE cap, cold enrichment under `+3.5%` LTFT, and bounded `computeCalibratedTrimPct` tests)
  - `EngineAndPerformanceTest`: 6/6
- **Unit Tests (`:app:testDebugUnitTest`):** Passed (`10/10` tests, `0` failures, `0` skipped)
  - `HudGridLayoutTest`: 3/3
  - `TripExporterTest`: 7/7 (includes idle-inclusive `VehicleProfileEntity.allTimeAvgKpl()` verification)
- **Android Lint (`:app:lintDebug`):** Passed (`0` errors, `51` warnings)
- **Debug APK Build (`:app:assembleDebug`):** Passed (`app/build/outputs/apk/debug/app-debug.apk`)
- **OpenSpec Strict Validation (`openspec validate --all --strict`):** Passed (`4/4` changes passed, `0` failed)

## On-Device Verification (`RMX1921` / `3646fbc7`, in-place `adb install -r`, zero `pm clear`)

- **Room Migration (`6 -> 7`) & Data Preservation (`v6-01b-home-settled.png`, `v6-05-final-home.png`):** Verified `Avanza 1.3L` migrated cleanly (`1300 cc · AT · MPV · 1175 kg · Tank 44.8/45 L`, `116949 km` odometer, all `4 Page(s)`, all `5 Run(s)`, and `158 MB` `mbgl-offline.db` preserved), while stale pre-fix `12.3 km/L` All-Time Avg and `15.85 km/L` (`710 km`) DTE reset cleanly to `— km/L` and `— km` ready for fresh calibrated accumulation.
- **Extended Profile Trim & Refuel Controls:** Verified `Fuel Calibration Trim (%)` (`100.0`) inside `Vehicle Profile` (`v6-02b-vehicle-trim-bottom.png`), `Full Tank Fill-Up` + `Auto-Calibrate Fuel Trim (%)` toggles (`Current 100.0% · OBD burned since fill: 0.0 L`) inside `Refuel Checkpoint Log` (`v6-03-refuel-autocal.png`), and `Coasting Fuel Cut (DFCO)` (`ECU DFCO Cut (99.9)` / `Continuous Airflow (20–50)`) inside Settings (`v6-04-settings-coasting.png`). Crash log (`adb -s 3646fbc7 logcat -b crash -d`) returned empty.
- **Remaining Physical Car Check:** Live ECU telemetry accumulation (`allTimeTotalAvgKpl`, `recentDteKpl`, and `0103`/`0134` status confirmation) on both the `2014 Avanza 1.3L 4AT` and `2024 Avanza 1.5G MT` awaits the owner's next road drives.
