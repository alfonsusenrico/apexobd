# logging-tracking Specification

## Purpose
Centralized manual Map-Record-triggered trip telemetry and stop-recording save/discard log confirmation.

## ADDED Requirements

### Requirement: Centralized Manual Run-Record Trigger for All Trip Telemetry
All 5 trip tracking metrics (`KPL · TRIP`, `Trip Fuel Used`, `Trip Range`, `Trip Time`, and `Trip Avg Speed`) SHALL remain inactive (`—` / `00:00`) upon app launch and OBD connection, SHALL reset to zero together and accumulate strictly while Map Run Recording (`isRecording`) is active, and SHALL record full session totals and per-sample telemetry into local Room storage.

#### Scenario: Trip metrics stay idle until Map Record is toggled ON
- **WHEN** the user opens the app or connects to the OBD adapter without starting Run Recording
- **THEN** `KPL · AVG` and `KPL · INST` update from live engine telemetry while all 5 trip metrics (`KPL · TRIP`, `Trip Fuel`, `Trip Range`, `Trip Time`, `Trip Avg Speed`) remain in standby until the Map Record toggle starts a run

### Requirement: Trip Summary Confirmation Modal on Stop Recording
Every time active trip tracking is switched OFF, the application SHALL display a summary modal presenting all recorded trip metrics (`Trip Range`, `Trip Time`, `KPL · TRIP`, `Trip Fuel Used`, `Trip Avg Speed`, `Max Speed`, `Peak RPM`, and sample count) and prompt the user to either `Save Trip Log` (committing the trip and samples to `Run Logs & Maps`) or `Discard Trip` (deleting the unconfirmed session and its samples from local storage).

#### Scenario: Saving or discarding a recorded trip when Stop is tapped
- **WHEN** the user taps Stop on the Map Record button after driving a trip
- **THEN** trip recording stops immediately and opens the `Save Recorded Trip Log?` modal showing the complete trip metrics, where tapping `Save Trip Log` persists the run into `Saved Runs` (`CSV`/`JSON` exportable) and tapping `Discard Trip` deletes the session and telemetry samples from Room
