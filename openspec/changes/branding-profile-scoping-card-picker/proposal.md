# Proposal

Status: **Approved by owner on `2026-11-18`** (Wordmark **B** Apex Cut + Blue OBD Pill, App Icon **2** Stacked Monogram, `Sport Arrow` default map cursor, `Trip Efficiency` formatted as percentage `e.g. 68%`).

## Why

Extended real-car usage and visual design review surfaced six usability, data-scoping, customization, and brand identity gaps:

1. **Accidental Modal Dismissals Lose Unsaved Input (`HomeSheets.kt`):** All six Home modal dialogs (`VehicleProfileSheet`, `RefuelLogSheet`, `FaultCodesSheet`, `RunLogsAndMapsSheet`, `EcuPidsSheet`, `SettingsSheet`) use default `ModalBottomSheet` swipe-to-dismiss and outside-scrim dismissal. Dragging down while scrolling form inputs (such as entering a new Vehicle Profile) closes the dialog immediately and discards in-progress form fields.
2. **Global Trip Data & Missing 1-Tap Vehicle Switcher (`HomeMenuScreen.kt`, `HudViewModel.kt`, `ApexDatabase.kt`):** Switching between the owner's cars (`Avanza K3VE` 4AT vs `Avanza 1.5G` MT) requires opening the profile sheet, and `HudViewModel.tripSessions` observes `tripDao.observeSessions()` globally instead of filtering by `activeVehicle.id` (unlike `refuelLogs`, which already uses `flatMapLatest` over `vehicleId`).
3. **Share Route Card Lacks Metric Selection, Displays `0 / 48` on Legacy Run, and Uses Plain Header Text (`TripCardRenderer.kt`, `HomeSheets.kt`, `AppPreferences.kt`):** Users cannot choose which metrics (up to 6) appear on the Share Route card, the legacy Trip #1 run (`session.id = 2`) renders `0 / 48 km/h` because pre-v5 rows stored `avgSpeedKmh = 0.0` despite having `distanceKm = 11.2` and `38 min` duration, the label reads `AVG / MAX SPD` instead of `AVG / TOP SPEED`, and the top-left header renders `"APEXOBD · ROUTE LOG"` in plain system text instead of an iconic motorsport sticker mark.
4. **Static Map Circle Location Marker (`DualMapCanvas.kt`, `AppPreferences.kt`):** The GPS puck on MapLibre is currently a static circle dot rather than offering heading-rotated Car or Sport Arrow cursor icons.
5. **Missing Motorsport Brand Decal (`ApexBrandLogo.kt`, `HomeMenuScreen.kt`, `TripCardRenderer.kt`):** The app lacks a signature classic automotive performance decal for `APEXOBD` (white forward-leaning top-right slant inspired by classic aftermarket gauges/stickers like AEM).
6. **Placeholder Android Robot App Launcher Icon (`AndroidManifest.xml`, `res/`):** The APK still ships `android:icon="@android:drawable/sym_def_app_icon"` instead of a tailored dark OLED Adaptive Icon (`ic_launcher` / `ic_launcher_round`).

## What Changes

- **Swipe-Locked Modals with Explicit Close (`HomeSheets.kt`):** Lock all Home `ModalBottomSheet` dialogs against accidental downward swipe/drag and unintentional scrim taps (`confirmValueChange = { it != SheetValue.Hidden }` + disabled sheet swipe/backdrop close), add explicit top-right `✕ Close` controls + System Back confirmation when dirty (`VehicleProfileSheet`), and guard `OfflineMapDownloaderDialog` outside taps.
- **1-Tap Vehicle Switcher & Profile-Scoped Data (`HomeMenuScreen.kt`, `HudViewModel.kt`, `ApexDatabase.kt`):** Add a compact 1-tap vehicle selector bar in Home (`Avanza K3VE` / `Avanza 1.5G MT` + quick `+` / edit access), add `TripDao.observeSessionsForVehicle(vehicleId)` and switch `HudViewModel.tripSessions` + `supportedPids` offline cache via `flatMapLatest` over `activeVehicle.id` so saved runs, last run map preview, stats, and ECU capabilities strictly follow the active car.
- **Customizable Share Card Metrics (Max 6), Interactive Preview Composer & Legacy Speed Fix (`AppPreferences.kt`, `TripCardRenderer.kt`, `TripExporter.kt`, `HomeSheets.kt`, `HudViewModel.kt`):**
  - Before exporting a Share Route PNG, open an interactive **`ShareCardComposerDialog`** showing a live bitmap preview with checkboxes for every visual layer (`App Brand Icon`, `Vehicle Name`, `Vehicle Specs`, `Date`, `Time`, `Route Path`, `Start/End Markers`, `Metrics Backing Card`, `Metrics Grid`) and background mode selector (`Car Photo`, `Plain Dark`, or `Transparent Components Only` with alpha channel).
  - Persist user-selected Share Card metrics (`shareCardMetrics`, ordered, max `6`) defaulting to `DISTANCE`, `TRIP_TIME`, `AVG_ECONOMY`, `AVG_TOP_SPEED`, `PEAK_RPM`, `TRIP_EFFICIENCY` (formatted as `%`), with selectable extras including `PEAK_AVG_HP`, `PEAK_TORQUE`, `FUEL_USED`, `MOVING_KPL`, `COOLANT_PEAK`, `MAX_THROTTLE`, `MAX_G_FORCE`, `AVG_VOLTAGE`, and `IDLE_TIME`.
  - Add `TripSessionEntity.effectiveAvgSpeedKmh()` falling back to `distanceKm / (effectiveDurationSec() / 3600.0)` when legacy `avgSpeedKmh <= 0.1`, rename `"AVG / MAX SPD"` to `"AVG / TOP SPEED"`, remove `"· ROUTE LOG"`, and stamp the custom slanted `APEXOBD` decal onto the Share Card header.
