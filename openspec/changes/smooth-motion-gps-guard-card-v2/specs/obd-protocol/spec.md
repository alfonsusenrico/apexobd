# obd-protocol Specification

## Purpose

Delivers non-freezing 60 Hz continuous visual interpolation across dynamic OBD channels (`rpm`, `engine_load`, `throttle`, `rel_throttle`, `kpl_road_avg`, `kpl_instant`) plus an optional UI-only micro-pulse display test toggle without modifying logged telemetry records.

## ADDED Requirements

### Requirement: Full-Interval Continuous Glide Without Inter-Poll Freezes
`TelemetryDisplaySmoother.AdaptiveSession` SHALL track each channel across `>= 100%` of its observed update period using smooth continuous easing (`LIGHT_DAMPER` and `ADAPTIVE_GLIDE`) so rapid metrics transition uninterruptedly between polls instead of finishing early and holding flat plateaus, and SHALL include `kpl_road_avg` in `SMOOTHED_KEYS`.

#### Scenario: `engine_load` and `rel_throttle` glide across 500-1250 ms OBD intervals
- **WHEN** `engine_load` or `rel_throttle` emits sequential steps spaced `500 ms` apart under `LIGHT_DAMPER`
- **THEN** at `400 ms` after a step the display value is still actively progressing smoothly toward the target without freezing at the target early

#### Scenario: Optional UI micro-motion toggle leaves raw values untouched
- **WHEN** the UI-only `liveMicroMotion` option is disabled (default) or enabled in Settings
- **THEN** raw `TelemetrySampleEntity` persistence and fuel/trip math remain byte-for-byte unaffected by UI micro-motion
