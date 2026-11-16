# diagnostics Specification

## Purpose
SAE J1979 fault code scanning, freeze frame capture, readiness monitors, vehicle info, and safe DTC clearance usable both on a HUD widget and parked diagnostics sheet.

## ADDED Requirements

### Requirement: DTC Scanning and Freeze Frame Decoding
The system SHALL read Confirmed (`Mode 03`), Pending (`Mode 07`), and Permanent (`Mode 0A`) DTCs, decode 2-byte OBD-II fault identifiers into `P`/`C`/`B`/`U` codes with human-readable descriptions, and retrieve Freeze Frame snapshots (`Mode 02`).

#### Scenario: Decoding multi-frame DTC response
- **WHEN** the ECU returns `43 02 01 71 04 20`
- **THEN** the decoder extracts `P0171` and `P0420` with their standard descriptions and classifies them under Confirmed DTCs

### Requirement: Readiness Monitors and Vehicle Info
The system SHALL decode Readiness Monitor statuses (`Mode 01 PID 01` / `Mode 06`) and Vehicle Info (`Mode 09` VIN, CALID, CVN).

#### Scenario: Parsing MIL and readiness bitmask
- **WHEN** `0101` returns MIL on and monitor completion bits
- **THEN** the UI displays MIL state, DTC count, and each monitor's `Ready` or `Incomplete` badge with text and icon (never color alone)

### Requirement: Safe DTC Clearance (Mode 04)
The system SHALL require explicit user confirmation explaining readiness monitor reset before sending `Mode 04` (`04`).

#### Scenario: User confirms DTC clearance
- **WHEN** the user taps "Clear DTCs (04)" and confirms the warning dialog
- **THEN** the engine sends `04`, refreshes DTCs and readiness monitors, and updates the UI with the post-clear status
