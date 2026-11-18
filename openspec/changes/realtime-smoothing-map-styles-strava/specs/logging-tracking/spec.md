# logging-tracking Specification

## Purpose
Pause/Resume continuous trip logging, crash-free CSV/JSON export, Strava-style PNG trip share card generation, and detailed vehicle metadata supporting physics modeling.

## ADDED Requirements

### Requirement: Pause and Resume Continuous Trip Recording
The centralized trip logger SHALL support `IDLE`, `RECORDING`, and `PAUSED` states via dual controls (`Pause/Resume` + `End`) on `MiniMapWidget`, persisting active driving duration (`activeDurationSec`) and excluding stopped pause gaps from avg speed, fuel delta, and trail stitching.

#### Scenario: Making a mid-trip stop and resuming the same run
- **WHEN** the user taps `Pause` at an intermediate stop and taps `Resume` when continuing
- **THEN** the timer and sample recording freeze during the pause (`PAUSED · mm:ss` amber indicator), resume accumulating onto the same `TripSessionEntity` without drawing a teleport line, and finalize one combined saved trip on `End`

### Requirement: Crash-Free CSV and JSON File Export
`TripExporter.shareFile` SHALL launch share chooser intents safely from `Application` view-model context with `Intent.FLAG_ACTIVITY_NEW_TASK`, `ClipData`, and `Dispatchers.Main` dispatch without throwing `AndroidRuntimeException`.

#### Scenario: Exporting CSV or JSON from Saved Runs on Android
- **WHEN** the user taps `CSV` or `JSON` on a saved run in `RunLogsAndMapsSheet`
- **THEN** the export file is written via `FileProvider` and the Android share chooser opens cleanly without crashing the app

### Requirement: Strava-Style Trip Route Image Export & Detailed Vehicle Profile
Users SHALL be able to store a local vehicle photo (`photoUri`), `engineCc`, `carType` (auto-tuning aero `Cd×A`), `transmission` (auto-tuning `drivetrainLossPct`), and `modelYear` (`MIGRATION_5_6`), and export any saved trip as a shareable high-contrast PNG route card overlaid on the vehicle photo (or dark OLED map backdrop when unset).

#### Scenario: Sharing a Strava-style trip image card with car photo background
- **WHEN** the user attaches a photo and specs (`Avanza 1.3L`, `1329 cc`, `MT`, `MPV`, `2019`) to their active car profile and taps `Share Card` on a saved trip
- **THEN** a rendered PNG overlays the GPS route path, key trip metrics (`Distance`, `Active Time`, `Avg Economy`, `Fuel`, `Avg/Max Speed`, `Peak RPM`), and vehicle badge over the darkened car photo and opens the share chooser cleanly
