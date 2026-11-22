# obd-protocol Specification

## ADDED Requirements

### Requirement: Low-Latency Wi-Fi Transport & Radio Locks
`WifiTcpTransport` SHALL read TCP responses in bulk buffers with low-delay QoS (`tcpNoDelay`, `IPTOS_LOWDELAY`), avoid per-byte `setsockopt` mutations, and send a `\r` resync on timeout. While `WIFI_TCP` is active, `WifiNetworkBinder` SHALL bind the process exclusively to the Wi-Fi network without holding a parallel cellular keepalive request, hold `WIFI_MODE_FULL_LOW_LATENCY` and `PARTIAL_WAKE_LOCK`, and `LocationTracker` SHALL prefer hardware GNSS (`GPS_PROVIDER`) over FusedLocationProvider.

#### Scenario: Bulk TCP read returns ELM327 prompt immediately without per-byte socket option mutations
- **WHEN** `WifiTcpTransport.sendCommand` receives `410C1AF8\r>` over TCP
- **THEN** it reads into a chunk buffer, returns immediately upon `'>'`, and issues `\r` resync on timeout

### Requirement: Reliable ELM327 Timing & Session-Sticky Multi-PID Backoff
`Elm327Client` SHALL initialize with `ATAT1` (and `ATST19` on CAN when aggressive timing is enabled). `ObdPollingEngine` SHALL use a `650 ms` CAN live-poll timeout and disable multi-PID for the session when an ECU fails 3 consecutive probes without any prior multi-PID success.

#### Scenario: Unsupported multi-PID ECU never stalls periodically while CAN multi-PID ECUs stay fast
- **WHEN** `ObdPollingEngine` connects to an ECU that rejects multi-PID frames 3 times without any prior multi-PID success
- **THEN** multi-PID stays disabled for the session with zero periodic 15-second re-probe stalls, whereas an ECU that previously succeeded recovers after transient drops

### Requirement: Always-Connected Background Listener & Tap-to-Terminate Notification
When ApexOBD opens, the OBD-II adapter listener SHALL connect automatically across both Home Menu and HUD Dashboard and remain active in the background via `LoggingForegroundService` (`connectedDevice|location`) hosted with an application-scoped `ViewModelStore`. An ongoing notification SHALL state that the background listener is active, and clicking the notification SHALL immediately terminate the background service, flush trip/fuel data, release wake/Wi-Fi locks, and disconnect the adapter.

#### Scenario: Clicking the active background listener notification terminates the service cleanly
- **WHEN** the background OBD listener is running and the user taps its persistent notification
- **THEN** `LoggingForegroundService` dispatches termination, stops recording if active, flushes vehicle telemetry, disconnects `ObdPollingEngine`, releases locks, and removes the notification

### Requirement: Custom CAN Header Switching & Read-Only Combination Meter (`7C0`) Fuel Discovery
`ObdPollingEngine` SHALL honor `CustomPidDefinition.headerHex` by switching CAN transmit/receive headers (`ATSH<hdr>` / `ATCRA<reply>`) under cooldown rate limiting and always restoring `ATCRA` + `ATSH7DF` in a `finally` block. `EcuDiscoveryDumper` SHALL walk `7E0` Mode 21 bitmasks and perform strictly read-only probes on `7C0` (Combination Meter), `750` (Body ECU), `7E1`, and `7E2` before restoring `ATCRA` + `ATSH7DF`.

#### Scenario: Custom PID targeting Combination Meter `7C0` switches headers safely and decodes Mode 21
- **WHEN** a custom PID defines `headerHex = "7C0"` and `modeAndPidHex = "2129"`
- **THEN** `ObdPollingEngine` sends `ATSH7C0`, `ATCRA7C8`, `2129`, decodes the `61 29` payload, and restores `ATCRA` and `ATSH7DF`

### Requirement: Auto-Detected Combination Meter (`7C0`) Fuel, Odometer & Cluster Sync
On CAN buses, `ObdPollingEngine` SHALL probe `7C0`/`7C8` at connect time (always restoring `ATCRA` + `ATSH7DF`). When `2129` responds, ApexOBD SHALL unlock `fuel_level` and feed `A * 0.5 L` into `FuelEconomyTracker` every `12 s`. When `21E1` responds, ApexOBD SHALL expose `ecu_odometer` and auto-sync the active vehicle profile every `30 s`. Optional `2121`/`2122`/`2123` cluster comparison sensors SHALL poll only when visible on the HUD.

#### Scenario: 2024 Avanza without Mode 01 `2F` unlocks live digital fuel level and syncs exact odometer
- **WHEN** `ObdPollingEngine` connects to a vehicle reporting `7C0 2129 -> 612925` and `7C0 21E1 -> 0000770D` without Mode 01 `2F`
- **THEN** `fuel_level` unlocks (`43.0%` on a 43 L tank), `fuel_remaining` updates to `18.5 L`, and `ecuOdometerKm` emits `30477.0 km` for automatic vehicle profile sync
