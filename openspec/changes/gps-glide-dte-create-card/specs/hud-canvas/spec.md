# hud-canvas Specification

## Purpose

Delivers continuous 60 Hz inter-poll HUD telemetry motion, decoupled live GPS speed delivery, and smooth fractional LED segment progress on boxed bars so moving driving gauges feel responsive in real time without discrete poll stalls.

## ADDED Requirements

### Requirement: Decoupled GPS Live Emission & Continuous Inter-Poll Glide
`HudViewModel` and `TelemetryDisplaySmoother.AdaptiveSession` SHALL ingest real `locationTracker.gps` speed fixes independently of OBD polling cycles and interpolate rapid gauges smoothly across each channel's actual update interval while snapping promptly after steady plateaus or braking stops.

#### Scenario: 1 Hz GPS speed updates continuously without poll-freeze stalls
- **WHEN** `locationTracker.gps` emits `1 Hz` speed fixes under `LIGHT_DAMPER` or `ADAPTIVE_GLIDE`
- **THEN** `gps_speed` updates `_liveValues` without waiting for an OBD cycle and interpolates smoothly across the inter-fix period at `60 Hz` instead of settling in `<100 ms` and freezing flat until the next fix

#### Scenario: Pedal snap after a steady cruise plateau remains responsive
- **WHEN** throttle or engine load holds steady for `>1200 ms` and then jumps sharply
- **THEN** `AdaptiveSession` completes `>65%` of the transition within `110 ms` without sluggish multi-second lag

### Requirement: Fractional Frontier Fill in Segmented Boxed Bars
`SegmentedBarGaugeWidget` SHALL keep unlit boxes dark and neutral (`0x14FFFFFF`) while rendering fractional left-to-right progress on the leading active box along the `Blue -> Green -> Yellow -> Orange -> Red` spectrum so wide scales like `0..220 km/h` animate smoothly between whole blocks.

#### Scenario: Compact speed boxed bar transitions between integer block thresholds
- **WHEN** `gps_speed` (`0..220 km/h`, `12` or `18` boxes) increases continuously between `10 km/h` and `28 km/h`
- **THEN** completed boxes glow solid along the spectrum, future boxes stay dark empty outlines, and the active frontier box fills proportionally with the exact fractional value
