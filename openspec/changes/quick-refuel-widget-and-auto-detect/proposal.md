# Proposal: 1-Tap Quick Refuel HUD Widget, Draft Confirmation, and Auto-Detect Fuel Jump

## Summary
Add a 1-tap `Quick Refuel` HUD widget, automatic fuel-level-jump refuel detection (for vehicles with native fuel level sensors), and editable draft confirmation in the Refuel Checkpoint Log (`v1.3.0`, `versionCode = 8`).

## Motivation
- Drivers often lack time at the gas station to type pump liters, price, and RON before pulling away.
- Delaying refuel logging until after driving home skews the odometer checkpoint and OBD fuel-used calibration snapshot.
- Vehicles with native fuel level sensors (e.g. 2024 Avanza) can automatically estimate added liters (`after - before`), while still requiring receipt confirmation before altering MAF trim or full-to-full cycle KPL.

## Scope
1. Room `MIGRATION_11_12` (`version = 12`) adding `isDraft`, `obdFuelUsedSnapshotL`, and `estimatedAddedLiters` to `RefuelLogEntity`, plus `VehicleDao.updateRefuel`.
2. Pure `QuickRefuelDetector` logic for fuel-jump detection (`>= max(4.5 L, 11% tank)` with 10-minute cooldown) and sensor/OBD-based estimated liters.
3. `HudViewModel` quick-draft capture, draft confirmation/edit with frozen pump-time `obdFuelUsedSnapshotL`, and live ECU fuel jump detection banner state.
4. `QuickRefuelWidget` (`sensorKey = "quick_refuel"`) for the HUD Canvas + non-blocking HUD auto-detect prompt + Draft badge and Edit/Confirm flow in `RefuelLogSheet`.
5. Bump version to `1.3.0` (`versionCode = 8`).

## Approval
- Approved by owner in session on 2026-11-24.
