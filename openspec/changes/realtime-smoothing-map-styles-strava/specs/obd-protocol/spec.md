# obd-protocol Specification

## Purpose
Low-latency priority CAN polling (`12–20 Hz`), single-frame ISO 15765 packing, extended `01C0` PID discovery for newer vehicles, and selectable low-lag UI gauge damping without altering raw logged telemetry.

## ADDED Requirements

### Requirement: Wire-First Tiered Fast Polling & Single-Frame CAN Packing
`Elm327Client` and `ObdPollingEngine` SHALL minimize adapter turnaround by applying tight adaptive CAN timeouts, limiting multi-PID requests to single-frame payloads, focusing high-tier polling on rapid driver sensors (`rpm`, `rel_throttle`, `obd_speed`, `maf`), and supporting `01C0` PID discovery.

#### Scenario: Rapid throttle lift-off on CAN adapters
- **WHEN** `rpm` and `rel_throttle` (or `throttle` fallback) are active on a CAN vehicle even alongside fuel economy and coolant tiles
- **THEN** background sensors do not block every cycle, core driver-input sensors refresh at multi-fold higher frequency, and raw `telemetry_samples` captures true high-rate transients without added software lag

### Requirement: Selectable Zero/Low-Lag Display Smoothing Mode
The HUD visual pipeline SHALL expose selectable `Gauge Smoothing` behavior (`Light 40ms` default damper on radial needles/bars, `Raw Instant 0ms` zero-filter pass-through, or `Fluid Glide` interval-paced ramp) strictly on the display layer without modifying raw `_liveValues`, `telemetry_samples`, or fuel/trip math.

#### Scenario: Switching gauge smoothing between Raw Instant and Light Damper
- **WHEN** the user selects `Raw Instant (0ms)` or `Light (40ms)` under Settings
- **THEN** numeric and needle readouts reflect incoming fast-tier polls immediately without double compose-animation lag while SQLite logs store exact unmodified ECU values
