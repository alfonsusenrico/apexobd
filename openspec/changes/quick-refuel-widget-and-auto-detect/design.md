# Design: 1-Tap Quick Refuel HUD Widget, Draft Confirmation, and Auto-Detect Fuel Jump

## 1. Persistence (`ApexDatabase.kt`, Room v11 -> v12)
- Add columns to `RefuelLogEntity`:
  - `isDraft: Boolean = false`
  - `obdFuelUsedSnapshotL: Double? = null`
  - `estimatedAddedLiters: Double? = null`
- Add `MIGRATION_11_12` altering `refuel_logs` non-destructively.
- Add `@Update suspend fun updateRefuel(entry: RefuelLogEntity)` to `VehicleDao`.

## 2. Estimation & Fuel-Jump Detection (`QuickRefuelDetector.kt`)
- `detectFuelLevelJump(...)` fires when a vehicle with a native fuel sensor sees `afterLiters - beforeLiters >= max(4.5 L, tankCapacityLiters * 0.11)` outside a 10-minute cooldown, avoiding false triggers from slopes or fuel slosh.
- `estimateQuickRefuelLiters(...)` prioritizes native sensor delta (`after - before >= 1.5 L`), then `obdFuelUsedSinceFullLiters`, then `tankCapacity - currentFuelLiters`.

## 3. Draft Lifecycle (`HudViewModel.kt`)
- **Quick Capture:** Freezes `timestampMs`, live `odometerKm`, `obdFuelUsedSnapshotL`, and `estimatedAddedLiters` as `isDraft = true`, resets `fuelUsedSinceFullRefuelLiters = 0.0` for the new tank, and does NOT modify `mafTrimPct` or `fullRefuelCycleKpl`.
- **Confirm / Edit:** Recomputes `cycleDistanceKm`, `cycleFuelLiters`, and `cycleAvgKpl` against confirmed full-tank entries, marks `isDraft = false`, and calibrates `mafTrimPct` using the frozen `obdFuelUsedSnapshotL`.

## 4. UI (`TorqueEvoWidgets.kt`, `HudCanvasScreen.kt`, `HomeSheets.kt`, `HomeMenuScreen.kt`)
- `QuickRefuelWidget` (`sensorKey = "quick_refuel"`): 1-tap HUD button with instant `"SAVED"` feedback and pending draft count badge.
- HUD Auto-Detect Banner: non-blocking top banner when `detectedRefuelJump != null` offering `[Save Draft]` and `[Dismiss]`.
- `RefuelLogSheet`: highlights `DRAFT` entries and supports editing/confirming any entry inline in the form.
