# hud-canvas Specification Delta

## ADDED Requirements

### Requirement: Continuous 60 FPS Predictive Map Gliding Across 1 Hz GPS Fixes
`DualMapCanvas` in follow-navigation mode SHALL advance the camera and cursor continuously on every display VSync frame using constant-velocity dead-reckoning (`speedKmh` + `bearingDeg`, bounded to `<= 1.4 s` since the last GPS fix) with soft phase-locked error correction so map motion stays smooth across 1 Hz GNSS updates instead of stepping and pausing.

#### Scenario: Moving vehicle at 54 km/h glides steadily across the full 1000 ms gap between GPS fixes
- **WHEN** a vehicle moves at `54 km/h` (`15 m/s`) northbound with `1 Hz` GPS updates
- **THEN** `MapMotionGlider` advances continuously across the full `0..1000 ms` interval without stalling in the second half of each second and smoothly absorbs minor GPS fix offsets
