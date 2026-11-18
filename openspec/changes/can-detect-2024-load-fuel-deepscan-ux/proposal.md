# Proposal

Status: **Approved by owner on `2026-11-18`** (fix CAN auto-detection across `ATDP`/`ATDPN`, add `0104 Engine Load` airflow/fuel/DFCO fallback + `0124`/`014C` for 2024 Avanza, expand Deep Scan Mode `21` probing, and add Deep Scan live progress + polling auto-resume + `ecu_dump.txt` sharing).

## Why

Inspecting the live `ecu_dump.txt` (`5,961 B`, `119` lines, `29,697 ms`) captured on the owner's **2024 Toyota Avanza 1.5G MT** (`RMX1921`) plus tracing `runDeepEcuDump` uncovered three concrete bugs impacting both vehicles:

1. **Deep Scan Appears Frozen With Zero Feedback & Dead Live Polling:** `HudViewModel.runDeepEcuDump()` stops `engine` without emitting progress to `_explorerStatus` or showing a spinner in `EcuPidsSheet`, and only restarts `reconnectTransport(...)` if `_onHudScreen.value || _isRecording.value` is true — leaving the `ECU PIDs` sheet stuck with stopped values forever after the scan finishes, with no in-app way to view or share `ecu_dump.txt`.
2. **`isCanProtocol = false` False-Negative on Clone `OBDII v1.5` (`A6`) Adapter:** `ecu_dump.txt` captured `Protocol ATDP: Auto (ATDPN=AUTO, ISO 15765-4 (CAN 11/500), isCan=false)`. Because `ATDPN` contained `"AUTO, ISO 15765-4 (CAN 11/500)"` (ending in `')'`) while `ATDP` kept only `.firstOrNull()` (`"Auto"`), `Elm327Client.initialize` marked `isCanProtocol = false` on **both** cars, disabling `ATST19` (`100 ms`), `"010C1"` fast queries (`45 ms` RTT proven in dump), and single-frame CAN multi-PID packing (`packSingleFrameCanChunks`).
3. **2024 Avanza (`2NR-VE`) Omits Both `0110 MAF` and `010B MAP` in Standard Mode `01`:** `ecu_dump.txt` shows 37 supported Mode `01` PIDs (`01..51`) with neither `10` (`MAF`) nor `0B` (`MAP`), causing `FuelEconomyTracker` (`if (maf != null ...)`) and `DerivedSensorCatalog` to skip all fuel, KPL, DTE, HP, and Torque computations on the 2024 Avanza even though `0104` (`Engine Load`), `010C` (`RPM`), `010F` (`IAT`), `0133` (`Baro`), `0124` (`O2 WR Lambda`), `0134`, `0144`, `0145`, and Toyota/Daihatsu Mode `21` (`2100..2140`) are natively supported.

## What Changes

- **Deep Scan Live Progress, Spinner, Auto-Resume Polling & Share `ecu_dump.txt` (`HudViewModel`, `EcuDiscoveryDumper`, `HomeSheets`):** Stream step progress into `_explorerStatus` (`Scanning CAN headers... -> Mode 01 PIDs... -> Saved`), show a loading progress indicator inside `EcuPidsSheet`, add a **Share ECU Dump** action for `files/ecu_dump.txt`, expand Mode `21` probing (`2123`, `2125`, `2126`, `212B`, `212F`, `2131`), and automatically resume explorer polling after the dump completes when `ECU PIDs` is open.
- **Bulletproof `isCanProtocol` Detection (`Elm327Client`):** Evaluate `isCan` across **all** cleaned lines of both `ATDP` and `ATDPN` (plus CAN protocol code regex and `"CAN"` / `"15765"` matching on `protoCode` as well as `protoName`, with fallback header check if `ATDP`/`ATDPN` return ambiguous `AUTO`).
- **SAE `0104` Engine-Load Airflow Fallback & `0124`/`014C` Support (`TelemetryCalculator`, `ObdPidRegistry`, `ObdPollingEngine`):** When both `MAF` (`0110`) and `MAP` (`010B`) are unavailable while `0104` (`engine_load`) and `010C` (`rpm`) are present, estimate effective manifold pressure / airflow physically from `engine_load * baro` (`estimateMafFromEngineLoad`) so 2024 Avanza KPL, Trip Fuel, DTE, HP, and Torque work out of the box, decode `0124` (`o2_b1s1_wr_lambda`) & `014C` (`commanded_throttle`), and add `setOf("04", "0C")` to `DerivedSensorCatalog` fuel/power PID groups.

## Capabilities

### Modified Capabilities
- `obd-protocol`: Fix CAN protocol detection on multi-string `ATDPN` responses, add `0104` Load-based airflow/fuel fallback, decode `0124`/`014C`, and expand Deep Scan Mode `21` probing.
- `diagnostics`: Show live Deep Scan progress + spinner, resume explorer polling after scan finishes, and allow sharing `ecu_dump.txt` in-app.

## Impact

- `obd-core`: `Elm327Client.kt`, `ObdPidRegistry.kt`, `TelemetryCalculator.kt`, `ObdPollingEngine.kt`, `EcuDiscoveryDumper.kt`, unit tests.
- `app`: `HudViewModel.kt`, `HomeSheets.kt`, `HomeMenuScreen.kt`.
