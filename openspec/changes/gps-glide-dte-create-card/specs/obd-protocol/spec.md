# obd-protocol Specification

## Purpose

Maintains responsive OBD PID polling during background Run Recording without duplicate high-priority channels and provides conservative, reserve-adjusted distance-to-empty (DTE) calculations consistent across live telemetry and vehicle summaries.

## ADDED Requirements

### Requirement: Lean Single-PID Recording Schedule & Secondary Gauge Cadence
`HudViewModel` and `ObdPollingEngine` SHALL keep background recording-only PIDs separate from visible HUD `HIGH`-tier urgency when primary equivalents (`rel_throttle` over `throttle`, `maf` over `map`) are already supported, and allocate faster rotation slots to visible live electrical/timing gauges (`timing_advance`, `voltage`).

#### Scenario: Run Recording with 16 visible HUD widgets on a single-PID ECU
- **WHEN** Run Recording runs on a single-PID vehicle supporting `0145` (`rel_throttle`) and `0110` (`maf`) while neither `throttle` (`0111`) nor `map` (`010B`) is displayed
- **THEN** redundant `0111` and `010B` are rotated in the background logger queue instead of `HIGH`-tier fast slots, and visible `timing_advance`/`voltage` poll significantly faster than `3.5 s`

### Requirement: Cumulative-Anchored DTE with Tank Reserve Cushion
`FuelEconomyTracker`, `HudViewModel`, and `HomeMenuScreen` SHALL compute `dteEconomyKpl` weighted conservatively toward `allTimeTotalAvgKpl` (preventing high-tank optimism spikes) and multiply by `max(remainingFuelLiters - reserveLiters, 0.0)` with a `10%` (`2..6 L`) tank safety reserve via shared helpers.

#### Scenario: High tank with prior highway cruise spikes and subsequent city ride
- **WHEN** a `45 L` vehicle holds `41.48 L` remaining, `allTimeTotalAvgKpl` is `11.65 km/L`, and `recentDteKpl` previously peaked near `13.8 km/L`
- **THEN** `HomeMenuScreen`, offline fallback, and `FuelMetricsSnapshot` report identical conservative `estimatedRangeKm` accounting for the `4.5 L` reserve and capped at `<= allTimeTotalAvgKpl` on >50% tank without exceeding raw fuel math (`528 km`)
