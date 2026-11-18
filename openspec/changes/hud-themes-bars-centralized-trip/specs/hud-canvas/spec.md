# hud-canvas Specification

## Purpose
HUD Canvas widget visual styles, KPL naming, circular shutter toggles, and theme/border quick controls.

## ADDED Requirements

### Requirement: Distinct Digital, Line Bar, and Boxed Bar Display Types
The HUD widget system SHALL distinguish pure numeric `Digital` tiles (tabular hero digits, label, unit, status dot, zero bottom progress bar) from continuous gradient `Line Bar` widgets (`WidgetDisplayType.BAR`) and segmented LED `Boxed Bar` widgets (`WidgetDisplayType.SEGMENTED_BAR` with threshold zone blocks and `min`/`mid`/`max` range labels).

#### Scenario: Switching sensor visual style between Digital, Line Bar, and Boxed Bar
- **WHEN** the user opens Component Studio or Edit Component on any numeric telemetry sensor
- **THEN** `Digital`, `Line Bar`, and `Boxed Bar` are selectable styles with distinct live visual previews and HUD rendering

### Requirement: KPL Component Naming & Circular Shutter Idle Toggle
Fuel economy components SHALL display short component names strictly as `KPL · AVG`, `KPL · TRIP`, and `KPL · INST`, and `FuelControlWidget` (`KPL · AVG`) SHALL place its idle-pause toggle in the bottom-right corner using the same `38dp` circular shutter-ring pattern as the Map widget record button.

#### Scenario: Toggling KPL · AVG idle pause via circular button
- **WHEN** the user views the `KPL · AVG` widget and taps its bottom-right circular shutter toggle
- **THEN** the idle-pause state flips between active tracking (`white/accent ring + solid telemetry dot`) and paused (`amber ring + || pause bars`) without clipping text

### Requirement: Field-Drive Ergonomics & Responsive Widget Sizing
Telemetry tiles SHALL auto-fit digits to prevent decimal truncation in compact `1×1` cells, SHALL anchor `AnalogDialWidget` readouts inside the circular arc mouth, and SHALL keep UI gauge interpolation (`TelemetryDisplaySmoother`) isolated from raw logged telemetry samples.

#### Scenario: Smooth HUD dial needle without polluting raw logged samples
- **WHEN** the vehicle accelerates rapidly with live telemetry running on the HUD Canvas
- **THEN** compact values retain full decimals, `Radial` aligns values inside the dial arc, and rapid gauges interpolate smoothly while SQLite `telemetry_samples` and fuel calculations use exact unmodified readings

### Requirement: Navigation-Style High-Contrast Dark Map & High-Tier Throttle Polling
Live HUD `MiniMapWidget` views SHALL use heading-up camera rotation with 3D forward tilt (`42°`), closer driving zoom (`16.2`), heightened dark street contrast, and live `REC` time/distance overlays, while `0145`/`0149`/`014A` throttle/pedal PIDs poll at `PollTier.HIGH`.

#### Scenario: Live navigation map heading and contrast while driving
- **WHEN** the user drives with the HUD Mini-Map visible and Run Recording active
- **THEN** the camera follows travel bearing at zoom `16.2` and `42°` tilt over bright contrast road lines and displays active elapsed clock and distance on the `REC` badge
