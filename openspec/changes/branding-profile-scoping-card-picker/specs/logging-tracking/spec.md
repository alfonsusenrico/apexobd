# logging-tracking Specification

## Purpose
Per-vehicle scoping for saved trip sessions, legacy average-speed derivation (`AVG / TOP SPEED`), and customizable up-to-6-metric Strava-style Share Route PNG cards with interactive preview, component checkboxes, background modes, and `APEXOBD` decal header.

## ADDED Requirements

### Requirement: Per-Vehicle Scoped Saved Trip Sessions
`TripDao` and `HudViewModel.tripSessions` SHALL filter saved trips strictly by the active `VehicleProfileEntity.id` via `observeSessionsForVehicle(vehicleId)` so saved run lists, exports, and home summaries reflect only the currently selected vehicle.

#### Scenario: Viewing Saved Runs after switching active vehicles
- **WHEN** the user switches active vehicle from `Avanza K3VE` (with 5 saved trips) to `Avanza 1.5G MT` (with 0 saved trips)
- **THEN** `Saved Runs` and `Last Saved Run` reflect only `Avanza 1.5G MT`'s sessions rather than mixing runs from `Avanza K3VE`

### Requirement: Customizable Strava Share Card Metrics (Max 6)
Users SHALL be able to choose up to `6` Share Route Card metrics, defaulting to `Distance`, `Trip Time`, `Avg Economy`, `Avg / Top Speed`, `Peak RPM`, and `Trip Efficiency` (formatted as `%`), with optional telemetry metrics including `Peak / Avg HP`, `Peak Torque`, `Fuel Used`, `Moving KPL`, `Coolant Peak`, `Max Throttle`, `Max G-Force`, `Avg Voltage`, and `Idle Time`.

#### Scenario: Customizing Share Card metrics with calculated power and torque
- **WHEN** the user selects `Peak / Avg HP` and `Peak Torque` up to the 6-slot limit
- **THEN** the Share Route PNG displays the chosen 6 metrics computed from the trip session and its stored `TelemetrySampleEntity` payload

### Requirement: Interactive Share Card Preview & Layer Checkboxes
Before exporting a Share Route PNG, the app SHALL display a live preview dialog allowing the user to select `Car Photo`, `Plain Dark`, or `Transparent` background and toggle checkboxes for `App Brand Icon`, `Vehicle Name`, `Vehicle Specs`, `Date`, `Time`, `Route Line`, `Start/End Markers`, `Metrics Backing Card`, and `Metrics`.

#### Scenario: Previewing and exporting a transparent component-only Share Card
- **WHEN** the user taps `Share Card` on a saved run, selects `Transparent` background, unchecks `Date` and `Metrics Backing Card`, and taps `Share PNG`
- **THEN** the live preview updates immediately and exports an `ARGB_8888` transparent PNG containing only the checked layers and selected metrics

### Requirement: Legacy Avg Speed Fallback & Share Card Header Cleanup
`TripCardRenderer` SHALL replace `"APEXOBD · ROUTE LOG"` with the slanted `APEXOBD` decal, rename `"AVG / MAX SPD"` to `"AVG / TOP SPEED"`, and compute fallback average speed (`distanceKm / (effectiveDurationSec / 3600)`) whenever legacy sessions store `avgSpeedKmh <= 0.1`.

#### Scenario: Exporting legacy Trip #1 showing derived average speed
- **WHEN** a legacy trip recorded with `avgSpeedKmh = 0.0`, `distanceKm = 11.247`, `duration = 37:59`, and `maxSpeedKmh = 47.7` is rendered as a Share Route PNG
- **THEN** the card header shows the slanted `APEXOBD` decal with no `"· ROUTE LOG"` suffix and displays `AVG / TOP SPEED` as `18 / 48 km/h`
