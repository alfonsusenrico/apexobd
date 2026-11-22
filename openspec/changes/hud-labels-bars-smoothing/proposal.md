# Proposal

Status: **Approved by owner instruction on `2026-11-18`** ("component label shouldn't dimmed as it's hard to read, better make it popped up; speed, throttle position, engine load very delayed and still feels bad smoothing; boxed bars empty by default, speed max 220, smoothed colors blue/green -> yellow -> orange -> red").

## Why

1. **Dimmed HUD Widget Labels (`TorqueEvoTheme.kt`, `TorqueEvoWidgets.kt`):** Sensor titles used low-luminance `textSecondary` (`0xFF7E8796` on `#000000`), making component headers difficult to read in motion.
2. **Stalled Throttle/Load Polling & Cruise-Plateau Smoothing Lag (`ObdPidRegistry.kt`, `ObdPollingEngine.kt`, `TelemetryCalculator.kt`, `TorqueEvoWidgets.kt`):**
   - `0104` (`engine_load`), `0111` (`throttle`), `014C` (`commanded_throttle`), and `010B` (`map`) were assigned `PollTier.MEDIUM`, causing visible gauges on single-PID ECUs (including 2024 Avanza MT) to wait up to 6–12 cycles while `rpm` polled 2–3x per cycle.
   - `0103` (`fuel_system_status`) polled on `cycle % 6`, delaying post-release DFCO/KPL cut detection.
   - `TelemetryDisplaySmoother.AdaptiveSession` let steady cruise/hold gaps stretch subsequent ramp duration up to `480 ms`, while `SemiDigitalArcWidget` stacked an extra `animateFloatAsState` spring.
3. **Boxed Bar Coloring & Speed Scale (`TorqueEvoWidgets.kt`, `ObdPidRegistry.kt`, `TelemetryCalculator.kt`, `HudViewModel.kt`):** Unlit high-zone boxed bar segments showed phantom orange/red tint when empty/0, and Speed maxed at `240`–`260 km/h` instead of `220 km/h`.

## What Changes

- Promote `labelColor` in `HudVisualPalette` and apply bold high-contrast labels across all HUD widget styles.
- Set `obd_speed` and `gps_speed` max scale to `220.0 km/h`.
- Render `SegmentedBarGaugeWidget` completely empty dark unlit boxes by default (`value == null` or `<= min`) and paint lit segments along a smooth `Blue -> Green -> Yellow (mid) -> Orange -> Red (max)` spectrum.
- Promote live driving PIDs (`engine_load`, `throttle`, `commanded_throttle`, `map`) to `PollTier.HIGH` when visible or fallback-required, poll `0103` more frequently when fuel tracking is active, reduce single-PID RPM interleaving flood, remove duplicate animation spring in `SemiDigitalArcWidget`, and cap `AdaptiveSession` steady-hold period growth cleanly (`55..155 ms`).
