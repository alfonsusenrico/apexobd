# custom-pids Specification

## Purpose
UDS Mode 22 extended PIDs, Torque-compatible CSV import, and safe arithmetic expression evaluation.

## ADDED Requirements

### Requirement: Custom / UDS Mode 22 PID Definition and Torque CSV Import
The system SHALL allow users to create, edit, and import (via Torque-compatible CSV format) custom OBD/UDS Mode `22` PIDs specifying command header/PID (e.g., `221E1C`), unit, min/max, and byte formula.

#### Scenario: Importing a Torque-format CSV row
- **WHEN** the user imports a CSV line `"Trans Temp","TFT","221E1C","((A*256)+B)/10-40","0","150","°C","7E1"`
- **THEN** the parser validates and stores the custom PID in Room and makes it selectable in the HUD Component Picker

### Requirement: Safe Byte Formula Evaluator
The system SHALL evaluate byte formulas referencing variables `A`..`H`, arithmetic operators (`+`, `-`, `*`, `/`), parentheses, bitwise operations (`&`, `|`, `<<`, `>>`), and `SIGNED(x)` without dynamic code execution.

#### Scenario: Evaluating a multi-byte UDS formula
- **WHEN** formula `((A*256)+B)/10-40` is evaluated against payload bytes `[0x04, 0x88]`
- **THEN** the evaluator returns `76.0` accurately
