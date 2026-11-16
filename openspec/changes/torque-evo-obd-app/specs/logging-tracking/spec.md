# logging-tracking Specification

## Purpose
Background telemetry recording, GPS route tracking, HP/Torque & 0–100 km/h performance metrics, and CSV/JSON export.

## ADDED Requirements

### Requirement: Foreground Telemetry & GPS Session Logging
The system SHALL provide a toggleable logging session (backed by an Android Foreground Service when recording) that writes timestamped PID snapshots and GPS coordinates (`lat`, `lon`, `gpsSpeedKmh`) to Room/SQLite.

#### Scenario: Recording and exporting a trip log
- **WHEN** the user starts logging, records live samples, stops logging, and taps Export CSV or Export JSON
- **THEN** the app generates a valid CSV or JSON file containing timestamps, GPS coordinates, and all logged sensor columns and opens the Android share sheet

### Requirement: Performance Tracking (0–100 km/h & Calculated HP / Torque)
The system SHALL compute real-time `0–100 km/h` acceleration runs using synchronized GPS/OBD speed, and SHALL estimate instantaneous Horsepower (`HP`) and Torque (`Nm`) from ECU Torque PIDs (`0162`/`0163`) when supported or MAF (`0110`) + RPM (`010C`) fallback.

#### Scenario: 0–100 km/h run trigger and completion
- **WHEN** the timer is armed at `0 km/h` and vehicle speed transitions from `> 1 km/h` to `>= 100 km/h`
- **THEN** the timer captures elapsed seconds accurately and records the best run
