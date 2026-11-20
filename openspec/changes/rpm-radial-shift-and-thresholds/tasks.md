# Tasks

- [x] Add 4 RPM tachometer enum variants, `alertBelow` column, and additive Room `MIGRATION_7_8` in `ApexDatabase.kt`
- [x] Implement `RpmRadialWidgets.kt` (`RpmTachGtWidget`, `RpmTachF1LedsWidget`, `RpmTachChronoWidget`, `RpmTachHaloWidget`, `MetricAlertFrameOverlay`, and pure helper functions)
- [x] Replace `< 12.5` direction heuristic in `TorqueEvoWidgets.kt` with `evaluateMetricAlert` and thread `alertBelow`
- [x] Add `UnitConverter.toInternalMetric`, `HudViewModel.updateWidgetThresholds`, and RPM style cycling
- [x] Wire RPM radial models, `MetricAlertFrameOverlay`, and optional Shift/Warning threshold editors in `HudCanvasScreen.kt`
- [x] Add unit tests in `HudWidgetStyleTest.kt` covering shift progression, metric alerts, dial scaling, and unit round-trips
- [x] Validate OpenSpec strict check, build debug APK, run tests and lint, and verify migration/launch on `RMX1921`
