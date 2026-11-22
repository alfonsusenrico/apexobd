# Proposal: Persistent Segmented Run Recording Across Pause, Resume, and App Close

## Approval
- Approved by owner in session on 2026-11-22.

## Why
1. When recording a multi-stop run (`A -> B`, pausing, turning off the OBD adapter, and closing the app or having Android reclaim the background process), `v1.1.0` lost the active recording session because `HudViewModel.init` deleted all `isSaved = 0` draft sessions on cold start (`tripDao.deleteDraftSessions()`), and `terminateBackgroundService()` immediately finalized the run instead of preserving a paused checkpoint.
2. Pausing and resuming a single run (`Leg 1: A -> B`, `Leg 2: B -> C`, etc.) did not record distinct sub-records (segments/legs) inside the parent run record or tag individual telemetry samples with their segment index.

## Expected Outcome
- Each pause-and-resume cycle inside a run creates a persisted **sub-record (`TripSegmentEntity`, Leg 1, Leg 2, …)** linked to the parent `TripSessionEntity`, and every `TelemetrySampleEntity` carries its `segmentIndex`.
- A run session remains open (`endedAtMs = null, isSaved = false`) across pause, adapter power-off, background service stop, app closure, or phone reboot until the user explicitly flags **End of Tracking (Stop)**.
- Pausing a run (or closing the app / stopping the background listener while a run is active) immediately flushes pending telemetry samples, closes the active `TripSegmentEntity` sub-record, and checkpoints the parent `TripSessionEntity` to SQLite without prematurely ending the run.
- On app cold start, any un-ended run session (`endedAtMs = null, isSaved = false`) is automatically restored in **PAUSED** state (including its accumulated distance, active duration, fuel burned, sub-record count, and recorded GPS route trail), and a home banner / HUD controls let the user **Resume** (starting the next sub-record `Leg N+1`) or **Finish** (flagging End of Tracking and opening `SaveTripSummaryDialog`).
- Saved runs, `SaveTripSummaryDialog`, CSV/JSON exports, and Route Share Cards display and preserve the sub-records (Legs) within the complete run record.

## What Changes
- **Capabilities affected:** `logging-tracking` (no new capability added).
- **`app` (`ApexDatabase.kt`, Room `MIGRATION_9_10`, `version = 10`):**
  - Add `segmentCount: Int = 1` to `TripSessionEntity`.
  - Add `segmentIndex: Int = 1` to `TelemetrySampleEntity`.
  - Create `trip_segments` table (`TripSegmentEntity`) with foreign key to `trip_sessions(id) ON DELETE CASCADE` storing `segmentIndex`, `startedAtMs`, `endedAtMs`, `distanceKm`, `fuelUsedLiters`, `activeDurationSec`, `avgSpeedKmh`, `maxSpeedKmh`, `peakRpm`, `avgKpl`, and `sampleCount`.
  - Add `TripDao` queries for active un-ended draft session lookup, segment insertion/updating, and observing/fetching sub-records for sessions.
- **`app` (`LocationTracker.kt` & `HudViewModel.kt`):**
  - Add `LocationTracker.restoreTrail(points: List<Pair<Double, Double>>)` to reload recorded GPS coordinates when restoring a paused session on startup.
  - Refactor `HudViewModel` recording lifecycle to support starting a new run or resuming an existing persisted draft session, immediately flushing samples and closing the current `TripSegmentEntity` on Pause or service termination, and opening `TripSegmentEntity(segmentIndex = N + 1)` on Resume.
  - On cold start (`HudViewModel.init`), restore any un-ended draft session (`endedAtMs == null && !isSaved`) into paused recording state (or into `_pendingSaveTrip` if `endedAtMs != null && !isSaved`) instead of wiping it.
- **`app` (`HomeMenuScreen.kt`, `HomeSheets.kt`, `TorqueEvoWidgets.kt`, `TripExporter.kt`):**
  - Show an active/paused run banner on `HomeMenuScreen` when a run is paused or active so the user can Resume or End Tracking directly from Home or HUD (`MiniMapWidget` & `DataLoggerWidget`).
  - Display the sub-records (`Leg 1`, `Leg 2`, … with distance, duration, KPL, fuel used, and top speed) inside `SaveTripSummaryDialog` and `RunLogsAndMapsSheet`.
  - Include `segment_index` in CSV exports and a `"segments": [...]` sub-records array plus sample `"segmentIndex"` in JSON exports (`TripExporter.kt`).

## Non-Goals
- Do not split a paused/resumed run into separate top-level saved runs; all legs remain sub-records inside one complete run record flagged by End of Tracking.
