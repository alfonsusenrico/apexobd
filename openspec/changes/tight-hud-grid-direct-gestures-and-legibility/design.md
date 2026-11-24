# Design: Tight HUD Grid, Direct On-Component Gestures, Legibility, and 99.99 KPL Cap

## 1. Torque-Style Micro-Grid & Proportional Rescaling (`AppPreferences.kt`, `HudGridLayout.kt`, `HudCanvasScreen.kt`)
- Extend `HudGridScale` with `FINE_16X8 (16×8)`, `ULTRA_24X12 (24×12)`, `MICRO_64X32 (64×32)`, and `ULTRA_80X40 (80×40)`, each carrying readable `minWidgetCols`/`minWidgetRows` and `defaultWidgetCols`/`defaultWidgetRows` (e.g. `8×5` min and `16×8` default on `64×32`; `10×6` min and `20×10` default on `80×40`).
- When `HudViewModel.setHudGridScale` switches scales, proportionally rescale every widget's `gridCol`, `gridRow`, `spanCols`, and `spanRows` via `rescaleWidgetBoundsForGridChange(...)` so existing components keep their exact physical size and position on screen instead of shrinking.
- For `cols >= 32`, use `gap = 0.dp`, `outerPad = 2.dp`, and draw crisp graph-paper grid lines (minor micro-grid lines + brighter major lines every `cols / 8` cols and `rows / 4` rows) whenever Edit Mode is active.

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
