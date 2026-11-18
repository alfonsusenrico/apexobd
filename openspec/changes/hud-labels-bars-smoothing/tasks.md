# Tasks

- [x] 1. Add popped `labelColor` token to `HudVisualPalette` in `TorqueEvoTheme.kt` and apply crisp `ExtraBold` high-contrast labels across all widget composables in `TorqueEvoWidgets.kt`.
- [x] 2. Set `obd_speed` and `gps_speed` max scale to `220.0 km/h` in `ObdPidRegistry.kt`, `TelemetryCalculator.kt`, and `HudViewModel.kt`.
- [x] 3. Update `SegmentedBarGaugeWidget` to render empty dark unlit boxes by default (`null` or `pct <= 0.004f`) and compute smooth multi-stop `Blue -> Green -> Yellow -> Orange -> Red` segment fills via `segmentedSpectrumColor`.
- [x] 4. Promote `engine_load`, `throttle`, `commanded_throttle`, and `map` to `PollTier.HIGH`, optimize single-PID cycle scheduling & `0103` DFCO status cadence in `ObdPollingEngine.kt`, remove double `animateFloatAsState` spring from `SemiDigitalArcWidget`, and prevent cruise-plateau lag in `TelemetryDisplaySmoother.AdaptiveSession`.
- [x] 5. Run `:obd-core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `openspec validate --all --strict`, and install/verify visually on `RMX1921` (`3646fbc7`).
