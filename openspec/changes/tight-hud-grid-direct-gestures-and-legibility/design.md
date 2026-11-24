# Design: Tight HUD Grid, Direct On-Component Gestures, Legibility, and 99.99 KPL Cap

## 1. Dense HUD Grid (`AppPreferences.kt`, `HudCanvasScreen.kt`)
- Extend `HudGridScale` with `FINE_16X8("Fine (16×8)", 16, 8)` and `ULTRA_24X12("Ultra (24×12)", 24, 12)`.
- Scale canvas padding and inter-cell gap by column count:
  - `cols >= 24`: `gap = 1.5.dp`, outer padding `4.dp` (`end = 6.dp`)
  - `cols >= 16`: `gap = 2.5.dp`, outer padding `4.dp` (`end = 6.dp`)
  - `cols >= 12`: `gap = 4.dp`, outer padding `6.dp` (`end = 10.dp`)
  - `cols < 12`: `gap = 7.dp`, outer padding `10.dp` (`end = 16.dp`)
- Render dashed grid guides whenever `editMode` is true (both tile-snap and free modes) using `0.75.dp` stroke for `cols >= 16`.

## 2. Direct Edge-Arrow Resizing, Hold-Middle Move, and Overlap Resolution
- Add `WidgetResizeEdge` (`LEFT`, `RIGHT`, `TOP`, `BOTTOM`), `GridBounds`, and pure `computeResizedGridBounds(...)` in `HudGridLayout.kt`.
- Add `HudViewModel.updateWidgetGridBounds(widget, col, row, spanCols, spanRows)` for atomic single-write updates when resizing from any edge, and `HudViewModel.bringWidgetToFront(widget)` to raise `sortOrder` above all siblings on the page.
- In `ProportionalHudPageCanvas`:
  - Render widgets ordered by `sortOrder` with explicit `.zIndex(...)` (`30f` dragging/resizing, `20f` selected, `sortOrder * 0.001f` otherwise).
  - Tapping any widget selects it and brings it to front so overlapping widgets remain clickable and editable; the Edit Mode bar also provides a `Layer ⇄` cycle button so even completely covered widgets can be surfaced.
  - Holding the middle of a widget (`detectDragGesturesAfterLongPress` with `HapticFeedbackType.LongPress`) unlocks drag-to-move without conflicting with tap-to-select or edge-arrow drags.
  - The selected widget renders 4 side arrow handles (`◄`, `►`, `▲`, `▼`) using immediate `detectDragGestures`, live-updating preview bounds and committing `updateWidgetGridBounds` on release.

## 3. Compact Label & Unit Legibility (`TorqueEvoWidgets.kt`)
- Raise label minimum font size to `9.5.sp` and unit minimum font size to `9.5.sp` with `0.86f` alpha contrast.
- In narrow/compact tiles (`maxWidth < 145.dp` or `valueText.length >= 5 && maxWidth < 170.dp`), move the unit into the top header row alongside the label (`Row(Arrangement.SpaceBetween)`), giving `XX.XX` digits full width in the value row.

## 4. Density-Scaled RPM Radial Labels (`RpmRadialWidgets.kt`)
- Replace raw-pixel clamps (`8f..15f` px) with `dp.toPx()` clamps (`10.dp.toPx()..18.dp.toPx()` for `"RPM"`, `9.5.dp.toPx()..17.dp.toPx()` for scale numerals) across `RpmTachGtWidget`, `RpmTachF1LedsWidget`, `RpmTachChronoWidget`, and `RpmTachHaloWidget`.

## 5. KPL Display Cap `99.99`
- Update all `99.9` KPL display clamps to `99.99` in `TelemetryCalculator.kt`, `VehicleAndUnits.kt`, `ApexDatabase.kt`, `HudViewModel.kt`, and `TripShareStatsCalculator.kt`.
- Underlying `totalDistanceKm`, `totalFuelLiters`, `roadDistanceKm`, `roadFuelLiters`, and `allTime*` accumulators remain un-clamped.
