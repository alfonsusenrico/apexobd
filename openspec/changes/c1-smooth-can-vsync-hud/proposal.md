# Proposal: C1 SmoothDamp Physics, Fast CAN Multi-PID Isolation & VSync Deferred-Read HUD

**Approved by owner:** `2026-11-19` (`"use defaults"` in session).

## Why

Forensic audit of `Session 15` (`RMX1921`, `Avanza 2NR`, `OBDII v1.5`, build `2026-11-19 07:50:17`, `LIGHT_DAMPER`, `MAX_PERFORMANCE`, `17` Page 1 widgets) and `files/ecu_dump.txt` identified three compounding causes behind persistent gauge stepping on `rpm`, `engine_load`, `rel_throttle`, and `kpl_instant`:

1. **Piecewise Hermite Zero-Velocity Braking & Early Clamps (`TelemetryDisplaySmoother.AdaptiveSession`):** Each packet interval restarts a `3u^2 - 2u^3` Hermite ramp whose slope is zero at both `u = 0` and `u = 1` and freezes hard past `avgPeriodMs`. Simulation on `Session 15` produced `24.9%` frozen frames on `rpm`, `30.2%` on `engine_load`, and `56.1%` on `rel_throttle`.
2. **CAN Bus Choking & Permanent Multi-PID Disablement (`ObdPollingEngine` + `Elm327Client`):** On this ECU, `34` Mode `01` PIDs take `35–42 ms`, while `0142` (`voltage`, `142 ms`), `0144` (`lambda`, `139 ms`), and `0145` (`rel_throttle` alone, `144 ms`) take 3.5x longer. Yet `010C451` (2-PID CAN Single-Frame with trailing `'1'`) ran in **`40 ms`**. `ObdPollingEngine` omitted trailing `'1'`, mixed all secondary PIDs into `cycle 0`, latched `supportsMultiPid = false` permanently after a single empty decode, and polled `0142`/`0144`/`0145` so frequently that `engine_load` stalled to **`502 ms` median (`760 ms` p90, `1.569%` median step, `139` gaps > `1.5 s`)**.
3. **Full-Tree 60 Hz Root Recomposition & Main-Thread Allocation Jitter (`HudViewModel` + `HudCanvasScreen` + `TorqueEvoWidgets`):** Collecting `displayValues` and `historyBuffers` at the `HudCanvasScreen` root via an unsynced `delay(16L)` loop recomposed `VerticalPager` and all `17` widgets (including `MiniMapWidget`, `BoxWithConstraints`, `18`-box `SegmentedBarGaugeWidget`, and `catalogItemFor()` rebuilding ~`45` objects per widget every frame).

## Expected Outcome

Dynamic gauges (`rpm`, `engine_load`, `rel_throttle`, `gps_speed`, `obd_speed`, `kpl_instant`) flow continuously across hardware VSync frames without stop-start Hermite pauses or `~500–1750 ms` CAN stalls, while non-updating and map widgets stay at zero recomposition during telemetry ticks.

## What Changes

- Replace piecewise Hermite ramps in `TelemetryDisplaySmoother.AdaptiveSession` with a C1 critically damped 2nd-order spring (`SmoothDamp`, *Game Programming Gems 4*) carrying continuous velocity across packet boundaries, plus calmer `~0.35s` C1 damping for `kpl_instant` (`Option 2A`) and keeping `live_micro_motion` opt-in (`false` default, `Option 1A`).
- Enforce `ATCAF1` in `Elm327Client`, isolate core fast CAN multi-PID packs (`[rpm, engine_load, obd_speed, rel_throttle]`) with trailing `'1'`, replace permanent `supportsMultiPid = false` latch with streak-based backoff + periodic retry, and apply explicit minimum polling cooldowns to slow `>100 ms` secondary PIDs (`voltage` ~`2.2s`, `lambda`/trims/thermal low-frequency) plus avoid `cycle 0` secondary flooding.
- Replace the `delay(16L)` smoothing loop and root `displayValues`/`historyBuffers` collection with Compose VSync `withFrameNanos`, per-key `State<Double?>` slots (`HudLiveValueStore`), O(1) cached `catalogItemFor` lookup, Draw-phase `Canvas` rendering (including single-`Canvas` 12/18-segment bars in `SegmentedBarGaugeWidget`), and isolated leaf digit `Text` updates.

## Non-goals

- Modifying raw persisted telemetry (`telemetry_samples`), fuel calculation math (`FuelEconomyTracker`), or `live_micro_motion` default (`false`).

## Impact

- Capabilities affected: `obd-protocol`, `hud-canvas` (no new project capability).
