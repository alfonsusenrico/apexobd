# Technical Design — Continuous GPS Glide, Conservative DTE & Create Card Studio

## 1. Decoupled GPS Emission, Adaptive Inter-Poll Glide & Leading-Edge Boxed Bars
- **Decoupled GPS Telemetry (`HudViewModel.kt`):** Collect `locationTracker.gps` directly whenever `_onHudScreen` or `_isRecording` is active, immediately updating `engine?.updateExternalTelemetry(...)` and `_liveValues["gps_speed"]` without waiting up to `250–500 ms` for the next OBD polling emission.
- **Channel-Aware 60 Hz Display Glide (`TelemetryCalculator.kt`):**
  - Separate rapid driver-input channels (`rpm`, `throttle`, `rel_throttle`, `pedal_d`, `pedal_e`, `engine_load`, `maf`, `map`, `boost_*`, `hp`, `torque`) from slower rolling channels (`gps_speed`, `obd_speed`).
  - For rolling speed channels (`gps_speed`, `obd_speed`), track true consecutive fix/poll periods (`gps_speed`: `400..1150 ms`; `obd_speed`: `220..950 ms`) and interpolate continuously across the measured cadence (`0.82x` in `LIGHT_DAMPER`, `0.94x` in `ADAPTIVE_GLIDE`) so the display glides continuously across 1 Hz GPS seconds rather than settling in `<100 ms` and freezing flat for `900 ms`. Snap rapidly (`180–220 ms`) when decelerating to `<= 0.8 km/h` or launching from rest.
  - For rapid driver inputs, keep `gapMs > 1200L` plateau wake-ups snapping in `~110 ms` while active consecutive poll transitions track true inter-poll period (`65..380 ms`).
- **Continuous Leading-Block Fill (`TorqueEvoWidgets.kt`):** Inside `SegmentedBarGaugeWidget`, keep inactive future segments empty neutral dark boxes (`0x14FFFFFF` fill + `0x1EFFFFFF` outline), fill completed segments along `segmentedSpectrumColor`, and render partial `0..1` width progress inside the leading frontier box so `0..220 km/h` accelerates smoothly pixel-by-pixel between whole segments.

## 2. Lean Recording Schedule & Dynamic Secondary Gauge Cadence (`HudViewModel.kt`, `ObdPollingEngine.kt`)
- Split `setActiveSensors(visibleHudKeys)` and `setBackgroundRecordKeys(backgroundKeys)` in `ObdPollingEngine` so background Run Recording collects diagnostics/fuel/trims without promoting redundant `throttle` (`0111`) alongside `rel_throttle` (`0145`) or `map` (`010B`) alongside `maf` (`0110`) to `PollTier.HIGH`.
- When `timing_advance` or `voltage` (dynamic electrical/ignition gauges) are visible on the active HUD page, rotate them ahead of slowly drifting thermal sensors (`coolant`, `iat`, `ambient_temp`) every cycle so refresh intervals stay `~1.0–1.5 s` instead of `3.5–4.0 s`.

## 3. Cumulative-Anchored Conservative DTE + 10% Reserve Cushion (`TelemetryCalculator.kt`, `HudViewModel.kt`, `HomeMenuScreen.kt`)
- Expose `rawRecentDteKpl` separately from blended `dteEconomyKpl` on `FuelMetricsSnapshot`, and persist `rawRecentDteKpl` in `VehicleProfileEntity.recentDteKpl` (`HudViewModel.persistFuelMetricsToProfile`) to eliminate blended feedback upward drift.
- Share `FuelEconomyTracker.computeConservativeDteKpl(...)` and `FuelEconomyTracker.computeEstimatedRangeKm(...)` across `FuelEconomyTracker.update`, `ObdPollingEngine` offline fallback, and `HomeMenuScreen`:
  - `wRecent` scales from `0.20` (`>50%` tank) through `0.30` (`25%..50%`) to `0.45` (`<=25%`), and caps DTE economy at `min(blended, allTimeTotalAvgKpl)` when tank fill `> 50%` so short highway spurts cannot inflate full-tank range.
  - Deduct a `10%` tank capacity safety reserve (`coercedIn(2.0, 6.0)` L, i.e., `4.5 L` on a `45 L` Avanza tank) from remaining fuel before multiplying by `dteKpl` so Home and HUD show identical usable distance before reserve.

## 4. Persistent Multi-Aspect `Create Card`, Background Picker, `60%` Map Roads & Dual `Export`
- **DataStore Persistence (`AppPreferences.kt`):** Persist full `TripCardConfig` (`backgroundMode`, `customPhotoPath`, `aspectRatio`, `showSurroundingRoads`, all 9 visibility toggles, `selectedMetrics`) in `apex_obd_prefs`.
- **On-the-Fly Photo & Aspect Layouts (`TripCardRenderer.kt`, `HomeSheets.kt`):**
  - Rename `"Preview & Share"` -> `"Create Card"`, track async `shareCardPreparing` + async bitmap preview rendering in `ShareCardComposerDialog` with `CircularProgressIndicator`.
  - Support `ShareCardAspectRatio` (`SQUARE_1_1` `1080×1080`, `LANDSCAPE_4_3` `1440×1080`, `STORY_9_16` `1080×1920`) with tailored margins, Story safe insets, and 1–6 metric cell geometry.
- **Surrounding Area Roads (`TripCardRenderer.kt`, `OfflineMapManager.kt`):**
  - When `showSurroundingRoads` is enabled and `pts.size >= 2`, generate an off-screen `MapSnapshotter` bitmap fitted to padded route `LatLngBounds` using a pure-roads-on-black inline MapLibre style (`.pmtiles` local source if configured, else OpenFreeMap vector source backed by cached `mbgl-offline.db` / network), convert road luminance to alpha at `60%` (`alpha <= 153`) so cards with photos or transparency preserve their backdrop, align the glowing tracked polyline via `snapshot.pixelForLatLng`, and fall back seamlessly to standard vector projection on timeout/error.
- **Dual `Export` Flow (`TripExporter.kt`, `HomeSheets.kt`):** Rename `"Share Image (PNG)"` -> `"Export"` and present a clean chooser between `Share to Apps` (`ACTION_SEND`) and `Download PNG` (written directly to `Pictures/ApexOBD` via `MediaStore`).
