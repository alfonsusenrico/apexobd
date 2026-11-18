# logging-tracking Specification

## Purpose
Align All-Time Avg, Estimated Range (DTE), and Refuel pump-calibration tracking with real full-to-full tank economy including idle fuel.

## ADDED Requirements

### Requirement: Idle-Inclusive All-Time Economy and Conservative DTE
`VehicleProfileEntity` and `FuelEconomyTracker` SHALL accumulate total distance and total fuel burned (both moving and idle when engine `RPM > 200` and unpaused) for `All Time Avg` and `Est. Range (DTE)`, capping DTE economy slices at `28.0 km/L` so standstill traffic and warm-up are reflected accurately alongside real refuel checks.

#### Scenario: Urban idle fuel lowers All-Time Avg and Estimated Range toward pump reality
- **WHEN** a vehicle burns fuel during stationary traffic idling alongside moving commutes
- **THEN** `All Time Avg` (`km/L`) and `Est. Range` (`km`) incorporate the idle fuel rather than remaining inflated at road-only cruise levels

### Requirement: Exposed Fuel Trim & Optional Full-Tank Pump Auto-Calibration
`VehicleProfileSheet` SHALL expose `Fuel Calibration Trim (%)` (`mafTrimPct`) and `RefuelLogSheet` SHALL provide an optional full-tank auto-calibration option comparing actual pump liters against OBD fuel consumed since the last full tank fill.

#### Scenario: Recording a full-tank refuel tunes mafTrimPct boundedly
- **WHEN** a user logs a full-tank refuel with auto-calibration enabled after consuming `>= 5.0 L` since the previous full checkpoint
- **THEN** `mafTrimPct` adjusts smoothly toward `pumpLiters / obdFuelUsedSinceFull` within bounded safety limits (`75%..135%`)
