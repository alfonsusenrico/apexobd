# Design: Reliable Multi-ECU PID Discovery and 60 FPS Predictive Map Gliding

## Decisions

### Decision 1: Post-Discovery `ATST19`, Multi-ECU Bitmask Union, and Advertised-Block Retries
- `Elm327Client.initialize()` keeps default `ATAT1` adaptive timing throughout handshake and discovery. `Elm327Client.applyLivePollingTiming(handshake, aggressiveTiming)` applies `ATST19` on CAN only *after* `discoverSupportedPids`, `ToyotaMeterProtocol.discover`, and `DtcDecoder.readVin` finish.
- `Elm327Client.extractAllPidPayloads(lines, expectedModeResp, expectedPid)` returns payloads from *every* matching CAN ECU response line (e.g. `7E8` Engine + `7E9` TCM), and `ObdPidRegistry.discoverSupportedPids()` unions all decoded bitmasks for each block.
- For block `"00"` and any subsequent block explicitly advertised by the previous block's bitmask (`nextBlockCode in blockSupported`), `discoverSupportedPids()` retries up to 3 attempts (standard `"01XX"`, retry after 80 ms, then `"01XX1"` fallback) before stopping.
- `HudViewModel.persistDiscoveredPidsToVehicle()` preserves previously confirmed Mode 01 PIDs whenever a transient partial connect returns a strict subset (`curMode01.isNotEmpty() && prevMode01.containsAll(curMode01) && curMode01.size < prevMode01.size`).

### Decision 2: 60 FPS Predictive Map Glider (`MapMotionGlider`)
- Hardware GNSS emits fixes at ~1 Hz (`1000 ms`). Chasing a static target with `tau = 160 ms` causes 95% movement in 480 ms followed by a 520 ms stall every second.
- `MapMotionGlider` maintains:
  - Anchored GPS fix `(anchorLat, anchorLon, anchorTimeMs)` updated whenever a new fix arrives.
  - Continuous dead-reckoned target `(predLat, predLon)` advancing every VSync frame along smoothed heading at `speedKmh / 3.6` m/s (clamped to `<= 1.4 s` extrapolation since last fix, and disabled below `1.5 km/h`).
  - Displayed camera/puck coordinates `(dispLat, dispLon, dispBearing)` that softly converge toward `(predLat, predLon, targetBearing)` (`posTauMs = 260 ms`, `rotTauMs = 240 ms`) without stop-and-go braking.
- GeoJSON puck updates are skipped when the displayed pose changes by `< 1e-7` deg and `< 0.05` deg bearing.
