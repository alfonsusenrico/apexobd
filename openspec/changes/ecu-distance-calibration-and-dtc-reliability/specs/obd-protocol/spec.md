# obd-protocol Specification Delta

## ADDED Requirements

### Requirement: Engine ECU Distance Since Codes Cleared Discovery (`0131` and `7E0 2131`)
The polling engine SHALL discover and read Distance Traveled Since Codes Cleared via standard Mode 01 (`0131`) or Toyota/Daihatsu Engine ECM Mode 21 (`7E0 2131`, capability token `7E0_2131`) while keeping `fuel_level` (`Fuel Level %`) and `ecu_odometer` (`Cluster Odometer`) strictly reserved for hardware fuel float (`012F` / `7C0 2129`) and cluster odometer (`7C0 21E1`) sensors.

#### Scenario: 2014 Avanza K3-VE exposes 7E0 2131 without 7C0 cluster
- **WHEN** `ObdPollingEngine` connects to a CAN vehicle where `0131` and `7C0` are absent from Mode 01/cluster discovery but `7E0 2131` responds `61 31 2A 8A`
- **THEN** the engine records capability token `7E0_2131`, emits `ecuDistanceSinceClearKm = 10890.0`, and does not fabricate `fuel_level` or `ecu_odometer`
