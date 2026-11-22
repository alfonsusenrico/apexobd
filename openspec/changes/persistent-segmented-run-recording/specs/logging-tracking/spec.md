# logging-tracking Specification Delta

## ADDED Requirements

### Requirement: Segmented Sub-Records Inside a Complete Run Record
When a user pauses and resumes an active run recording before flagging End of Tracking (Stop), the application SHALL record each contiguous driving leg as a persisted sub-record (`TripSegmentEntity`, 1-based `segmentIndex`) inside the parent `TripSessionEntity` and SHALL tag each `TelemetrySampleEntity` with its `segmentIndex`.

#### Scenario: Multi-leg trip A to B then B to C records two sub-records in one run
- **WHEN** a user starts recording at `A`, pauses at `B` after `12.4 km`, later resumes at `B`, drives `8.6 km` to `C`, and taps Stop (End of Tracking)
- **THEN** the saved `TripSessionEntity` has `distanceKm = 21.0 km` and `segmentCount = 2`, with two child `TripSegmentEntity` sub-records (`Leg 1: 12.4 km` and `Leg 2: 8.6 km`) displayed in the trip summary and run logs and included in CSV/JSON exports

### Requirement: Persistent Paused Run Restoration Across Adapter Off and App Closure
Pausing a run recording (or stopping the background service / closing the app while a run is in progress) SHALL immediately checkpoint pending samples, the current sub-record (`TripSegmentEntity`), and the parent `TripSessionEntity` (`endedAtMs = null, isSaved = false`) to SQLite, and cold-starting the app SHALL restore the unfinished run in `PAUSED` state so the user can resume (`Leg N+1`) or finish and save the complete run.

#### Scenario: User pauses recording at B, turns off adapter, closes app, and resumes later
- **WHEN** a user pauses an active run at `B` (`12.4 km`, `Leg 1`), turns off the OBD-II adapter, and closes the app process
- **THEN** reopening the app restores the run in `PAUSED` state at `12.4 km` with its recorded GPS route trail intact, and tapping Resume starts `Leg 2` within the same run record until the user taps Stop
