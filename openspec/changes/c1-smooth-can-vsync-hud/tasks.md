# Tasks: C1 SmoothDamp Physics, Fast CAN Multi-PID Isolation & VSync Deferred-Read HUD

## Phase 1: Specification & Core OBD / Smoother Engine (`obd-core`)
- [x] Validate OpenSpec change `c1-smooth-can-vsync-hud` (`openspec validate c1-smooth-can-vsync-hud --strict` passed cleanly).
- [x] Add `"ATCAF1"` initialization in [`Elm327Client.kt`](obd-core/src/main/kotlin/com/apexobd/core/elm/Elm327Client.kt).
- [x] Refactor `TelemetryDisplaySmoother.AdaptiveSession` in [`TelemetryCalculator.kt`](obd-core/src/main/kotlin/com/apexobd/core/protocol/TelemetryCalculator.kt) to C1 `SmoothDamp` continuous velocity integration with calmer `kpl_instant` damping (`Option 2A`) and opt-in `liveMicroMotion = false` default (`Option 1A`).
- [x] Upgrade [`ObdPollingEngine.kt`](obd-core/src/main/kotlin/com/apexobd/core/scheduler/ObdPollingEngine.kt) to isolate `highTargets` CAN single-frame multi-PID chunks (`'1'` suffix), add retryable streak fallback, and enforce cooldowns on slow `>100 ms`/secondary PIDs (`voltage`, `lambda`, trims, thermal).
- [x] Extend [`TelemetryCalculatorTest.kt`](obd-core/src/test/kotlin/com/apexobd/core/protocol/TelemetryCalculatorTest.kt) and [`EngineAndPerformanceTest.kt`](obd-core/src/test/kotlin/com/apexobd/core/scheduler/EngineAndPerformanceTest.kt) covering C1 handoff velocity, `kpl_instant` damping, `ATCAF1`, trailing `'1'` multi-PID recovery, and slow `0142` isolation (`45/45` `:obd-core:test` passed).

**Expected Result:** `gradle :obd-core:test` passes all unit tests cleanly (`45/45`).

## Phase 2: Zero-Allocation VSync Compose HUD (`app`)
- [x] Replace `delay(16L)` loop and root `displayValues` / `historyBuffers` invalidations with `@Stable HudLiveValueStore`, cached `catalogItemFor` lookup, `hasVisibleTimer0100`, and Compose `withFrameNanos` frame stepping across [`HudViewModel.kt`](app/src/main/kotlin/com/apexobd/app/ui/HudViewModel.kt) and [`HudCanvasScreen.kt`](app/src/main/kotlin/com/apexobd/app/ui/HudCanvasScreen.kt).
- [x] Push live `valueProvider: () -> Double?` reads into `Canvas` Draw phase and `LiveDigitsRow` leaf composables across [`TorqueEvoWidgets.kt`](app/src/main/kotlin/com/apexobd/app/ui/components/TorqueEvoWidgets.kt), replacing `SegmentedBarGaugeWidget` nested boxes with a single `Canvas`, and add `HudLiveValueStore` test coverage in [`HudWidgetStyleTest.kt`](app/src/test/kotlin/com/apexobd/app/HudWidgetStyleTest.kt) (`24/24` `:app:testDebugUnitTest` passed).

**Expected Result:** `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` succeeds with `0` failures and `0` lint errors.

## Phase 3: Device Deployment, Verification & Documentation
- [x] Install debug APK (`adb install -r app/build/outputs/apk/debug/app-debug.apk`) onto connected `RMX1921` (`lastUpdateTime=2026-11-19 18:26:01`) and verify clean startup process `pid 9370`, `gfxinfo`, and `0` crashes in `logcat`.
- [x] Record test/build/device evidence in `verification.md`, commit cleanly on `fix/hud-labels-bars-smoothing`, and update untracked `PROJECT_STATE.md`.
- [ ] Road-test live Wi-Fi OBD-II telemetry with owner acceptance, then archive `c1-smooth-can-vsync-hud`, push, and open the PR.

**Expected Result:** Clean commit and updated `RMX1921` ready for on-road CAN validation before archive and PR.
