# Technical Design — Popped HUD Labels, Empty-Default Spectrum Boxed Bars & Low-Latency Smoothing

## 1. High-Contrast HUD Labels (`TorqueEvoTheme.kt`, `TorqueEvoWidgets.kt`)
- Add `labelColor: Color` (`0xFFEDF2F8` in `OLED_BLUE`, scaled cleanly per theme) to `HudVisualPalette` and boost `textSecondary` legibility slightly (`0xFFA0ABB8` in `OLED_BLUE`).
- Render widget headers across all display types (`AnalogDialWidget`, `SemiDigitalArcWidget`, `DigitalReadoutWidget`, `BarGaugeWidget`, `SegmentedBarGaugeWidget`, `SparklineChartWidget`, `FuelControlWidget`, `DtcCheckerWidget`, `PerfTimerWidget`, `DataLoggerWidget`) in `FontWeight.ExtraBold` with `palette.labelColor`.

## 2. Boxed Bar Spectrum & `220` Speed Scale (`TorqueEvoWidgets.kt`, `ObdPidRegistry.kt`, `TelemetryCalculator.kt`, `HudViewModel.kt`)
- Set default `maxValue = 220.0` for `obd_speed` (`010D`) and `gps_speed`.
- Add `segmentedSpectrumColor(fraction)` interpolating smoothly via Compose `lerp`: `0.00 -> #00A8FF (Blue)`, `0.25 -> #22C55E (Green)`, `0.50 -> #FACC15 (Yellow)`, `0.75 -> #F97316 (Orange)`, `1.00 -> #EF4444 (Red)`.
- In `SegmentedBarGaugeWidget`, render all segments when `value == null` or `pct <= 0.004f` as neutral unlit empty dark boxes (`Color(0x14FFFFFF)` fill + subtle `Color(0x1EFFFFFF)` outline) instead of pre-colored phantom amber/red segments.

## 3. Low-Latency Polling & Anti-Stall Smoothing (`ObdPidRegistry.kt`, `ObdPollingEngine.kt`, `TelemetryCalculator.kt`)
- Promote `04` (`engine_load`), `11` (`throttle`), `4C` (`commanded_throttle`), and `0B` (`map`) to `PollTier.HIGH` in `ObdPidRegistry`.
- Inside `ObdPollingEngine.runPollingLoop`, admit `PollTier.HIGH` PIDs to `highTargets` whenever visible on HUD (`requested`) OR required for core air/fuel derivation (`rpm`, `obd_speed`, active throttle, `maf` or `engine_load`/`map` when `maf` unsupported).
- Poll `0103` (`fuel_system_status`) every 2nd cycle and batch up to 2 visible secondary gauges per slow slot.
- On single-PID adapters (`supportsMultiPid == false`), interleave extra `rpm` once mid-cycle instead of flooding every 2nd step so speed/throttle/load finish cycles ~2x faster.
- Remove double `animateFloatAsState` spring from `SemiDigitalArcWidget`.
- In `TelemetryDisplaySmoother.AdaptiveSession`, ignore steady plateau gaps (`gapMs > 260L -> 95.0`) so `avgPeriodMs` does not balloon to `480 ms` when cruise/throttle holds steady, keeping responsive smoothing within `55..155 ms`.
