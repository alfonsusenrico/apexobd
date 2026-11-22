# Tasks

- [x] Harden `WifiTcpTransport` (bulk read buffer, cached `soTimeout`, `IPTOS_LOWDELAY`, `\r` timeout resync), `Elm327Client` (`ATAT1` + `ATST19`), and `ObdPollingEngine` (`650 ms` CAN live timeout, session-sticky multi-PID disable on unsupported ECUs, rate-limited `headerHex` CAN switching).
- [x] Add `WIFI_MODE_FULL_LOW_LATENCY` + `PARTIAL_WAKE_LOCK` in `WifiNetworkBinder` and pure-GNSS mode during Wi-Fi OBD in `LocationTracker`.
- [x] Add `ApexObdApplication`, upgrade `LoggingForegroundService` (`connectedDevice|location`, live background status notification, tap-to-terminate action), and wire always-connected background lifecycle into `MainActivity`, `HudViewModel`, `HomeMenuScreen`, and `HomeSheets`.
- [x] Extend `EcuDiscoveryDumper` with `7C0` Combination Meter / `750` Body ECU / full `7E0` Mode 21 bitmask probes and multi-frame `FormulaEvaluator.decodeCustomPid`.
- [x] Add unit tests, update `AGENTS.md`, run tests/lint/assembleDebug/openspec validate, and install & verify on RMX1921.