- **In-Feature Contextual Settings & Fixed-Order Alignment (`HomeMenuScreen.kt`, `HomeSheets.kt`, `HudCanvasScreen.kt`):**
  - Pin car switcher pills in stable `id` order (`vehicles.sortedBy { it.id }`) so active selection never scrambles pill positions, and format Saved Run cards with non-wrapping tabular stat cells.
  - Expose Map Style, Camera Tilt, Map Cursor, and High-Contrast Roads directly inside `RunLogsAndMapsSheet` (`Map Options` chip), expose HUD Grid Scale, Gauge Smoothing, and Mirror Flip directly inside `HudCanvasScreen`'s Appearance dialog, and make the Home top-right connection status badge tappable.
- **Selectable Heading-Rotated Map Cursor Icons (`DualMapCanvas.kt`, `AppPreferences.kt`, `HomeSheets.kt`):** Add `MapCursorStyle` (`SPORT_ARROW`, `TOP_DOWN_CAR`, `CLASSIC_DOT`) in Settings and render high-DPI Canvas symbol icons on MapLibre synced with 60 Hz smoothed `dispBearing`.
- **Classic Slanted **`APEXOBD`** Vector Decal & Adaptive App Icon (`ApexBrandLogo.kt`, `res/mipmap-anydpi-v26/`, `res/drawable/`, `AndroidManifest.xml`):** Implement shared Compose & Android Canvas vector drawing for the chosen **`APEXOBD`** sticker wordmark (Home top bar + Share Card) and replace the stock Android robot launcher icon with the chosen Adaptive Launcher Icon (`minSdk 26+` compatible `mipmap-anydpi-v26` + crisp vector layers).

## Capabilities

### Modified Capabilities
- `hud-canvas`: Non-swipe-dismissing Home modal sheets with explicit close & dirty-form protection, 1-tap active vehicle switcher in Home, classic slanted `APEXOBD` header decal, selectable heading-rotated map cursor icons (`Sport Arrow` / `Top-Down Car` / `Classic Dot`), and Android Adaptive Launcher Icon.
- `logging-tracking`: Profile-scoped `TripDao.observeSessionsForVehicle(vehicleId)` flow in `HudViewModel`, legacy `effectiveAvgSpeedKmh()` fallback fixing `0 / 48` on historical sessions, customizable max-6 Strava Share Card metric picker UI + renderer, `"AVG / TOP SPEED"` metric label, and `APEXOBD` decal header without `"· ROUTE LOG"`.

## Impact

- `app`: `ApexDatabase.kt` (`TripDao.observeSessionsForVehicle`, `TripSessionEntity.effectiveAvgSpeedKmh`), `AppPreferences.kt` (`MapCursorStyle`, `ShareCardMetric`, `shareCardMetrics`), `HudViewModel.kt`, `TripCardRenderer.kt`, `TripExporter.kt`, `ApexBrandLogo.kt`, `DualMapCanvas.kt`, `HomeMenuScreen.kt`, `HomeSheets.kt`, `AndroidManifest.xml`, and `res/mipmap-anydpi-v26/` + `res/drawable/` launcher icons + unit tests.
- `obd-core`: Minor unit coverage if helper/metric calculations sit in `obd-core` (no protocol breaking changes).
