# Design

## Architecture Decisions

### 1. Bar & Digital Display Types (Zero Breaking DB Migration for `WidgetDisplayType`)
- Keep `WidgetDisplayType.DIGITAL` and `WidgetDisplayType.BAR` enum names untouched (`BAR` = previous smooth continuous `Line Bar`) so existing saved HUD widgets continue deserializing cleanly without destructive migrations.
- Add `WidgetDisplayType.SEGMENTED_BAR` (`Boxed Bar`), rendering 18 discrete rounded LED blocks colored by normal/warn/redline threshold zones plus `min`, `mid`, and `max` scale labels.
- Remove the `2.dp` bottom bar from `DigitalReadoutWidget` so `Digital` acts purely as an oversized tabular numeric tile.

### 2. Full-Canvas Theme & Layer Controls (`CompositionLocal` + `AppPreferences`)
- Define `HudThemePreset` (7 templates), `HudCanvasBackdrop` (`MATCH_THEME`, `GLASSY_DIMMED`, `PURE_BLACK`), and `HudCardStyle` (`THEMED`, `CLEAR_GLASS`) in `TorqueEvoTheme.kt` and persist them in `AppPreferences` (`showWidgetBorders`, `hudTheme`, `hudCanvasBackdrop`, `hudCardStyle`).
- Expose resolved tokens via `LocalHudVisualStyle` (`canvasBrush`, `cardFill`, `cardSheen`, `cardBorder`, `accent`, `textPrimary`, `textSecondary`, `dialTrack`, `showBorders`) consumed by `HudCanvasScreen`, `HudWidgetSlotWrapper`, `TorqueEvoWidgets`, and `HudCanvasScreen` pickers/dialogs.
- `DualMapCanvas` retains its `#070E17` vector canvas behind overlays while adopting theme accents for user marker/trail and border visibility for outer card stroke.

### 3. KPL Nomenclature & Circular Shutter-Style Toggle
- Shorten all KPL short names in `DerivedSensorCatalog`, `HudViewModel`, and `FuelControlWidget` strictly to `KPL · AVG`, `KPL · TRIP`, and `KPL · INST`.
- Replace the boxed `Text("Pause Idle")` button in `FuelControlWidget` with a `38.dp` `CircleShape` button bottom-right (`2.dp` ring border, `0xD90A0D14` fill): theme/white ring + solid accent telemetry dot while active, switching to amber ring (`#F59E0B`) + double pause bars (`||`) when paused.

### 4. Centralized Run-Record-Triggered Trip Telemetry & Save/Discard Modal
- Track live trip run metrics inside `HudViewModel` exclusively while `_isRecording.value == true` (starting cleanly from `0.0` / `00:00` on `startRecording()`).
- While idle on launch or OBD connect (before any run or after Discard), publish idle/unset values (`trip_range`, `trip_time_sec`, `trip_avg_speed`, run `fuel_used_l`, run `kpl_trip_avg` removed/null in `_liveValues` except idle `00:00` UI fallback for `trip_time`). Note `kpl_road_avg` (`KPL · AVG`) and `kpl_instant` (`KPL · INST`) continue updating normally whenever connected, per requirement.
- During recording:
  - Accumulate `distanceKm` primarily from validated GPS delta (matching the Map polyline path) with smooth speed integration fallback (`speed * dt`) when inside tunnels or during simulator testing without GPS movement.
  - Accumulate `fuelUsedLiters` strictly from fuel delta since `startRecording()`, compute `tripKpl = distanceKm / fuelUsedLiters` and `avgSpeedKmh = distanceKm / elapsedHours`, and track `elapsedSec = (nowMs - startMs) / 1000`.
- Room Schema v4 -> v5 (`MIGRATION_4_5`): add `avgSpeedKmh REAL NOT NULL DEFAULT 0.0` and `isSaved INTEGER NOT NULL DEFAULT 1` to `trip_sessions` (`1` preserves any pre-existing saved sessions from v4; active in-flight sessions insert with `isSaved = 0` so crash-safe sample batching streams straight to disk without appearing in `Saved Runs` until confirmed).
- On `stopRecording()`: stop foreground logging service cleanly, finalize session metrics in `pendingSaveTrip`, keep HUD trip widgets displaying the finalized trip summary if saved (or clear to standby if discarded), and emit `pendingSaveTrip` StateFlow to open the non-accidental `Save Recorded Trip Log?` modal showing `Trip Range`, `Trip Time`, `KPL · TRIP`, `Trip Fuel Used`, `Trip Avg Speed`, `Max Speed`, `Peak RPM`, and sample count with `Discard Trip` and `Save Trip Log` actions.
