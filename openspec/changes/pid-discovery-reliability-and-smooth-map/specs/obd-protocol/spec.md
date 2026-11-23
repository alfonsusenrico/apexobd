# obd-protocol Specification Delta

## ADDED Requirements

### Requirement: Multi-ECU Bitmask Union and Retry During Mode 01 PID Discovery
`ObdPidRegistry.discoverSupportedPids` SHALL union supported PID bitmasks across all responding CAN ECUs per block and SHALL retry any advertised block (`0100`, `0120`, `0140`, …) up to 3 times under default `ATAT1` timing before `ObdPollingEngine` applies tight `ATST19` live-polling timing.

#### Scenario: Transient timeout on 0140 or multi-ECU response still discovers voltage, rel_throttle, and ambient_temp
- **WHEN** `0120` advertises block `0140`, the first `0140` query returns `NO DATA`, and the retry returns both a `7E9` line (`414000000000`) and a `7E8` line (`414044DC8000` advertising `42`, `45`, `46`)
- **THEN** `discoverSupportedPids` retries `0140`, unions both response lines, and includes `42`, `45`, and `46` in the discovered PID set
