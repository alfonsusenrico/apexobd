# hud-canvas Specification

## Purpose

Formats speed widgets as unrounded integers, surfaces `0.01` resolution on fractional dynamic percentages/KPL, prompts immediately when entering the HUD with GPS disabled, and recovers live GPS fixes cleanly upon re-enabling Location.

## ADDED Requirements

### Requirement: Truncated Integer Speed Formatting & Precision Fractional Telemetry
`formatTelemetryValue` SHALL format speed channels (`gps_speed`, `obd_speed`, or unit `"km/h"`/`"mph"`) strictly as truncated whole integers (`floor(value).toInt()`, no rounding and no decimal point), while dynamic fractional percentage and fuel-economy channels display `2` decimal places (`%.2f`) so 60 Hz interpolation stays visually continuous.

#### Scenario: `gps_speed` displays unrounded integers at all speeds
- **WHEN** `gps_speed` reports `42.94` or `99.8` `km/h`
- **THEN** HUD readouts display `"42"` and `"99"` with zero decimal places and no upward rounding

### Requirement: Immediate HUD GPS-Off Warning & Auto-Recovery
Upon entering `HudCanvasScreen`, the HUD SHALL inspect live system Location state (`LocationTracker.isGpsEnabled`), prompt the driver immediately with a warning and `"Enable GPS"` action if Location is disabled, and restart high-accuracy FusedLocation updates automatically as soon as Location is enabled.

#### Scenario: Entering HUD with system Location disabled displays enable action
- **WHEN** the driver opens the HUD Dashboard while Android Location is turned off
- **THEN** an immediate warning banner with an `"Enable GPS"` button appears and clears automatically once Location is enabled and resumed
