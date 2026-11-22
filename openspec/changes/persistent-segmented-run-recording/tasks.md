# Tasks

## Phase 1: Room v10 Schema (`trip_segments` Sub-Records) & Core State
- [x] 1.1 Add `TripSegmentEntity`, `TripSessionEntity.segmentCount`, `TelemetrySampleEntity.segmentIndex`, `TripDao` segment/draft queries, and `MIGRATION_9_10` (`version = 10`) in `ApexDatabase.kt`. (Evidence: `:app:compileDebugKotlin`)
- [x] 1.2 Add `segmentCount` and `currentSegmentIndex` to `TripRunMetrics` in `TelemetryCalculator.kt`, and add `restoreTrail` to `LocationTracker.kt`. (Evidence: `:obd-core:test` & `:app:testDebugUnitTest`)

## Phase 2: Persistent Pause/Resume Loop & Cold-Start Session Restoration
- [x] 2.1 Refactor `HudViewModel.kt` recording lifecycle to create and checkpoint `TripSegmentEntity` sub-records on every Start/Pause/Resume/Stop transition, flush immediately on Pause, preserve paused sessions when `terminateBackgroundService()` is invoked, and restore un-ended draft sessions in PAUSED state on cold start. (Evidence: unit tests & `:app:testDebugUnitTest`)
- [x] 2.2 Update `TripExporter.kt` so CSV exports include `segment_index` and JSON exports include `"segmentCount"`, `"segments": [...]` sub-records, and per-sample `"segmentIndex"`. (Evidence: unit tests in `TripExporterTest.kt`)

## Phase 3: Sub-Record UI in Home, HUD Logger, Save Dialog, and Run Logs Sheet
- [x] 3.1 Update `HomeMenuScreen.kt`, `TorqueEvoWidgets.kt` (`DataLoggerWidget` & `MiniMapWidget`), `HudCanvasScreen.kt`, and `HomeSheets.kt` (`SaveTripSummaryDialog` & `RunLogsAndMapsSheet`) to show active/paused leg status, Resume/Stop controls, and expandable/inline Sub-Records (`Leg 1`, `Leg 2`, …) breakdown. (Evidence: `:app:lintDebug` & `:app:assembleDebug`)
- [x] 3.2 Align `debug` buildType signing in `app/build.gradle.kts` to use the release keystore whenever `hasReleaseSigning` is configured so local dev installs and GitHub Release APKs share the same signing certificate without `INSTALL_FAILED_UPDATE_INCOMPATIBLE` clashes, and bump `versionCode = 3` / `versionName = "1.1.1"`. (Evidence: `:app:assembleDebug`)
- **Expected Result:** `:obd-core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and `openspec validate --all --strict` pass with 0 errors.
