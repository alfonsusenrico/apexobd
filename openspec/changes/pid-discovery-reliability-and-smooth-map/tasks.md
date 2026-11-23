# Tasks

## Phase 1: Reliable Multi-ECU PID Discovery (`obd-core` & `HudViewModel`)
- [x] 1.1 Add `extractAllPidPayloads` and `applyLivePollingTiming` in `Elm327Client.kt`, defer `ATST19` until after discovery in `ObdPollingEngine.kt`, and update `ObdPidRegistry.discoverSupportedPids` to union multi-ECU bitmasks and retry advertised blocks up to 3 times. (Evidence: `PidDiscoveryReliabilityTest` & `EngineAndPerformanceTest`, `:obd-core:test` `62/62` passed)
- [x] 1.2 Guard `HudViewModel.persistDiscoveredPidsToVehicle` against shrinking saved PID sets on partial connects. (Evidence: `:app:compileDebugKotlin`)

## Phase 2: 60 FPS Predictive Map Gliding (`LocationTracker`, `DualMapCanvas`, `TorqueEvoWidgets`)
- [x] 2.1 Add `fixTimeMs` to `GpsSnapshot` and set `minDistance = 0f` for high-frequency hardware GNSS in `LocationTracker.kt`.
- [x] 2.2 Implement `MapMotionGlider` in `DualMapCanvas.kt`, wire `speedKmh` and `fixTimeMs` from `MiniMapWidget` (`TorqueEvoWidgets.kt`), and add unit tests verifying continuous inter-fix velocity gliding and bounded extrapolation. (Evidence: `MapMotionGliderTest`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and `openspec validate --all --strict` `17/17` passed)
