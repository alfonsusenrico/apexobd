# Design — Real-Time Smoothing, Map Styles, Pause/Resume & Strava Route Export

## Context

This change addresses eight field-drive findings and requests observed on `RMX1921` (`Avanza 1.3L`) after the `hud-themes-bars-centralized-trip` release. Key diagnostics:
- **Verified Crash (`adb logcat -b crash`):** Exporting CSV/JSON at `11-17 23:24:15.431` threw `android.util.AndroidRuntimeException` at `TripExporter.kt:102` (`startActivity` from `Application` context without `Intent.FLAG_ACTIVITY_NEW_TASK`, executed on `Dispatchers.IO`).
- **Verified ECU Capabilities (`apex_obd.db`):** Avanza supports `04,05,06,07,0B,0C,0D,0E,0F,10,11,14,20,2E,33,40,42,45` (`0145` relative throttle supported, CAN protocol).
- **Verified OpenFreeMap Styles (`curl` HTTP 200):** `styles/dark`, `styles/positron`, `styles/liberty` (`fill-extrusion` 3D buildings supported), `styles/bright`, `styles/fiord` all resolve cleanly at HTTP 200.

## Decisions

### 1. Hardware Wire Latency First + Light 40ms Needle Damper (Not Heavy Retrospective Interpolation)
- **Problem:** Users observe `0.5–1.5s` real end-to-end delay (e.g. releasing gas pedal and waiting for RPM drop) even with `TelemetryDisplaySmoother`. Investigation of `ObdPollingEngine.kt` + `Elm327Client.kt` + `Avanza supportedPidsCsv` shows why:
  1. Currently 8+ PIDs sit in `PollTier.HIGH`, PLUS `expandDependencies` injects `maf`, `map`, `iat`, `baro`, `engine_load`, `coolant`, trims on every cycle.
  2. `chunked(5)` multi-PID requests (`> 7` bytes) trigger slow ISO-TP multi-frame flow control or fallback (`supportsMultiPid = false`), serializing **10–12 individual PID commands per cycle** (`~450–1,200 ms` per update!) under `ATAT1` / default ~200 ms `ATST`.
  3. Retrospective mathematical smoothing **cannot predict a future pedal lift-off** — it can only animate AFTER the slow ECU sample arrives, actually *adding* latency on top of OBD lag.
- **Decision:**
  - Keep raw `_liveValues` untouched for SQLite `telemetry_samples` and fuel/trip math.
  - Fix hardware polling cadence FIRST: keep ONLY primary rapid driver channels (`rpm`, `rel_throttle`, `pedal_d`/`pedal_e`, `obd_speed`, optional `maf`) in Fast Priority Tier 1; demote `engine_load`, `timing_advance`, `throttle` (when `0145` is supported), `map`, `baro`, and trims to rotating background scheduling (**max 1 slow PID per N fast cycles**); enable `ATAT2` + tight CAN timeout (`ATST 14`) and limit CAN multi-PID packing strictly to single-frame `<= 7` response bytes (eliminating ISO-TP multi-frame stalls). Add `"C0"` block discovery in `ObdPidRegistry` for newer 2024 ECUs.
  - Provide a clear `Gauge Smoothing` preference: `Light (40 ms)` default (subtle needle damper over ~12–20 Hz real frames with zero sluggish trail), `Instant Raw (0 ms)` (zero interpolation latency), or `Fluid Glide` (interval-paced visual transitions). Remove duplicate `animateFloatAsState` inside `AnalogDialWidget`.
- **Rejected Alternative:** Masking 500–1,200 ms polling delays with slow 300–500 ms retrospective easing curves adds extra lag when lifting off throttle or braking.

### 2. Lockstep 60 Hz Map Puck & Camera Interpolation
- **Problem:** `apex-puck-src` updated instantly on discrete 500–1000 ms GPS fixes while `mlMap.animateCamera(..., 420)` animated separately and was cancelled/restarted whenever Compose recomposed `LaunchedEffect(..., trail.size, ...)`.
- **Decision:** Drive both `apex-puck-src` GeoJSON coordinates and `mlMap.moveCamera(CameraUpdateFactory.newCameraPosition(...))` from one `withFrameNanos` animation state keyed smoothly on incoming `(lat, lon, bearingDeg, speedKmh)` targets. Interpolate shortest-path angular wrap `((target - cur + 540) % 360) - 180` and ease `(lat, lon)` over the observed GPS fix period (`clamp(dt, 350..900 ms)`) with tiny speed-projected lead (`<= 120 ms` cap, zero overshoot on stop).

