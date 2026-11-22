# logging-tracking Specification Delta

## ADDED Requirements

### Requirement: One-Time ECU Distance & Fuel Baseline Calibration for Non-Sensor Vehicles
For vehicles without native fuel level (`012F` / `7C0_2129`) or cluster odometer (`7C0_21E1`) sensors, `VehicleProfileSheet` SHALL provide an ECU Distance & Fuel Baseline Calibration card (with a `?` guidance dialog) to lock the user's confirmed odometer and starting fuel (percentage or volume) against `0131` or `7E0 2131`, and SHALL automatically advance the odometer and deduct unlogged fuel burn on subsequent connects.

#### Scenario: Unlogged driving is automatically accounted for on next OBD connect
- **WHEN** a calibrated vehicle with baseline `O₀ = 117010.0 km` at `E₀ = 10890.0 km` is driven `50.0 km` without the phone connected and later reconnects with `E_now = 10940.0 km` and cycle economy `11.5 km/L`
- **THEN** the app updates the vehicle's odometer to `117060.0 km` and deducts `50.0 / 11.5 = 4.35 L` from `estimatedRemainingFuelLiters`

#### Scenario: Mode 04 DTC clear or ECU counter reset preserves odometer baseline
- **WHEN** `E_now` drops below `lastSeenEcuDistanceKm` after a fault code clear or battery reset
- **THEN** the app re-anchors `odometerAtBaselineKm` to the current `allTimeOdometerKm` and `ecuDistanceBaselineKm` to `E_now` without losing mileage

### Requirement: Full-to-Full Refuel Cycle KPL Recording and Estimation Baseline
`RefuelLogSheet` and `HudViewModel` SHALL record `cycleAvgKpl` on Full-Tank refuel checkpoints using distance since the previous Full-Tank checkpoint divided by total fuel added since that checkpoint (including intermediate partial refuels), and SHALL use this Full-to-Full Cycle KPL for Estimated Fuel Left and Estimated Range (DTE) without modifying real-time OBD All-Time KPL or Trip KPL metrics.

#### Scenario: Partial refuel followed by full-tank refuel computes true full-to-full cycle KPL
- **WHEN** a vehicle has a full-tank checkpoint at `117000.0 km`, adds a partial refuel of `10.0 L` at `117150.0 km`, and then logs a full-tank refuel of `20.0 L` at `117345.0 km`
- **THEN** the partial refuel records no cycle KPL while adding `+10.0 L` to estimated remaining fuel, and the full-tank refuel records `cycleDistanceKm = 345.0 km`, `cycleFuelLiters = 30.0 L`, and `cycleAvgKpl = 11.5 km/L` as the new cycle estimation baseline without altering real-time OBD All-Time KPL
