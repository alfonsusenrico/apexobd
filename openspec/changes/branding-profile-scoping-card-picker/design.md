# Technical Design — Branding, Profile Scoping, Map Cursors, Modal Lock & Share Card Picker

## 1. Swipe-Locked Modals (`HomeSheets.kt`)

- **Root Cause:** Material 3 `ModalBottomSheet` (`composeBom = 2024.12.01`, M3 `1.3.1`) allows vertical swipe-down drags and backdrop scrim taps to invoke `onDismissRequest` / collapse to `SheetValue.Hidden`, colliding with nested-scroll gestures inside forms like `VehicleProfileSheet`.
- **Design:**
  - Create a shared helper composable `ApexLockedBottomSheet(...)` wrapping `ModalBottomSheet`:
    - `rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })`
    - `sheetGesturesEnabled = false` so nested `verticalScroll` never pulls the sheet down
    - `onDismissRequest = { if (allowBackdropDismiss) handleClose() }` with `ModalBottomSheetProperties(shouldDismissOnBackPress = !hasUnsavedChanges)`
  - When `VehicleProfileSheet` has unsaved edits (compared against initial fields) and the user taps `✕` / `Cancel` / system `BackHandler`, show a concise `AlertDialog` (`Discard unsaved vehicle changes?`) before closing.
  - All other Home sheets (`RefuelLogSheet`, `FaultCodesSheet`, `RunLogsAndMapsSheet`, `EcuPidsSheet`, `SettingsSheet`) use `ApexLockedBottomSheet` with a consistent explicit top-right `✕` icon button (`IconButton(Icons.Default.Close)`) + safe `BackHandler(enabled = true) { onDismiss() }`.
  - `OfflineMapDownloaderDialog` sets `dismissOnClickOutside = false` while active/downloading.

## 2. 1-Tap Vehicle Profile Switcher & Per-Vehicle Scoping (`ApexDatabase.kt`, `HudViewModel.kt`, `HomeMenuScreen.kt`)

