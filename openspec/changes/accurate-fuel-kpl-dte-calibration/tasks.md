# Tasks

> Status: Approved by owner (`2026-11-18`) and verified across unit tests (`41/41`), Android lint (`0` errors), debug APK build, and live `RMX1921` device verification (`verification.md`).

## Phase 1 — Protocol, DFCO & Core Fuel Model (`obd-core`)
- [x] 1. Decode SAE `0103` (`fuel_system_status`) and `0134` (`o2_b1s1_lambda`) in `ObdPidRegistry` and expose optional fuel-status / O2 inputs in `FuelEconomyTracker.update` and `ObdPollingEngine`.
- [x] 2. Update `FuelEconomyTracker` to include idle fuel in `allTimeTotalFuelLiters` and idle-inclusive `recentDteKpl` (capping slices at `28.0 km/L`), preserve road-only metrics separately, tighten `AT` vs `MT` DFCO thresholds (with `mapKpa != null` vacuum check and optional `AIRFLOW_CONTINUOUS` coasting mode), fix `coldFactor` (< `70°C` bug), update gasoline density to `732.0 g/L`, and track `fuelUsedSinceFullRefuelLiters`.
- [x] 3. Add focused unit tests in `TelemetryCalculatorTest` and `ObdPidRegistryTest` verifying AT vs MT DFCO suppression at `1300 RPM`, idle-inclusive All-Time Avg & DTE stability, cold enrichment with `+3%` LTFT, and `0103` decoding.

## Phase 2 — Room Schema, Refuel Auto-Calibration & UI (`app`)
- [x] 4. Add Room `MIGRATION_6_7` in `ApexDatabase` (`allTimeTotalDistanceKm`, `allTimeTotalFuelLiters`, `fuelUsedSinceFullRefuelLiters` on `vehicle_profiles`, and `isFullTank` on `refuel_logs`), cleanly recalculating/resetting the dev `Avanza 1.3L` inflated `recentDteKpl` (`15.85`) without touching the 5 saved trips.
- [x] 5. Expose `Fuel Calibration Trim (%)` (`mafTrimPct`) in `VehicleProfileSheet`, `Coasting Fuel Cut (DFCO)` preference in `SettingsSheet`, and `"Full Tank Fill-Up"` + `"Auto-Calibrate Fuel Trim"` support in `RefuelLogSheet` + `HudViewModel`.
- [x] 6. Update Home `All time avg` & `Est. Range` bindings (`HomeMenuScreen.kt`) to read total fuel economy (including idle) and conservative DTE.

## Phase 3 — Verification & Replay Against Real Drives
- [x] 7. Validate updated formulas against `TelemetryCalculatorTest`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and `openspec validate --all --strict`, install in-place on `RMX1921` (`adb install -r`, zero `pm clear`), and verify realistic `Avanza 1.3L` All-Time Avg, `Est. Range`, and zero crash logs.
