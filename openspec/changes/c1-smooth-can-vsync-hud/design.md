# Design: C1 SmoothDamp Physics, CAN Fast-Pack Scheduling & Deferred-Read VSync HUD

## Context

`Session 15` (`Avanza 2NR`, `OBDII v1.5`, `37` Mode 01 PIDs) proved that visual stepping came from three compounding layers rather than a single constant: piecewise `u * u * (3 - 2 * u)` Hermite braking, CAN Single-Frame Multi-PID loss / slow-PID stalling (`0142` `142 ms`, `0144` `139 ms`, `0145` `144 ms` vs `010C451` `40 ms`), and root-scoped 60 Hz Compose recomposition over an unsynced `delay(16L)` loop.

## Goals & Non-Goals

- **Goals:** C1 velocity continuity across arbitrary packet arrival gaps; `~80–120 ms` fast-tier CAN update cadence on `rpm`, `engine_load`, `obd_speed`, `rel_throttle`; calmer sweep on `kpl_instant`; VSync-locked Draw-phase gauge rendering with near-zero Compose allocation on the Main thread.
- **Non-Goals:** Adding fake sinusoidal oscillation by default (`liveMicroMotion` remains opt-in `false`); altering Room sample persistence or historical trip cards.

## Decisions

### Decision 1: C1 Critically Damped Spring (`SmoothDamp`) Over Hermite Ramps
- **Choice:** Maintain per-channel `(pos, vel, target, avgPeriodSec)` state in `TelemetryDisplaySmoother.AdaptiveSession` integrated with exact critically damped exponential decay `SmoothDamp` (*Game Programming Gems 4*, Ch. 1.10) with overshoot clamping on resting targets and adaptive `smoothTime` scaled to `avgPeriodSec`.
- **Rejected Alternative:** Tuning Hermite exponents or `tauMs` first-order LPF alone. Hermite has `du/dt = 0` at `u = 0, 1` and hard-freezes past `durationMs`; first-order LPF resets instantaneous corner acceleration (`C0` only) when `target` jumps.
- **Channel Tuning:**
  - Fast dynamic (`rpm`, `rel_throttle`, `engine_load`, `throttle`, `pedal_*`): responsive `smoothTime` scaled cleanly to `avgPeriodSec` (`[0.11s..0.34s]` for `LIGHT_DAMPER`, `[0.16s..0.48s]` for `ADAPTIVE_GLIDE`) so consecutive `~100 ms` packets overlap cleanly with nonzero velocity.
  - `kpl_instant` (`Option 2A`): calmer `smoothTime` (`[0.28s..0.55s]`) to absorb `4–5 km/L` discrete fuel-flow steps smoothly.
  - `gps_speed`: `smoothTime ≈ 0.36 * avgPeriodSec` (`stopSmoothSec = 0.038s` when slowing to `0 < 0.8 km/h`) so 1 Hz GPS fixes glide across the full second yet clear promptly on stop.

### Decision 2: Isolated Fast-Pack CAN Multi-PID & Cooldown-Throttled Slow PIDs
- **Choice:**
  - Send `"ATCAF1"` during `Elm327Client.initialize`.
  - Keep `highTargets` (`rpm`, `rel_throttle`, `engine_load`, `obd_speed`, etc.) in separate `packSingleFrameCanChunks` batches (`<= 7` response bytes), appending trailing `'1'` on `isCanProtocol` multi-PID commands (matching the verified `40 ms` `010C451` benchmark).
  - Never disable `supportsMultiPid` permanently on a single empty response or slow secondary chunk: poll secondary/slow PIDs singly, require `>= 3` consecutive core multi-PID failures before a `15 s` cooldown, and retry automatically afterward.
  - Enforce explicit `lastPollMs` cooldowns on slow/secondary channels (`voltage 0142`: `>= 2200 ms`, `lambda 0144`/WR O2/trims/fuel status: `>= 1000 ms`, thermal `coolant`/`iat`/`ambient`/`baro`: `>= 2500 ms`), seeding at most `1` due secondary item per cycle so `cycle 0` never floods the bus and high-tier targets run unhindered.
  - In single-PID fallback, inject `midRpmIndex` only when `others.size >= 4`.
- **Rejected Alternative:** Putting all `slowAll` PIDs into `cycle 0` multi-PID chunks or running `0142` in `visibleDynamic` every second cycle.

### Decision 3: VSync `withFrameNanos` + Deferred-Read `HudLiveValueStore` + Canvas Draw Isolation
- **Choice:**
  - Replace the coroutine `delay(16L)` loop with Compose `withFrameNanos` inside `HudCanvasScreen` calling `viewModel.stepVisualDisplayFrame(frameMs)`.
  - Store live visual values in `@Stable HudLiveValueStore` (`HashMap<String, MutableState<Double?>>`) plus per-key `historyStates`, cached `catalogLookup` map (updated only on `_supportedPids`/`customPids` change), and `hasVisibleTimer0100` flag.
  - Pass `() -> Double?` providers into `AnalogDialWidget`, `SemiDigitalArcWidget`, `BarGaugeWidget`, `SegmentedBarGaugeWidget`, `SparklineChartWidget`, `DigitalReadoutWidget`, and `FuelControlWidget` so `Canvas` / `drawBehind` reads happen purely in Draw phase and formatted numeric `Text` reads inside a small leaf composable. Replace `18` `Box` nodes in `SegmentedBarGaugeWidget` with a single `Canvas`.
- **Rejected Alternative:** Optimizing `AdaptiveSession` alone while keeping root `displayValues.collectAsStateWithLifecycle()`. Root recomposition + `catalogItemFor` allocations still stall ART GC on a 17-widget page with an embedded `MapView`.

## Risks & Mitigations

- **Compatibility with Multi-PID Mock Tests:** Existing `EngineAndPerformanceTest` mocks reject commands matching `cmd.startsWith("01") && cmd.length > 5` with `NO DATA`. Streak-based fallback + immediate single-PID fallback within the same cycle preserves exact behavior and passes all existing tests.
- **Initial Value Latency on `cycle 0`:** Because every `lastPollMs` initializes to `0L` and visible secondary items take priority over background helpers, visible gauges like `voltage` populate within the first cycles without starving fast RPM/Load.
