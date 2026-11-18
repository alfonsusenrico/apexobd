# hud-canvas Specification

## ADDED Requirements

### Requirement: High-Contrast Component Labels & Empty-Default Spectrum Boxed Bars
`TorqueEvoWidgets` SHALL render all HUD component titles with popped high-contrast `labelColor` (`ExtraBold`), set default speed upper bounds (`obd_speed`, `gps_speed`) to `220 km/h`, render `SegmentedBarGaugeWidget` segments uncolored/empty when `value == null` or at minimum (`pct <= 0.004f`), and interpolate active boxed bar segments smoothly from cool blue/green through yellow at middle and orange to red at maximum.

#### Scenario: Viewing speed boxed bar at 0 and at increasing road speeds
- **WHEN** `SegmentedBarGaugeWidget` displays `obd_speed` or `gps_speed` at `0 km/h` or offline (`null`)
- **THEN** all segments render as empty dark unlit boxes and the upper tick reads `220` (`km/h`), while active acceleration lights segments smoothly along Blue -> Green -> Yellow (around `110`) -> Orange -> Red (`220`)
