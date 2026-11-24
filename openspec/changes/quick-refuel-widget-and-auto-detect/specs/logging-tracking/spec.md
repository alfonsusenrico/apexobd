## ADDED Requirements

### Requirement: One-Tap Quick Refuel HUD Widget and Draft Freezing
The HUD canvas MUST offer a `"quick_refuel"` special widget that records a draft refuel checkpoint on a single tap, freezing the pump-time timestamp, live odometer, OBD fuel-used snapshot, and estimated added liters without altering MAF trim or full-to-full cycle KPL until the driver confirms the entry.

#### Scenario: Driver taps Quick Refuel at the station
- **WHEN** the driver taps the Quick Refuel HUD widget after refueling
- **THEN** a draft `RefuelLogEntity` (`isDraft = true`) is saved with frozen odometer, timestamp, `obdFuelUsedSnapshotL`, and estimated added liters, the vehicle's `fuelUsedSinceFullRefuelLiters` resets for the new tank, and `mafTrimPct` remains unchanged until confirmed

### Requirement: Automatic Refuel Jump Detection Prompt
For vehicles with a native fuel level sensor, the app MUST detect sustained positive fuel-level jumps (`>= max(4.5 L, 11% of tank capacity)` with a 10-minute cooldown) and offer a non-blocking HUD prompt to save a draft refuel entry with the sensor-estimated added volume.

#### Scenario: Fuel level jumps after ignition-on at a gas station
- **WHEN** a vehicle with a native fuel sensor reconnects with fuel level `+24.0 L` higher than its pre-refuel reading
- **THEN** the HUD displays a prompt with the estimated added volume and `[Save Draft]` / `[Dismiss]` actions

### Requirement: Refuel Draft Editing and Confirmation
The Refuel Checkpoint Log MUST flag draft entries and allow editing and confirming any refuel entry, recalculating full-to-full cycle KPL and optional MAF trim calibration using the frozen pump-time OBD fuel snapshot.

#### Scenario: Driver confirms a saved draft with receipt values
- **WHEN** the driver edits a draft refuel entry with actual pump liters and price and confirms it
- **THEN** `isDraft` becomes `false`, cycle KPL updates against prior confirmed full-tank checkpoints, and MAF trim calibration uses `obdFuelUsedSnapshotL`