### 3. Flashing Top OBD Connection Status Pill
- **Decision:** Observe `viewModel.connectionState` inside `HudCanvasScreen` (`Alignment.TopCenter`, safe-area aware, respects mirror flip).
  - `Connecting` / `Reconnecting(n, _)`: flashing electric-blue/amber capsule (`FINDING OBD2...` / `RECONNECTING #n...`).
  - `Disconnected` (when `!settings.useSimulator`): flashing red/amber pill (`OBD2 NOT CONNECTED · Tap Settings`).
  - Transition into `Connected`: brief `CONNECTED · <protocol>` confirmation toast for `1800 ms`, then fades out (`AnimatedVisibility`) leaving pure unobstructed `#000000` canvas.

### 4. Bottom-Reclaiming Radial Dial Geometry
- **Problem:** A `250°` dial (`startAngle = 145°`, `sweep = 250°`) ends vertically at `cy + 0.574 * r`, leaving everything below `0.574 * r` unused while sizing against `min(w, h) * 0.43`.
- **Decision:** Compute `r = minOf(maxWidth / 2.10, maxHeight / 1.68)` and center vertically at `cy = maxHeight * 0.59` (keeping top clearance `1.04 * r` and placing the readout label right inside the bottom opening above `maxHeight - 8dp`). This maximizes dial ring diameter in compact rectangular cells (e.g. `4×4` or `4×3`) and frees a full tile row beneath.

### 5. Trip Recording Pause / Resume & `MIGRATION_5_6`
- **Decision:**
  - Replace boolean `_isRecording` with `RecordingState` enum (`IDLE`, `RECORDING`, `PAUSED`), exposed to UI along with `paused: Boolean` and backward-compatible `isRecording: Boolean` (`!= IDLE`).
  - During `PAUSED`: suspend `TelemetrySampleEntity` DB writes, accumulate previously elapsed active driving milliseconds in `accumulatedActiveMs`, freeze `trip_time` / `trip_avg_speed` / fuel accumulation (`accumulatedFuelL`), and clear `prevGps` + segment trail discontinuity anchor so moving while paused does not stitch a straight jump or dilute `avgSpeedKmh`.
  - On `Stop / End`: finalize `TripSessionEntity` using `activeDurationSec = accumulatedActiveMs / 1000L` (stored in Room column `activeDurationSec` with fallback `(endedAtMs - startedAtMs)/1000L` for legacy rows) and compute `avgSpeedKmh` strictly from moving/active duration.
  - UI controls on `MiniMapWidget`: while `IDLE`, single red circular `REC` button; while `RECORDING` / `PAUSED`, compact pill housing circular `[⏸ / ▶]` (Pause / Resume) beside `[■]` (End Trip), plus top badge `REC · mm:ss · km` vs amber `PAUSED · mm:ss · km`.

### 6. Map Styling & Camera Presets (For Owner Selection)
- Support 6 selectable styles persisted in `AppPreferences` (`mapStylePreset`):
  1. `DARK_CONTRAST` (Default): OpenFreeMap `dark` + `enhanceDarkStyleContrast` + `.pmtiles` dark palette
  2. `FIORD_NAVY`: OpenFreeMap `fiord` deep slate-navy cluster aesthetic (+ `.pmtiles` navy palette)
  3. `LIBERTY_3D`: OpenFreeMap `liberty` daylight map with 3D building extrusions
  4. `BRIGHT_NAV`: OpenFreeMap `bright` crisp Google-Maps-like navigation roads & shields
  5. `POSITRON_LIGHT`: OpenFreeMap `positron` clean monochrome light style (+ `.pmtiles` light palette)
  6. `OLED_TRACK`: `#000000` pure black zero-tile vector trail
- Support Camera Config (`mapCameraMode`): `NAV_3D_45` (`45°` tilt, `16.2` zoom), `CHASE_LOW_55` (`55°` tilt, `16.8` zoom, closer horizon), `TOP_DOWN_0` (`0°` flat heading-up, `15.4` zoom), plus `highContrastRoads: Boolean` toggle.

