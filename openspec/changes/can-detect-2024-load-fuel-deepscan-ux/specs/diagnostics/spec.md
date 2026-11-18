# diagnostics Specification

## Purpose
Visible Deep ECU Scan progress, in-app `ecu_dump.txt` sharing, and automatic explorer polling resumption.

## ADDED Requirements

### Requirement: Non-Freezing Deep ECU Scan Progress & Share Action
`HudViewModel.runDeepEcuDump` SHALL publish live step progress to `explorerStatus` and `deepScanRunning`, keep a loading indicator visible in `EcuPidsSheet`, resume live explorer polling once the scan completes while the sheet remains open, and allow exporting `files/ecu_dump.txt` via the Android share sheet.

#### Scenario: Deep Scan completes from ECU PIDs sheet without leaving values permanently frozen
- **WHEN** the user taps `Deep Scan` inside `ECU PIDs` on Home
- **THEN** a progress bar and step status update continuously during the scan, and once `ecu_dump.txt` finishes writing, live PID polling resumes automatically and `Share ECU Dump` becomes available
