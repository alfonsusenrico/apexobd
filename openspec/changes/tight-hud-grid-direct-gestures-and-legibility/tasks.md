# Tasks: Tight HUD Grid, Direct On-Component Gestures, Legibility, and 99.99 KPL Cap

- [x] 1. Add `FINE_16X8` and `ULTRA_24X12` grid scales in `AppPreferences.kt` and pure `computeResizedGridBounds` in `HudGridLayout.kt`.
- [x] 2. Add `updateWidgetGridBounds` and `bringWidgetToFront` in `HudViewModel.kt` and update `99.9` -> `99.99` clamps.
- [x] 3. Implement tight grid spacing, visible Edit Mode grid, 4-side arrow resize handles, hold-middle drag-to-move, overlap click-to-front + layer cycling, and Edit Mode grid-scale pill in `HudCanvasScreen.kt`.
- [x] 4. Improve compact label/unit legibility and header-unit placement for `XX.XX` values in `TorqueEvoWidgets.kt`.
- [x] 5. Fix raw-pixel `RPM` label and numeral scaling using `dp.toPx()` across all 4 RPM radial gauges in `RpmRadialWidgets.kt`.
- [x] 6. Raise KPL display cap from `99.9` to `99.99` in `TelemetryCalculator.kt`, `VehicleAndUnits.kt`, `ApexDatabase.kt`, and `TripShareStatsCalculator.kt`, and bump `app/build.gradle.kts` to `1.2.2` (`versionCode = 7`).
- [x] 7. Add/update unit tests (`HudGridLayoutTest`, `HudWidgetStyleTest`, `TelemetryCalculatorTest`) and run full Gradle tests, lint, debug APK build, and `openspec validate --all --strict`.
- [x] 8. Install `v1.2.2` APK onto connected phone and verify version.
