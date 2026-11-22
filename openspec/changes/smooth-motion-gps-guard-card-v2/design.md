# Design: Zero-Freeze 60 Hz Motion, GPS Guard, Route Repair, Card Polish & Inset Fix

## Architectural Decisions

1. **Continuous Full-Period 60 Hz Glide + Optional UI Micro-Pulse (`TelemetryCalculator.kt`, `TorqueEvoWidgets.kt`, `AppPreferences.kt`)**
   - Why steps happened: `lightScale = 0.68`, `maxDurationMs = 440.0`, and `plateauResetMs = 1150L` caused ramps on `rpm`, `engine_load`, and `rel_throttle` (`251–1261 ms` cadence) to finish early and hold flat until the next poll.
   - Fix: Set `lightScale = 1.06`, `adaptiveScale = 1.15`, `maxDurationMs = 1150.0` (`1350.0` for `gps_speed`), raise `plateauResetMs = 2600L` for engine/throttle/fuel channels, add `"kpl_road_avg"` to `SMOOTHED_KEYS`, and use smooth continuous S-curve interpolation (`u * u * (3 - 2 * u)` for clean velocity handoffs or bounded critically-damped easing) so values stay in motion right up to the next poll without overshooting or flat plateaus.
   - Display precision: Truncate speed (`gps_speed`, `obd_speed`) via `floor(max(0.0, value)).toInt()` (zero rounding, zero decimals). Format fractional `%` (`engine_load`, `rel_throttle`, `throttle`, `pedal_d`, `pedal_e`) and `km/L` channels at `%.2f` so 60 Hz interpolated movement renders smoothly every frame.
   - Opt-in A/B alternative (`liveMicroMotion: Boolean = false`): When explicitly enabled in Settings, `HudViewModel`'s 60 Hz display coroutine adds a tiny bounded UI-only display shimmer (`<= 0.12%` on throttle/load, `<= 0.03 km/L` on KPL, `<= 4 RPM` on RPM) while `rpm > 300`, never touching raw `_liveValues`, `telemetry_samples`, or trip/fuel accumulators.

2. **Proactive GPS Guard & Auto-Recovery (`LocationTracker.kt`, `HudCanvasScreen.kt`, `HudViewModel.kt`, `TripCardRenderer.kt`)**
   - `LocationTracker` exposes `isGpsEnabled: StateFlow<Boolean>` monitored via `LocationManager.PROVIDERS_CHANGED_ACTION` / `MODE_CHANGED_ACTION`, `LocationCallback.onLocationAvailability`, and lifecycle `ON_RESUME`. When Location transitions `OFF -> ON`, `restartUpdatesAndFetchCurrent()` stops any stale FusedLocation callback, restarts `requestLocationUpdates`, and requests an immediate `getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY)`.
   - On `HudCanvasScreen` entry, whenever `!isGpsEnabled`, an immediate warning banner with `"Enable GPS"` triggers Google Play Services `SettingsClient.checkLocationSettings` (`IntentSenderRequest`) and falls back to `Settings.ACTION_LOCATION_SOURCE_SETTINGS` if non-resolvable.
   - `HudViewModel.startRecording()` persists `latitude = null, longitude = null` unless `gps.hasRealFix == true`.
   - `TripCardRenderer.extractValidGpsPoints` (shared via `TripCardRenderer.extractCleanGpsPoints` with `HomeMenuScreen`) filters unfixed Jakarta sentinel coordinates `(-6.2088, 106.8456)` when real fixes differ by `> 0.05°`, and trims minority isolated extreme-teleport prefixes/outliers (`> 0.15°` single-step jump) so existing runs like `Session 14` render their true Surabaya path while single-region synthetic `-6.2088` test trips remain intact.

3. **Seamless Surrounding Roads Vignette + Card Controls (`TripCardRenderer.kt`, `OfflineMapManager.kt`, `HomeSheets.kt`, `AppPreferences.kt`)**
   - `TripCardConfig` persists `surroundingRoadsOpacityPct: Int = 25` (`10..60`) and `surroundingRoadsMainOnly: Boolean = true`.
   - `OfflineMapManager.buildRoadsMaskStyleJson(pmtilesFilePath, mainRoadsOnly)` omits minor/service/track/path layers when `mainRoadsOnly = true`.
   - `TripCardRenderer.convertRoadsMaskToAlphaBitmap` replaces the rectangular `8%` edge fade with a smooth elliptical radial smoothstep fade (`d = hypot(nx, ny)` in normalized ellipse coordinates, `smoothstep` fading cleanly to `0` before boundaries) and stores full-resolution alpha mask in `RoadContextLayer` (`100%` base). `drawRouteTrace` multiplies paint alpha by `surroundingRoadsOpacityPct / 100f` at draw time so dragging the opacity slider updates the preview instantaneously without re-invoking `MapSnapshotter`.
   - Card UI simplifies aspect pills strictly to `"1:1"`, `"4:3"`, `"9:16"` and merges `"+ Add Image"` into the single `"Custom Photo"` chip (with an inline edit badge/tap when active).

4. **Safe Modal Dialog Window Insets (`HomeSheets.kt`, `HomeMenuScreen.kt`)**
   - Configure `DialogWindowProvider` inside `ApexLockedSheet` and `SaveTripSummaryDialog` (`WindowCompat.setDecorFitsSystemWindows(window, false)`, `MATCH_PARENT` size, transparent system bar backgrounds, display cutout `SHORT_EDGES` / `ALWAYS`) and apply `WindowInsets.safeDrawing.union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))` **outside** `BoxWithConstraints` so `maxHeight` equals the true safe drawable area above navigation/gesture bars (`Export` / `Cancel` never overflow).
