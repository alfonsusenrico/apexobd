# Design: Persistent Segmented Run Recording Across Pause, Resume, and App Close

## Context
Users frequently record multi-stop trips (`A -> B`, park with adapter off and app closed, then resume `B -> C`). Previously:
1. `TripSessionEntity` had no child segment table and `TelemetrySampleEntity` had no segment identifier.
2. `HudViewModel.init` executed `tripDao.deleteDraftSessions()` on every cold start, destroying any `isSaved = 0` session if the app process died while parked.
3. `terminateBackgroundService()` called `stopRecording()`, which finalized the session and popped up the save dialog instead of keeping the run paused for later resume.

## Decisions

### Decision 1: Parent Run (`trip_sessions`) + Sub-Records (`trip_segments`) Schema (Room `v10`)
- A complete run is a single `TripSessionEntity` row.
  - While tracking is in progress (active or paused), `endedAtMs = null` and `isSaved = false`.
  - When the user explicitly ends tracking (**Stop / Finish Run**), `endedAtMs` is set to the completion timestamp and `_pendingSaveTrip` presents `SaveTripSummaryDialog`.
  - When the user taps **Save Trip Log**, `isSaved = true`.
- Each contiguous driving leg between Start/Resume and Pause/End is stored as a `TripSegmentEntity` row in `trip_segments` (`FOREIGN KEY(sessionId) REFERENCES trip_sessions(id) ON DELETE CASCADE`):
  - `id: Long`
  - `sessionId: Long`
  - `segmentIndex: Int` (`1`-based: Leg 1, Leg 2, …)
  - `startedAtMs: Long`
  - `endedAtMs: Long?`
  - `distanceKm: Double`
  - `fuelUsedLiters: Double`
  - `activeDurationSec: Long`
  - `avgSpeedKmh: Double`
  - `maxSpeedKmh: Double`
  - `peakRpm: Double`
  - `avgKpl: Double`
  - `sampleCount: Int`
- Each `TelemetrySampleEntity` stores `segmentIndex: Int = 1`, tying every GPS/OBD sample to its sub-record leg.
- `TripSessionEntity` stores `segmentCount: Int = 1` for fast summary rendering without N+1 queries.

### Decision 2: Immediate Checkpoint Flush on Pause & Process Stop
- When the user taps **Pause**:
  - `_isRecordingPaused.value = true`
  - The recording loop immediately flushes any buffered `TelemetrySampleEntity` items in `batch`, computes the leg's metrics (`legDistKm`, `legFuelL`, `legDurationSec`, `legMaxSpeed`, `legPeakRpm`, `legSamples`), updates the current `TripSegmentEntity` with `endedAtMs = nowMs`, and updates `TripSessionEntity` (`endedAtMs = null`, `isSaved = false`, `segmentCount`).
- When the user taps **Resume**:
  - If the recording coroutine is already running in memory, `_isRecordingPaused.value = false` transitions `wasPaused -> false`, increments `currentSegmentIndex`, inserts a new `TripSegmentEntity` row, resets leg baselines (`segStartDistKm = currentTrip.distanceKm`, `segStartElapsedMs = activeElapsedMs`, `segStartFuelL = accumulatedFuelL`, `segmentFuelBaseline = curTotalFuel`, `prevGps = null`), and resumes sampling.
  - If the app was closed and cold-started into a restored paused session (`_isRecording.value == true && _isRecordingPaused.value == true` with `loggingJob == null`), tapping **Resume** launches `launchRecordingLoop(resumedSession)` which starts `LoggingForegroundService` and begins `segmentIndex = lastSegmentIndex + 1`.
- When the user taps the ongoing notification to stop the background service (`terminateBackgroundService()`) while a recording is active:
  - Instead of ending the run (`stopRecording()`), if `_isRecording.value` is true, it pauses and checkpoints the active leg to SQLite (`pauseAndCheckpointForBackgroundStop()`) so the run remains resumable when the user reopens the app!

### Decision 3: Cold-Start Restoration of Un-Ended Runs
- On `HudViewModel.init`:
  - Query `tripDao.getLatestDraftSession()`.
  - Delete any empty zero-sample stale drafts (`sampleCount == 0 && distanceKm < 0.001`).
  - If a draft session exists with `endedAtMs != null` (the user had already tapped Stop before the process died, awaiting Save/Discard), restore `_pendingSaveTrip.value = draft`.
  - If a draft session exists with `endedAtMs == null` (the run was active or paused when the app closed):
    - Close any un-ended `TripSegmentEntity` (`endedAtMs = lastSampleTimestamp ?: now`).
    - Load existing `TelemetrySampleEntity` rows for the session and restore the GPS route trail via `locationTracker.restoreTrail(pts)`.
    - Populate `_isRecording.value = true`, `_isRecordingPaused.value = true`, `activeSessionId = draft.id`, and `_tripRunMetrics.value` with the draft's accumulated `distanceKm`, `fuelUsedLiters`, `activeDurationSec`, `avgKpl`, `avgSpeedKmh`, and `segmentCount`.
- On `HomeMenuScreen`, `MiniMapWidget`, and `DataLoggerWidget`:
  - Display clear Pause/Resume and Finish (End Tracking) controls so the user can resume or finish a paused run from either the Home screen or the HUD Canvas.
