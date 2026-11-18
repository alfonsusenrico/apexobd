# Design — CAN Auto-Detection Fix, 2024 Avanza Load-Based Fuel/Power & Deep Scan UX

## Evidence From 2024 Avanza `ecu_dump.txt` (`5,961 B`, `29.7s`)

- Adapter identity: `OBDII v1.5`, `ATI -> A6`, `ATRV -> 13.1V`.
- Handshake logged: `ATDP -> Auto`, `ATDPN -> AUTO, ISO 15765-4 (CAN 11/500)`, `isCan=false`.
- ECU CAN header check: `0100 (ATH1) -> 7E8064100BE1EA813` (true 11-bit 500 kbps CAN).
- `010C1` RTT: `45 ms` (~`22 Hz`), whereas individual standard polling without `isCan` optimization ran `~80–105 ms` (up to `280 ms`).
- Supported Mode `01` PIDs (`37`): `01, 03, 04, 05, 06, 07, 0C, 0D, 0E, 0F, 11, 13, 15, 1C, 1F, 20, 21, 24, 2E, 30, 31, 33, 34, 3C, 3E, 40, 42, 44, 45, 46, 47, 49, 4A, 4C, 4D, 4E, 51`. Neither `0110 MAF` nor `010B MAP` is present.
- OEM Mode `21`: `2100 -> 6100BC000001` (`2101`, `2103`, `2104`, `2105`, `2106`, `2120`), `2120 -> 61202C0022D1` (`2123`, `2125`, `2126`, `212B`, `212F`, `2131`, `2132`, `2134`, `2138`, `2140`).

## Decisions

### 1. Robust CAN Detection in `Elm327Client`
- Collect **all** cleaned lines from `ATDP` (`protoName`) and `ATDPN` (`protoCode`) rather than `.firstOrNull()`, retry `ATDP` once if it equals `"Auto"`, and detect CAN if ANY of these holds:
  - `Regex("""\bA?[6-9A-C]\b""").containsMatchIn(protoCode)`
  - `protoName` or `protoCode` contains `"CAN"` or `"15765"` (case-insensitive)
- Restores `ATST19` (`100 ms`), `"01xx1"`, and `packSingleFrameCanChunks` on both Avanzas immediately.

### 2. Deep Scan Progress, Resume Explorer & Share `ecu_dump.txt`
- Add `onProgress: (String) -> Unit = {}` callback to `EcuDiscoveryDumper.runFullReadOnlyScan(...)` updating `_explorerStatus` at each stage (Handshake, ECUs, Mode 01 PIDs `i/N`, Benchmark, Mode 06/09, Mode 21/22), plus `deepScanRunning: StateFlow<Boolean>` + `hasEcuDump: StateFlow<Boolean>` in `HudViewModel`.
- Render a clean `LinearProgressIndicator` / spinner and **Share Dump** button inside `EcuPidsSheet`.
- Track `_onExplorerSheet: MutableStateFlow<Boolean>` (set in `forceConnectAndDiscoverForExplorer` / `restoreVisibleCanvasPolling`) so when `runDeepEcuDump()` finishes, it restarts explorer polling cleanly when the sheet is still open (as well as HUD/recording cases).

### 3. NA `0104 Engine Load` Fallback Airflow & Pressure Model (`2024 Avanza`)
- When both `mafGramsPerSec == null` and `mapKpa == null`, while `engineLoadPct != null` and `rpm != null`:
  - Infer effective intake manifold pressure for DFCO & VE physics cleanly from SAE J1979 Load ratio:
    `inferredMapKpa = effectiveBaro * ((engineLoadPct.coerceIn(0.0, 100.0) / 100.0) * 0.94 + 0.04)`
  - Compute `mafFromLoad` via `estimateMafSpeedDensity(...)` using `inferredMapKpa` + `vePct` + `iat` + `rpm` (`2NR-VE 1.496L`).
- Add `0124` (`o2_b1s1_wr_lambda`) and `014C` (`commanded_throttle`) decoders to `ObdPidRegistry` and allow `setOf("04", "0C")` in `DerivedSensorCatalog` so all fuel/KPL/DTE/HP/Torque widgets activate automatically on `2024 Avanza`.
