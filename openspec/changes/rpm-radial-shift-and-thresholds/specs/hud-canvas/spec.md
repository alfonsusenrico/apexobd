# Capability: hud-canvas

## ADDED Requirements

### Requirement: Dedicated RPM Radial Tachometer Models
RPM widgets (`rpm` and `cluster_rpm`) SHALL offer four dedicated selectable radial tachometer styles (`RPM_TACH_GT`, `RPM_TACH_F1_LEDS`, `RPM_TACH_CHRONO`, and `RPM_TACH_HALO`) in addition to standard widget styles. Each model SHALL preserve the active Electric Blue sweep below redline and display a permanent Red redline band scaled to the active vehicle's `redlineRpm`.

#### Scenario: Selecting an RPM sensor in the component picker
- **WHEN** the user picks `rpm` or `cluster_rpm` in the Add Component picker
- **THEN** the four dedicated RPM radial styles appear first in the Visual Style selector and the default selection switches to `RPM_TACH_GT`

#### Scenario: Rendering Track Chrono shift indicator placement
- **WHEN** `RPM_TACH_CHRONO` renders with a configured shift RPM
- **THEN** the shift dot renders inside the dark open-center chrono ring at the top-right of the RPM digits with no `"SHIFT"` or `"FLASH"` text label

### Requirement: Optional Progressive RPM Shift Lamp
RPM tachometer widgets SHALL keep the shift lamp disabled unless the user configures a shift RPM threshold. When configured, only the shift dot or crown LEDs SHALL progress from Off (`<60%`) to steady Green (`60-79%`), steady Yellow (`80-99%`), and rapid flashing Red (`>=100%` of `min(shiftRpm, redlineRpm)`), with no text label on the lamp.

#### Scenario: Shift lamp disabled by default
- **WHEN** an RPM widget has `critThreshold == null`
- **THEN** `computeShiftLampStage` returns `OFF` and no flashing strobe timer runs

#### Scenario: Shift lamp progresses through green, yellow, and flashing red
- **WHEN** an RPM widget has `shiftRpm = 6000` and `redlineRpm = 6500`
- **THEN** the shift lamp is `OFF` at 3500 RPM, `GREEN` at 3600 RPM, `YELLOW` at 4800 RPM, and `RED_FLASH` at 6000 RPM

### Requirement: Optional Per-Widget Warning and Critical Thresholds
Non-special telemetry widgets SHALL allow users to optionally configure warning and critical thresholds with explicit `Above (>=)` or `Below (<=)` comparison direction (`alertBelow`) in active display units. Thresholds SHALL remain unset (`null`) by default with zero alert overlay until explicitly configured.

#### Scenario: High coolant temperature warning and critical alert
- **WHEN** a Coolant widget has `warn = 95`, `crit = 105`, and `alertBelow = false`
- **THEN** the widget displays no alert below 95, steady Amber warning framing and dot at 95, and flashing Red critical framing and dot at 105

#### Scenario: Low fuel or battery alert below threshold
- **WHEN** a Fuel Level widget has `warn = 15`, `crit = 8`, and `alertBelow = true`
- **THEN** `evaluateMetricAlert` returns `WARN` at 14.5% and `CRITICAL` at 7.5% without relying on hardcoded numeric cutoffs
