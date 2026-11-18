# Tasks

> Status: **Approved & Verified on `2026-11-18`** (Wordmark **B**, App Icon **2**, `Sport Arrow` default map cursor, `Trip Efficiency` as `%`, fixed-order car switcher pills, contextual Map & HUD settings, and Interactive Share Route Card Composer verified live on `RMX1921`).

## Phase 1 — Preferences, Database Scoping & Pure Card Stats
- [x] 1. Add `TripDao.observeSessionsForVehicle(vehicleId)` and `TripSessionEntity.effectiveAvgSpeedKmh()` fallback in `ApexDatabase.kt`.
- [x] 2. Add `MapCursorStyle` (`SPORT_ARROW`, `TOP_DOWN_CAR`, `CLASSIC_DOT`), `ShareCardBackgroundMode` (`CAR_PHOTO`, `PLAIN_DARK`, `TRANSPARENT`), `TripCardConfig`, and `ShareCardMetric` (max 6, default `DISTANCE`, `TRIP_TIME`, `AVG_ECONOMY`, `AVG_TOP_SPEED`, `PEAK_RPM`, `TRIP_EFFICIENCY` as `%`) to `AppPreferences.kt` + `HudViewModel.kt`.
- [x] 3. Implement `TripShareStatsCalculator` with cached `summarize()` aggregation for all 15 selectable Share Card metrics and scope `HudViewModel.tripSessions` + offline `supportedPids` to `activeVehicle.id` with active-recording protection in `selectVehicle`.

## Phase 2 — Branding, Adaptive Icon, Map Cursors, Contextual Settings & Share Composer
- [x] 4. Create `ApexBrandLogo.kt` (Compose + Android `Canvas` Wordmark B decal) and apply it to `HomeMenuScreen.kt` top bar and `TripCardRenderer.kt` header (removing `"· ROUTE LOG"` and renaming `"AVG / TOP SPEED"`).
- [x] 5. Add Android Adaptive Launcher Icon (`res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`, `res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml` Icon 2 stacked monogram) and wire into `AndroidManifest.xml`.
- [x] 6. Update `DualMapCanvas.kt` and `MapDisplayControlsSection` to render selectable heading-rotated `Sport Arrow`, `Top-Down Car`, and `Classic Dot` cursors in both MapLibre (`SymbolLayer` + `CircleLayer`) and `OLED Track Only` mode.
- [x] 7. Add pinned-order 1-tap Car Profile switcher bar on `HomeMenuScreen.kt`, tabular Run cards + inline `Map Style & Cursor` inside `RunLogsAndMapsSheet`, contextual Grid/Smoothing/Mirror controls inside HUD Appearance dialog, tappable Home connection chip, interactive `ShareCardComposerDialog` with live preview + component checkboxes + `Transparent` mode, and swipe-locked Home modals (`ApexLockedSheet` + explicit `✕ Close` / unsaved-edit confirmation in `VehicleProfileSheet`).

## Phase 3 — Verification & In-Place Install
- [x] 8. Add unit tests (`TripShareStatsCalculatorTest`) and verify `:obd-core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and `openspec validate --all --strict`.
- [x] 9. Install APK in-place (`adb install -r`) on `RMX1921` (`3646fbc7`, zero `pm clear`), visually verifying Wordmark B header, fixed-order 1-tap car switcher across `Avanza K3VE` / `Avanza 2NR`, swipe-locked sheets, tabular `Saved Runs` (`18 / 48 km/h`), inline `Map Style & Cursor`, `ShareCardComposerDialog` pinned preview/buttons (`14.8 km/L`, `74%`, `68%`, `Transparent` checkerboard), and clean `logcat -b crash -d`.