### 7. Vertical Canvas Pager
- Replace `HorizontalPager` in `HudCanvasScreen.kt` with `VerticalPager` (`state`, `userScrollEnabled = !editMode`). Retain vertical dot bar on `Alignment.CenterEnd` (now naturally matching swipe direction) in both portrait and landscape.

### 8. CSV/JSON Export Crash Fix, Strava PNG Card & Vehicle Metadata
- **Export Fix:** Update `TripExporter.shareFile` and `HudViewModel.exportSession` to write files asynchronously on `Dispatchers.IO`, construct `ClipData.newUri`, add `Intent.FLAG_ACTIVITY_NEW_TASK` to both inner intent and `Intent.createChooser(...)`, launch chooser inside `withContext(Dispatchers.Main)`, and guard with `runCatching` + `Downloads/ApexOBD/` fallback notice if no share handler responds.
- **Strava Route Image Export (`TripCardRenderer`):** Render an offscreen Android `Bitmap` (`1080×1350` or `1440×1440` portrait card) off GPS coordinates + `TripSessionEntity` + active `VehicleProfileEntity`:
  - Background: car's `photoUri` (center-cropped, subtly darkened scrim `0.58` black gradient for guaranteed text contrast) or sleek OLED dark telemetry mesh + grid when no car photo is set.
  - Route Graphic: normalized smooth rounded polyline with cyan/electric-blue glow casing, green Start dot, red Finish flag/dot, start→end bearings scaled with balanced padding.
  - Overlay Stats & Identity: Vehicle name + spec pill (`1329 cc · MT · MPV · 2019`), date/time, big hero metrics (`Distance`, `Active Time`, `Avg Economy`, `Fuel Used`), secondary strip (`Avg Speed`, `Max Speed`, `Peak RPM`), and subtle ApexOBD badge. Share via updated `TripExporter.shareImageFile` (`image/png`, `FLAG_ACTIVITY_NEW_TASK`) from both `Saved Runs` (`Card PNG`) and Stop Modal!
- **Vehicle Profile Metadata (`MIGRATION_5_6`):** Add `photoUri: String?`, `engineCc: Int` (synced cleanly with `displacementLiters = engineCc / 1000.0`), `carType: VehicleBodyType` (`SEDAN`, `HATCHBACK`, `MPV`, `SUV`, `CROSSOVER`, `PICKUP`, `COUPE`, `WAGON`, `EV`), `transmission: TransmissionType` (`MANUAL`, `AUTOMATIC`, `CVT`, `DCT`, `SINGLE_SPEED_EV`), and `modelYear: Int?`. Selecting `carType` / `transmission` automatically suggests realistic defaults for `dragCoefficientAreaCdA`, `rollingResistanceCrr`, and `drivetrainLossPct` (with user override preserved if manually customized in future advanced view), directly sharpening speed-density MAF, aero power, and wheel HP calculations in `obd-core`.

## Compatibility, Migration & Risks

- **Room Migration `MIGRATION_5_6`:** `ALTER TABLE vehicle_profiles ADD COLUMN photoUri TEXT`, `ADD COLUMN engineCc INTEGER NOT NULL DEFAULT 1300`, `ADD COLUMN carType TEXT NOT NULL DEFAULT 'MPV'`, `ADD COLUMN transmission TEXT NOT NULL DEFAULT 'AUTOMATIC'`, `ADD COLUMN modelYear INTEGER`; backfill `engineCc = CAST(ROUND(displacementLiters * 1000) AS INTEGER)` from existing rows (`1.3 -> 1300`) so user's existing `Avanza 1.3L` stays lossless! `ALTER TABLE trip_sessions ADD COLUMN activeDurationSec INTEGER`. Never destructive (`pm clear` never needed).
- **Risk (`photoUri` gallery access):** On Android API 29+, `ActivityResultContracts.GetContent()` (`image/*`) URIs lose permission across app restarts unless we copy the compressed JPEG (`max 1600px`) into `filesDir/vehicle_photos/vehicle_<id>.jpg` on pick! Copying locally guarantees offline/permanent reliability even if the user deletes the gallery original.
- **Open Question:** Owner confirmation of preferred default Map Style preset & approval of full plan.
