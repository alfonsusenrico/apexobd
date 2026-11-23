# obd-protocol Specification Delta

## ADDED Requirements

### Requirement: Automatic ECU Vehicle Identification on Connect
On OBD-II connection discovery, `ObdPollingEngine` SHALL read Mode `09 02` VIN (when supported) alongside Mode 01 and OEM PID discovery and emit `EcuIdentitySnapshot` so `VehicleIdentityMatcher` can identify the connected vehicle by VIN or Mode 01 PID fingerprint before persisting odometer, fuel level, or capability tokens.

#### Scenario: Connecting 2024 2NR-VE while 2014 K3-VE profile is active auto-switches to 2NR-VE
- **WHEN** the active profile is `Avanza K3VE` (`04,05,06,07,0B,0C,0D,0E,0F,10,11,14,...`) and the adapter connects to `BAM` (`01,03,04,05,06,07,0C,0D,0E,0F,11,13,15,1C,1F,20,21,24,2E,30,31,...`, `7C0_2129`, `7C0_21E1`)
- **THEN** `VehicleIdentityMatcher` matches `BAM` and `HudViewModel` switches to `BAM` before writing `7C0_2129`, `7C0_21E1`, fuel level, or odometer so `Avanza K3VE` is never overwritten
