# Proposal

Status: **Approved by owner on `2026-11-18`** (idle fuel included in All-Time Avg & DTE, strict transmission-aware DFCO + `0103`/`0134` verification with optional Torque-style toggle, cold-enrichment & gasoline density fix, exposed `Fuel Trim %` + full-tank refuel learning, and clean `MIGRATION_6_7` dev-profile baseline recalibration).

## Why

Auditing ApexOBD's fuel, KPL, and DTE calculations against real driving logs (`5` saved `2014 Avanza 1.3L 4AT` sessions in `apex_obd.db`, `21,285` samples) and full-to-full pump checkpoints (`9.5–10.5 km/L`) revealed why ApexOBD reported an optimistic `12.3 km/L` All-Time Avg and `710 km` Est. Range:

1. **Idle Fuel Excluded from All-Time Avg and Est. Range:** `FuelEconomyTracker` only adds moving samples (`speedKmh > 2.0`) to `allTimeRoadFuelLiters` (`12.27 km/L`) and `recentDteKpl` (`15.85 km/L`), completely dropping `7.6%–10.5%` stationary idle fuel inside recorded trips plus warm-up and standstill traffic idling.
2. **Premature Low-RPM DFCO Triggering (`99.9 km/L`) on 4AT City Overrun:** Triggering `fuelFlow = 0` at `speedKmh > 12.0` and `rpm > 1150.0` flagged `7.3%–16.3%` of urban 4AT driving at `1277–1358 RPM` and `28–41 km/h`, where an automatic torque converter unlocks and keeps injecting anti-stall fuel (`+4.7%` to `+10.4%` understated fuel per run).
3. **Runaway `recentDteKpl` (`15.85 km/L` -> `710 km`):** A moving-only 4 km EWMA sliced up to `45.0 km/L` pushed `recentDteKpl` to `15.85 km/L`, inflating `44.82 L × 15.85 = 710 km` against a real `~450 km` tank range.
4. **Cold Enrichment Gate Bug, Static `745 g/L` Density & Unexposed `mafTrimPct`:** `coldFactor` (< `70°C`) is skipped whenever `|STFT + LTFT| > 2%` (so `+2.8%..+4.6%` LTFT disables cold warmup enrichment), gasoline density ignores retail `RON 90/92` mass (`~732 g/L`), and `mafTrimPct` cannot be tuned from Vehicle Profile or Full-Tank Refuel checkpoints.

## What Changes

- **Total Tank Fuel Baseline (`All-Time Avg` & `Est. Range` Including Idle):** Accumulate `allTimeTotalFuelLiters` (moving + idle whenever engine `RPM > 200`, respecting recording/fuel pause) and switch Home `All Time Avg`, HUD `KPL · AVG`, and `recentDteKpl` (`Est. Range`) to use total fuel consumed, while retaining road-only moving KPL (`kpl_moving_avg`) as an optional diagnostic metric. Tighten DTE slice caps (`<= 28.0 km/L`), update DTE on total window fuel (including idle), and blend conservatively with the long-term baseline.
- **Accurate Transmission-Aware ECU DFCO + `0103` / Wideband Verification:** Raise DFCO entry thresholds so genuine overrun cuts (`99.9 km/L`, matching OEM MID behavior on downhill/highway coasting) require closed throttle, verified low MAP (`<= 28%` baro, never missing-MAP blind trigger), warm coolant (`>= 65°C`), and transmission-realistic overrun speed/RPM (`>= 1700 RPM` & `>= 26 km/h` for AT/CVT/DCT, `>= 1500 RPM` & `>= 18 km/h` for MT), reinforced when PID `0103` Fuel System Status or `0124`/`0134`/`0144` O2 sensors confirm fuel cut. Keep optional `Coasting Mode` (`Accurate ECU DFCO` vs `Raw Airflow / Torque-Style`).
- **Cold Enrichment Fix, Gasoline Density Calibration & Refuel Trim Learning:** Fix `coldFactor` so coolant `< 70°C` applies unless active negative trim indicates pull-back, use realistic `732 g/L` retail gasoline default (or RON-aware factor), expose `Fuel Trim (%)` (`mafTrimPct`) in `VehicleProfileSheet`, and add an optional **Full-Tank Refuel** calibration toggle in `RefuelLogSheet` that compares actual pump liters against OBD fuel burned since the previous full tank to tune `mafTrimPct` toward pump truth.
- **Dev Profile Recalibration (`MIGRATION_6_7`):** Migrate schema (`6 -> 7`) adding `allTimeIdleFuelLiters` / total fuel tracking (recalculating existing saved `Avanza 1.3L` baseline to realistic total economy with idle and resetting inflated `recentDteKpl = 15.85` without deleting the 5 saved commute trips).

## Capabilities

### Modified Capabilities
- `obd-protocol`: Decode SAE PID `0103` (`Fuel System Status`) and `0134` (`WR Lambda B1S1`) where supported, tighten transmission-aware DFCO verification, fix cold enrichment gating, refine gasoline density and fuel trim calibration.
- `logging-tracking`: Track total fuel (moving + idle) in `VehicleProfileEntity` and `FuelEconomyTracker`, align `Est. Range (DTE)` with idle-inclusive economy, expose `Fuel Calibration Trim (%)`, and support optional Full-Tank pump calibration from `RefuelLog`.

## Impact

- `obd-core`: `ObdPidRegistry.kt`, `TelemetryCalculator.kt`, `VehicleAndUnits.kt`, `ObdPollingEngine.kt`, unit tests (`TelemetryCalculatorTest`, `ObdPidRegistryTest`).
- `app`: `ApexDatabase.kt` (`MIGRATION_6_7`), `AppPreferences.kt` (optional DFCO coasting preference if enabled), `HudViewModel.kt`, `HomeMenuScreen.kt`, `HomeSheets.kt` (`VehicleProfileSheet`, `RefuelLogSheet`), and unit tests.