- **DAO & Entity (`ApexDatabase.kt`):**
  - Add `@Query("SELECT * FROM trip_sessions WHERE isSaved = 1 AND vehicleId = :vehicleId ORDER BY startedAtMs DESC") fun observeSessionsForVehicle(vehicleId: Long): Flow<List<TripSessionEntity>>` to `TripDao`.
  - Add `fun effectiveAvgSpeedKmh(): Double` on `TripSessionEntity`:
    ```kotlin
    fun effectiveAvgSpeedKmh(): Double {
        if (avgSpeedKmh > 0.1) return avgSpeedKmh
        val sec = effectiveDurationSec()
        return if (distanceKm > 0.05 && sec > 0L) distanceKm / (sec / 3600.0) else 0.0
    }
    ```
    This fixes historical rows (such as Trip #1: `11.247 km / 2279 s -> 17.77 km/h -> 18 km/h`) without needing destructive DB writes.
- **ViewModel Scoping (`HudViewModel.kt`):**
  - Replace global `tripDao.observeSessions()` with `activeVehicle.map { it?.id ?: -1L }.distinctUntilChanged().flatMapLatest { vid -> if (vid > 0L) tripDao.observeSessionsForVehicle(vid) else flowOf(emptyList()) }`.
  - In `selectVehicle(vehicleId)`:
    - Reject profile switch while actively `RECORDING` or `PAUSED` with a clear status prompt (`Stop or Save active trip before switching vehicle`) so samples never mix across cars.
    - When idle, flush active vehicle stats, call `vehicleDao.setActiveVehicle(vehicleId)`, immediately update `_supportedPids.value` to the target vehicle's `savedSupportedPids()`, and clear transient per-ECU `diagnosticReport` / `vin` / `fuelMetrics` caches so stale data from the previous car never lingers on Home.
- **Home Switcher UI (`HomeMenuScreen.kt`):**
  - Place a compact horizontal chip strip directly above the Home summary card (or integrated at the top of the Vehicle header card): one tappable pill per vehicle profile (active pill highlighted in `#00A8FF`, inactive in dark surface), plus `+ Add` / `Edit` icon button. Single tap calls `viewModel.selectVehicle(v.id)` and immediately refreshes Home metrics, saved run count, last run map card, and refuel checkpoint.

## 3. Selectable Map Cursor Icons (`AppPreferences.kt`, `DualMapCanvas.kt`, `HomeSheets.kt`)

- **Preferences (`AppPreferences.kt`):**
  - Add `enum class MapCursorStyle(val label: String) { SPORT_ARROW("Sport Arrow"), TOP_DOWN_CAR("Top-Down Car"), CLASSIC_DOT("Classic Dot") }` defaulted to `SPORT_ARROW`, stored in `DataStore` under `map_cursor_style`.
- **MapLibre Rendering (`DualMapCanvas.kt`):**
  - Generate crisp `@Px` high-DPI offscreen `Bitmap`s once per density/style for `apex-cursor-arrow` (white-bordered `#00A8FF` chevron with soft outer halo) and `apex-cursor-car` (top-down chassis with dark cabin glass, `#00A8FF` headlights, `#EF4444` taillights).
  - Register the bitmaps into `style.addImage(...)` inside `ensureSourcesAndLayers(style)` whenever `style.getImage(...) == null`.
  - Add GeoJSON feature `"bearing"` property updated inside the 60 Hz frame interpolator lockstep with `"dispLat"`, `"dispLon"`, and `"dispBearing"`.
  - For `SPORT_ARROW` / `TOP_DOWN_CAR`: enable `SymbolLayer("apex-puck-symbol", "apex-puck-src")` with `iconImage`, `iconRotate(Expression.get("bearing"))`, `iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)`, `iconAllowOverlap(true)`, `iconIgnorePlacement(true)`, and hide `CircleLayer("apex-puck-layer")`. For `CLASSIC_DOT`: show `CircleLayer("apex-puck-layer")` and hide `SymbolLayer`.
  - Mirror the selected cursor style in the `OLED Track Only` Compose Canvas fallback (`SPORT_ARROW` chevron rotated by bearing, `TOP_DOWN_CAR` vector silhouette rotated by bearing, `CLASSIC_DOT` circle).
  - Add `Map Cursor` selector row (`Sport Arrow` / `Top-Down Car` / `Classic Dot`) in `SettingsSheet` right alongside `Map Style` and `Camera`.

## 4. Customizable Strava Share Card Metrics (Max 6) + Legacy Speed Fix (`AppPreferences.kt`, `TripCardRenderer.kt`, `TripExporter.kt`, `HomeSheets.kt`)

- **Metric Catalog (`ShareCardMetric` enum):**
  - Default ordered set (6 items):
    1. `DISTANCE` (`DISTANCE`)
    2. `TRIP_TIME` (`TRIP TIME`)
    3. `AVG_ECONOMY` (`AVG ECONOMY`)
    4. `AVG_TOP_SPEED` (`AVG / TOP SPEED`)
    5. `PEAK_RPM` (`PEAK RPM`)
    6. `TRIP_EFFICIENCY` (`TRIP EFFICIENCY`)
  - Optional metrics:
    7. `PEAK_AVG_HP` (`PEAK / AVG HP`)
    8. `PEAK_TORQUE` (`PEAK TORQUE`)
    9. `FUEL_USED` (`FUEL USED`)
    10. `MOVING_KPL` (`MOVING KPL`)
    11. `COOLANT_PEAK` (`COOLANT PEAK`)
    12. `MAX_THROTTLE` (`MAX THROTTLE`)
    13. `MAX_G_FORCE` (`MAX G-FORCE`)
    14. `AVG_VOLTAGE` (`AVG VOLTAGE`)
    15. `IDLE_TIME` (`IDLE TIME`)
- **Pure Metric Extractor (`TripShareStatsCalculator`):**
  - Extract summary values once from `(session: TripSessionEntity, samples: List<TelemetrySampleEntity>, unitPrefs, fuelType)` in pure Kotlin so unit tests can assert exact formatting, `effectiveAvgSpeedKmh()` fallback (`18 / 48 km/h`), efficiency score aggregation (mean of `efficiency_score`), `PEAK / AVG HP`, `PEAK TORQUE`, `MOVING KPL`, `IDLE TIME`, `MAX G-FORCE`, `COOLANT PEAK`, `MAX THROTTLE`, `AVG VOLTAGE`, and missing-sensor `"—"` fallback cleanly.
- **Preferences & Picker UI (`AppPreferences.kt`, `HomeSheets.kt`, `RunLogsAndMapsSheet`):**
  - Store ordered comma-separated `share_card_metrics` in `DataStore` (validated max `6` items, non-empty fallback to default 6).
  - Inside `RunLogsAndMapsSheet` (plus an inline expandable `"Share Card Metrics (x/6)"` bar right above the saved runs or triggered alongside `Share Card`, and in `SettingsSheet`), show compact toggle chips where tapping adds/removes metrics up to 6 (with clear `Reset Default 6` button).
- **Header & Typography Fixes (`TripCardRenderer.kt`):**
  - Replace `"APEXOBD · ROUTE LOG"` with shared vector call `ApexBrandLogo.drawOnAndroidCanvas(canvas, ...)` (pure white slanted motorsport decal, no `"ROUTE LOG"` suffix).
  - Use `ShareCardMetric.AVG_TOP_SPEED` labeled `"AVG / TOP SPEED"`.

## 5. **`APEXOBD`** Motorsport Wordmark & Android Adaptive Launcher Icon (`ApexBrandLogo.kt`, `res/`)

- **Shared Decal Vector (`ApexBrandLogo.kt`):**
  - Implement exact owner-selected option (`Wordmark A`, `B`, or `C`) in both Jetpack Compose `Canvas` (`ApexBrandWordmark`) for `HomeMenuScreen` header and Android `android.graphics.Canvas` (`drawWordmarkOnCanvas`) for `TripCardRenderer` so Home header and exported PNG images share identical branding.
- **Android Adaptive App Icon (`app/src/main/res/`):**
  - Create `mipmap-anydpi-v26/ic_launcher.xml` and `mipmap-anydpi-v26/ic_launcher_round.xml` pointing to:
    - `@drawable/ic_launcher_background` (pure `#000000` OLED surface)
    - `@drawable/ic_launcher_foreground` (vector artwork matching the owner's selected Icon `1`, `2`, or `3`, centered cleanly inside Android adaptive icon 66% safe zone)
  - Update `AndroidManifest.xml`: replace `android:icon="@android:drawable/sym_def_app_icon"` with `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`.
