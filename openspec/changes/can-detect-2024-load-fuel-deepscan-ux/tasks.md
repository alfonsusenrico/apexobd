# Tasks

> Status: Approved by owner (`2026-11-18`) and verified across unit tests (`43/43`), Android lint (`0` errors), debug APK build, and live `RMX1921` install (`verification.md`).

## Phase 1 — `obd-core` CAN Detection, Load Fallback & Expanded Deep Dump
- [x] 1. Fix `Elm327Client.initialize` CAN detection across multi-line `ATDP`/`ATDPN` strings containing `"CAN"`/`"15765"` anywhere (e.g. `AUTO, ISO 15765-4 (CAN 11/500)`).
- [x] 2. Add `0124` (`o2_b1s1_wr_lambda`) and `014C` (`commanded_throttle`) in `ObdPidRegistry`, add `estimateMafFromLoad` / `inferredMapKpa` fallback when `maf == null && mapKpa == null && engineLoadPct != null` in `FuelEconomyTracker` + `ObdPollingEngine`, and allow `setOf("04", "0C")` in `DerivedSensorCatalog` so 2024 Avanza exposes KPL/Fuel/DTE/HP/Torque cleanly.
- [x] 3. Add `onProgress` callback and expanded `2120` range probes (`2123`, `2125`, `2126`, `212B`, `212F`, `2131`) to `EcuDiscoveryDumper`, plus unit tests in `ObdPidRegistryTest`, `TelemetryCalculatorTest`, and `EngineAndPerformanceTest` verifying `ATDPN = "AUTO, ISO 15765-4 (CAN 11/500)"` and 2024 Load-only fuel/DFCO math.

## Phase 2 — `app` Deep Scan Progress UI, Auto-Resume & Share `ecu_dump.txt`
- [x] 4. Add `deepScanRunning`, `ecuDumpAvailable`, `_explorerStatus` live progress updates, `shareEcuDump()`, and post-scan explorer polling auto-resume in `HudViewModel` + `HomeSheets.kt` (`EcuPidsSheet`).

## Phase 3 — Verification & In-Place Install
- [x] 5. Run `:obd-core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and `openspec validate --all --strict`, then install in-place on `RMX1921` (`adb install -r`) and verify `ECU PIDs` `Share Dump` + `Deep Scan` UI and zero crashes via `logcat`.
