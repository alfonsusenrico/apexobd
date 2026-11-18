# Proposal

Status: **Approved by owner instruction on `2026-11-18`** ("ok do it", accepting recommended defaults after night-ride DB and code audit of `Avanza K3VE` Sessions `11` and `12`).

## Why

1. **Interval-Stepped `gps_speed` & Slow Secondary Gauges (`LocationTracker.kt`, `HudViewModel.kt`, `ObdPollingEngine.kt`, `TelemetryCalculator.kt`, `TorqueEvoWidgets.kt`):** Phone GNSS delivers fixes at `~1007 ms` median while `LIGHT_DAMPER` (`tau = 26 ms`) and `ADAPTIVE_GLIDE` (`gapMs > 260 ms -> 95 ms`) finish moving within `~95–100 ms` and freeze flat for the rest of the second. Meanwhile `HudViewModel` delayed GPS emission behind OBD polling cycles, injected redundant `throttle`/`map` PIDs into `HIGH` priority during Run Recording, starved visible `timing_advance` / `voltage` to `3.5–4.0 s`, and `SegmentedBarGaugeWidget` stepped in whole `18.3 km/h` binary blocks across `0..220 km/h`.
2. **Inflated Estimated Range (`FuelEconomyTracker`, `HudViewModel.kt`, `HomeMenuScreen.kt`):** Even with `41.48 L` left and `allTimeTotalAvg = 11.645 km/L` (`483 km` raw ceiling), `recentDteKpl` (`12.731 km/L`) skewed both `Home` and `range_dte` to `528 km`, without accounting for cumulative dominance or a tank reserve cushion.
3. **Route Card Customization & Export Flow (`TripCardRenderer.kt`, `HomeSheets.kt`, `AppPreferences.kt`, `HudViewModel.kt`):** Component toggles and layout choices reset on every share, users could not pick a background image on the fly, select `1:1` / `4:3` / `9:16` cards, choose Share vs Gallery Download on Export, or render surrounding map roads at `60%` opacity underneath the glowing tracked route.

## What Changes

- Decouple `locationTracker.gps` live emission from OBD loop blocking and update `TelemetryDisplaySmoother` so `LIGHT_DAMPER` and `ADAPTIVE_GLIDE` glide continuously across each channel's true inter-update period (including `~1 Hz` `gps_speed` with fast deceleration convergence) instead of twitching on polls and freezing.
- Add continuous sub-block fractional fill to `SegmentedBarGaugeWidget` while preserving empty dark outlines for unlit segments and the smooth multi-stop spectrum.
- Eliminate redundant `throttle`/`map` duplicate `HIGH`-tier polling during Run Recording when `rel_throttle` (`0145`) and `maf` (`0110`) are available, and improve refresh cadence for visible `timing_advance` and `voltage`.
- Anchor DTE fuel economy conservatively to `allTimeTotalAvgKpl` and deduct a `10%` tank reserve floor consistently across `FuelEconomyTracker`, `HudViewModel`, and `HomeMenuScreen`.
- Upgrade Route Card workflow to `Create Card` with async loading indicator, persisted `TripCardConfig` state, on-the-fly custom background photo selection, `1:1` / `4:3` / `9:16` aspect ratios, optional `60%` opacity surrounding area roads overlay via `MapSnapshotter`, and `Export` chooser (`Share to Apps` vs `Download PNG` to `Pictures/ApexOBD`).

## Capabilities

### New Capabilities
- `hud-canvas`: Continuous inter-poll gauge visual smoothing (`60 Hz`), decoupled live GPS speed updates, and leading-block fractional LED progress inside segmented boxed bars.
- `obd-protocol`: Cumulative-anchored DTE fuel economy with tank reserve floor across live/home calculations, plus streamlined single-PID Run Recording polling without duplicate throttle/MAP saturation or `3.5s` timing/voltage starvation.
- `logging-tracking`: Persistent, multi-aspect (`1:1`, `4:3`, `9:16`) Route Card composer (`Create Card`) with loading states, on-the-fly background photo picking, `60%` opacity surrounding map road context, and `Export` share-or-download actions.

### Modified Capabilities

## Impact

- `obd-core`: `TelemetryDisplaySmoother`, `FuelEconomyTracker`, `ObdPidRegistry`, `ObdPollingEngine`, and `TelemetryCalculatorTest`.
- `app`: `AppPreferences`, `HudViewModel`, `TorqueEvoWidgets`, `TripCardRenderer`, `TripExporter`, `HomeSheets`, `HomeMenuScreen`, and unit tests (`HudWidgetStyleTest`, `TripShareStatsCalculatorTest`).
