# logging-tracking Specification

## Purpose
Background Strava-style run recording, multi-sensor derived fuel/torque/efficiency telemetry, all-time vehicle profiles, tank/range tracking, and independent refuel checkpoint logs.

## ADDED Requirements

### Requirement: Foreground Strava-Style Run Logging, All-Time Odometer, & Export
The system SHALL track per-run GPS routes, distance, fuel used, `KPL Trip`, and IMU metrics via an Android Foreground Service, SHALL accumulate persistent global `All time avg` fuel economy and `All-Time Odometer` per `VehicleProfile` (`Gasoline`, `Diesel`, `Electric`), and SHALL export run logs to CSV/JSON using active global units.

#### Scenario: Recording and exporting a run log
- **WHEN** the user toggles Start Run, drives, taps Stop Run, and selects Export CSV or JSON
- **THEN** the per-run distance, fuel, `KPL Trip`, GPS polyline, and telemetry samples persist in Room and export in the selected unit system

### Requirement: Multi-Sensor Fuel Economy, Persisted Idle Pause, Dual-EWMA DTE, & Torque/HP Physics
The system SHALL fuse supported ECU PIDs, IMU, and GPS signals to compute `KPL` (`All time avg`), `KPL Inst`, `KPL Trip`, `Eff.`, `Fuel Left`, `Est. Range`, `Torque`, and `Power` according to `Gasoline/Diesel/Electric` profiles while honoring persisted `Pause Fuel Avg` and tank depletion. `Est. Range` SHALL return empty (`null` / `—`) without fuel-economy history and otherwise use moving-only Dual-Window EWMA blending with idle fuel deduction and smoothing.

#### Scenario: Persisted idle fuel pause protects KPL All Time Avg and blank DTE without data
- **WHEN** a fresh profile has zero consumption history (`0.0 km/L` avg), `Est. Range` displays `—`; and when driving begins and Pause Fuel Avg is enabled while idling
- **THEN** active EWMA DTE computes distance-to-empty from real remaining fuel and blended consumption while stationary idling deducts fuel burned without dragging down `All time avg` (`KPL`)

### Requirement: Independent Refuel Checkpoint Log
The system SHALL record Refuel entries (`volume`, `price`, `currency`, `RON`, optional `fuelName`, `odometerKm`, timestamp) per vehicle profile, calculate refuel-cycle distance and fuel economy exclusively between consecutive refuel checkpoints without altering OBD `All time avg` or per-run calculations, and credit added fuel to `Estimated Fuel Left`.

#### Scenario: Logging consecutive refuels computes cycle average independently
- **WHEN** the user logs a refuel of `24.1 L` (`IDR 325,350`, `RON 95`, odometer `48,412 km`) after a previous checkpoint at `48,077 km`
- **THEN** the log displays `335.0 km` cycle distance and `13.9 km/L` cycle average while keeping `All time avg` untouched and adding `24.1 L` to tank remaining fuel
