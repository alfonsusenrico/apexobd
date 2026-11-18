# obd-protocol Specification

## Purpose
Accurate real-world DFCO verification, cold-enrichment gating, retail gasoline density, and optional `0103` / `0134` verification across both automatic and manual drivetrains.

## ADDED Requirements

### Requirement: Transmission-Aware ECU DFCO & Optional Continuous Mode
`FuelEconomyTracker` SHALL restrict automatic DFCO (`0.0 L/h` -> `99.9 km/L`) to closed-throttle deceleration with verified high manifold vacuum (`mapKpa != null && mapKpa <= baro * 0.28`), warm coolant (`>= 65°C`), and drivetrain-appropriate overrun thresholds (`>= 1700 RPM` and `>= 26 km/h` for `AT/CVT/DCT`, `>= 1500 RPM` and `>= 18 km/h` for `MT`), refined by PID `0103` (`fuel_system_status`) / O2 lambda when available, plus a selectable `AIRFLOW_CONTINUOUS` fallback mode.

#### Scenario: City 4AT coasting at 1300 RPM does not trigger false 99.9 km/L zero-fuel cut
- **WHEN** an `AUTOMATIC` vehicle slows down at `30 km/h` and `1300 RPM` with `rel_throttle <= 1.5%` and `MAP 28 kPa`
- **THEN** DFCO remains inactive and fuel flow continues proportional to measured airflow and trims rather than reporting `99.9 km/L`

### Requirement: Cold Enrichment & Fuel Density Accuracy
`FuelEconomyTracker` SHALL apply cold-start coolant enrichment (`coolant < 70°C`) whenever closed-loop trims are not actively subtracting fuel (`trimSumPct >= -3.0%`) and use realistic commercial gasoline density (`732.0 g/L`).

#### Scenario: Cold start with positive LTFT still applies warm-up fuel enrichment
- **WHEN** coolant is `35°C` and stored `LTFT` is `+3.5%`
- **THEN** `coldFactor > 1.0` applies without suppression by positive long-term trim memory
