# obd-protocol Specification

## Purpose
Wi-Fi ELM327 transport, AT initialization, SAE J1979 PID discovery and decoding, and priority-tiered polling scheduler.

## ADDED Requirements

### Requirement: Wi-Fi ELM327 Initialization and Transport Abstraction
The system SHALL abstract adapter communication behind `ObdTransport` supporting both `WifiTcpTransport` (default `192.168.0.10:35000`, bound to Wi-Fi network when available) and `SimulatedObdTransport`, and SHALL execute the ELM327 initialization sequence (`ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATAT1`, `ATSP0`) synchronizing on the `>` prompt.

#### Scenario: Successful Wi-Fi adapter handshake
- **WHEN** the user connects to a reachable Wi-Fi ELM327 endpoint or activates Simulator mode
- **THEN** the engine completes AT initialization, reports connected state, and reads the active vehicle protocol (`ATDPN` / `ATDP`)

#### Scenario: Adapter timeout or socket drop
- **WHEN** the Wi-Fi TCP stream times out or closes unexpectedly during polling
- **THEN** the connection manager transitions to `Reconnecting`, closes the stale socket cleanly, and retries with bounded backoff without crashing the UI

### Requirement: Bitmask PID Discovery and SAE J1979 Decoding
The system SHALL query Mode `01` support bitmasks (`0100`, `0120`, `0140`, `0160`, `0180`, `01A0`) on connect and SHALL accurately decode standard SAE J1979 Mode `01` PIDs including RPM (`0C`), Speed (`0D`), Coolant Temp (`05`), Intake Air Temp (`0F`), Throttle (`11`), MAF (`10`), Timing Advance (`0E`), Voltage (`42`), MAP (`0B`), Load (`04`), Fuel Trims (`06`-`09`), Fuel Rail Pressure (`22`/`23`/`59`), O2/Lambda (`14`-`1B`/`44`), Oil Temp (`5C`), and Torque PIDs (`62`/`63`).

#### Scenario: Bitmask discovery filters polling set
- **WHEN** the ECU responds to `0100` with `41 00 BE 1F A8 13`
- **THEN** the engine decodes the exact 32-bit support mask and marks only the supported PIDs as available in the catalog

#### Scenario: Malformed or NO DATA response
- **WHEN** the adapter returns `NO DATA`, `?`, or truncated hex bytes for a PID
- **THEN** the decoder returns a typed failure/unavailable result rather than emitting `0` or throwing an unhandled exception

### Requirement: Priority-Tiered Polling Scheduler
The system SHALL poll visible high-frequency PIDs on every cycle, slow-changing visible PIDs on a reduced cadence, and pause background PID polling when an on-demand diagnostic command (`Mode 02`/`03`/`04`/`06`/`09`) is queued.

#### Scenario: On-demand DTC read preempts live PID loop
- **WHEN** the user triggers a DTC scan while live HUD polling is running
- **THEN** the scheduler finishes the current in-flight command, executes the DTC commands cleanly without interleaving, and resumes live polling
