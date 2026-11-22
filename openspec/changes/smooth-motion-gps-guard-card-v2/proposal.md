# Proposal: Zero-Freeze 60 Hz Motion, GPS Guard, Route Repair, Card Polish & Inset Fix

**Approved by owner:** `2026-11-19` (defaults + selected alternatives confirmed in session).

## Why

Morning road and on-device testing (`Session 14` on `RMX1921`, `2026-11-19`) exposed six concrete issues:
1. **Stepped / Freeze-Then-Jump Gauges:** `AdaptiveSession` completes linear ramps early (`0.68x` of poll interval, `<= 440 ms` max) and freezes flat (`----`) across `251–1261 ms` gaps while `kpl_road_avg` bypasses smoothing and `%` gauges round at `0.1`. Owners want continuous, un-stepped live motion across `rpm`, `engine_load`, `throttle`/`rel_throttle`, and `KPL`, plus an optional micro-motion toggle to test A/B.
2. **GPS Speed Decimals:** `gps_speed` below `100` displays `%.1f` decimals; the owner requires pure integer truncation with no decimal point and no rounding.
3. **Silent GPS-Off State & Dormant Map After Toggle:** Entering the HUD with system GPS disabled shows no warning or quick enable button, and toggling GPS on mid-drive leaves `FusedLocationProviderClient` dormant because `LocationTracker` never monitors provider transitions or restarts updates on availability changes.
4. **Straight-Line Card Export on Partial-GPS Trips:** `HudViewModel.startRecording()` saved fake `GpsSnapshot` default Jakarta coordinates (`-6.2088, 106.8456`) when `hasRealFix == false` (`209` initial samples in `Session 14`) before real Surabaya fixes arrived (`52.7 s`), causing `TripCardRenderer` to fit a `~650 km` cross-Java diagonal line while Home Menu looked normal by centering on the last point.
5. **Card Studio Surrounding Roads, Naming & Redundancy:** The `8%` linear edge mask shows a rectangular box border and fixed `60%` density feels too crowded; aspect ratio chips read `"1:1 Post"`, `"4:3 Wide"`, `"9:16 Story"` instead of `"1:1"`, `"4:3"`, `"9:16"`; and `Background Style` has both `"Custom Photo"` and redundant `"+ Add Image"`.
6. **Bottom Sheet Screen Overflow:** On `RMX1921` (`1080x2340`), `ApexLockedSheet` (`Dialog`) overlaps system insets (`parent=[0,77][1080,2340]` vs `2339 px` content and missing bottom gesture insets), clipping `~77 px` off `"Cancel"` / `"Export"`.

## What Changes

- Replace early-stopping ramps in `TelemetryDisplaySmoother.AdaptiveSession` with continuous full-period smooth glide (`kpl_road_avg` included), format fractional `%` and `km/L` HUD gauges at `2` decimals (`%.2f`), truncate speed gauges strictly to integer (`toInt()`, no rounding), and expose an opt-in UI-only `"Live Display Micro-Pulse"` switch in Settings (`false` by default).
- Add system Location state detection, provider-change auto-restart + `getCurrentLocation` recovery in `LocationTracker`, immediate HUD-entry GPS warning with native Play Services / Android Settings enable prompt, `null`-coordinate logging when `!hasRealFix`, and outlier/sentinel filtering in `TripCardRenderer` + `HomeMenuScreen` so existing `Session 14` recovers its true Surabaya path.
- Refine `Create Card` UI & renderer: rename ratio chips strictly to `"1:1"`, `"4:3"`, `"9:16"`, merge `"+ Add Image"` into the single `"Custom Photo"` chip (with inline edit when active), replace the surrounding-roads checkbox with a seamless 2D elliptical vignette + `Surrounding Roads` toggle, `Opacity` slider (`10%–60%`, default `25%`), and `Main Roads Only` checkbox (`default: true`), and fix `ApexLockedSheet` / `SaveTripSummaryDialog` / screen bottom insets so no action buttons clip past the bottom viewport.

## Impact

- Capabilities affected: `obd-protocol`, `hud-canvas`, `logging-tracking` (no new project capability).
- Non-goals: Altering raw logged OBD or fuel economy calculations when visual micro-pulse is toggled (strictly UI display layer).
