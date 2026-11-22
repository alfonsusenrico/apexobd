# obd-protocol Specification

## ADDED Requirements

### Requirement: Responsive High-Tier Polling & Bounded Display Smoothing for Speed, Throttle & Load
`ObdPidRegistry`, `ObdPollingEngine`, and `TelemetryDisplaySmoother` SHALL schedule visible or core driving controls (`rpm`, `obd_speed`, `rel_throttle`, `throttle`, `engine_load`, `map`, `commanded_throttle`) in the high-priority polling tier, poll `0103` every 2nd cycle when fuel metrics are active, avoid steady-hold duration inflation in `AdaptiveSession`, and avoid redundant widget spring stacking.

#### Scenario: Rapid pedal input after steady cruise on single-PID CAN ECU
- **WHEN** the driver holds steady throttle or speed for >2 seconds and then rapidly presses or releases the accelerator on a 2024 Avanza MT (single-PID fallback)
- **THEN** `engine_load`, `throttle`/`rel_throttle`, and `obd_speed` poll in every fast cycle without slow-tier starvation and `AdaptiveSession` completes visual transition cleanly within `<= 160 ms` without 480 ms cruise-plateau lag
