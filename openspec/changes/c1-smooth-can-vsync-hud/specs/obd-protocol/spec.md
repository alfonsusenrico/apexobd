# obd-protocol Specification

## ADDED Requirements

### Requirement: C1 Critically Damped Display Interpolation (`SmoothDamp`)
`TelemetryDisplaySmoother.AdaptiveSession` SHALL integrate rapid HUD telemetry channels with a C1 critically damped 2nd-order spring (`SmoothDamp`) that preserves continuous velocity across irregular OBD/GPS sample boundaries without Hermite endpoint braking (`v = 0` at `u = 0/1`) or premature mid-interval freezes, while using calmer damping (`~0.35s` scale) on `kpl_instant` and snappy stop settling (`<= 220 ms` to zero) on rolling speeds.

#### Scenario: Jittered 250–1260 ms ECU updates glide continuously with nonzero handoff velocity
- **WHEN** `rpm`, `engine_load`, or `rel_throttle` receives consecutive updates at varying intervals in `LIGHT_DAMPER` or `ADAPTIVE_GLIDE`
- **THEN** position and velocity evolve continuously across every update with zero Hermite stalls at poll arrivals and zero overshoot past resting targets

#### Scenario: `kpl_instant` damps large step swings smoothly while `gps_speed` clears stops promptly
- **WHEN** `kpl_instant` steps steeply (`e.g. 10.5 -> 6.0`) or `gps_speed` decelerates from `28 km/h` to `0.0 km/h`
- **THEN** `kpl_instant` sweeps smoothly across `~0.35s` without snapping and `gps_speed` settles within `0.01 km/h` of `0.0` inside `220 ms`

### Requirement: Isolated Fast CAN Multi-PID Packing & Streak Recovery
`Elm327Client.initialize` SHALL enable `ATCAF1`, and `ObdPollingEngine` SHALL pack `highTargets` into CAN single-frame multi-PID chunks (`<= 7` response payload bytes) with trailing `'1'` on CAN buses. Transient multi-PID failures SHALL trigger streak backoff (`>= 3` core failures -> `15 s` retry cooldown) rather than permanently disabling multi-PID on `cycle 0`.

#### Scenario: Transient multi-PID failure does not permanently disable CAN multi-PID polling
- **WHEN** an initial multi-PID request returns empty once or twice before succeeding or timing out transiently
- **THEN** `ObdPollingEngine` immediately polls that chunk singly for the current cycle without losing multi-PID capability permanently, and retries multi-PID after backoff cooldown if streak limit triggers

### Requirement: Slow & Secondary PID Cooldown Isolation
`ObdPollingEngine` SHALL poll secondary and slow `>100 ms` PIDs individually outside core fast multi-PID chunks, capped to at most one due item per cycle under per-key minimum intervals (`voltage` >= `2200 ms`, `lambda` and fuel trims >= `1000 ms`, thermal sensors >= `2500 ms`) so `cycle 0` and steady polling never starve `highTargets`.

#### Scenario: Slow `0142` (`142 ms`) Voltage and background helpers do not starve core Load/Throttle/RPM
- **WHEN** `rpm`, `engine_load`, `rel_throttle`, `obd_speed`, and `voltage` (`0142`) are active on Page 1 alongside background logging helpers
- **THEN** `highTargets` execute together on every fast cycle using CAN single-frame packs with `'1'` suffix while `voltage` and slow/helper PIDs rotate at most one due item per cycle under minimum cooldown intervals
