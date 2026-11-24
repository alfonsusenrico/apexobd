# Tasks: 1-Tap Quick Refuel HUD Widget, Draft Confirmation, and Auto-Detect Fuel Jump

- [x] 1. Add `isDraft`, `obdFuelUsedSnapshotL`, `estimatedAddedLiters`, `updateRefuel`, and `MIGRATION_11_12` (`version = 12`) in `ApexDatabase.kt`.
- [x] 2. Implement pure `QuickRefuelDetector.kt` and unit tests (`QuickRefuelDetectorTest.kt`).
- [x] 3. Wire `quickCaptureRefuelDraft`, `confirmOrEditRefuelCheckpoint`, fuel-jump detection, and `"quick_refuel"` catalog entry in `HudViewModel.kt`.
- [x] 4. Add `QuickRefuelWidget` in `TorqueEvoWidgets.kt`, render `"quick_refuel"` + auto-detect prompt banner in `HudCanvasScreen.kt`, and add Draft badge + Edit/Confirm UI in `HomeSheets.kt` and `HomeMenuScreen.kt`.
- [x] 5. Bump `app/build.gradle.kts` to `1.3.0` (`versionCode = 8`), run full Gradle tests, lint, debug APK build, and `openspec validate --all --strict`.
- [x] 6. Install `v1.3.0` APK onto connected phone and open PR into `main`.
