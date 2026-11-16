# Tasks: Torque Evo Android OBD-II App

## Phase 1: Project Scaffold & `:obd-core` Protocol Engine
- [x] 1.1 Initialize Gradle multi-module Android + JVM project (`settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, `:obd-core`, `:app`) — **Evidence:** Gradle build and `:app:assembleDebug` succeed.
- [x] 1.2 Implement `ObdTransport`, `WifiTcpTransport` (with Wi-Fi `Network` binding support), `SimulatedObdTransport`, and `Elm327Client` AT state machine — **Evidence:** `:obd-core:test` (`EngineAndPerformanceTest`) passes.
- [x] 1.3 Implement SAE J1979 Mode `01` bitmask discovery (`0100`–`01A0`), Mode `01` PID registry/decoders, Priority Polling Scheduler, HP/Torque & 0–100 km/h calculators — **Evidence:** `:obd-core:test` (`ObdPidRegistryTest`, `EngineAndPerformanceTest`) passes.
- [x] 1.4 Implement Mode `02` Freeze Frame, Mode `03`/`07`/`0A` DTC decoder + SAE dictionary, Mode `04` Clear DTCs, Mode `01` Readiness Monitors, Mode `09` VIN — **Evidence:** `:obd-core:test` (`DtcDecoderTest`) passes.
- [x] 1.5 Implement safe recursive-descent `FormulaEvaluator` and Torque CSV importer for UDS Mode `22` custom PIDs — **Evidence:** `:obd-core:test` (`FormulaEvaluatorTest`) passes.
- **Expected Result:** `:obd-core` compiles and all 13 pure-JVM unit tests pass on JDK 17.

## Phase 2: Persistence, Location & Foreground Logging Service
- [x] 2.1 Implement Room database (`HudPageEntity`, `HudWidgetEntity`, `TripSessionEntity`, `TelemetrySampleEntity`, `CustomPidEntity`) and DataStore preferences (`wifiHost`, `wifiPort`, `useSimulator`, `hudMirror`, `imperialUnits`, `keepScreenOn`) — **Evidence:** `:app:kspDebugKotlin` and `:app:compileDebugKotlin` succeed.
- [x] 2.2 Implement `LocationTracker`, `LoggingForegroundService`, and pure-Kotlin `TripExporter` (CSV/JSON) — **Evidence:** `:app:testDebugUnitTest` (`TripExporterTest`) passes.
- **Expected Result:** Persistence, GPS tracking, and export pipeline implemented and tested.

## Phase 3: Torque Evo Compose UI & Dynamic N-Page Canvas
- [x] 3.1 Implement `TorqueEvoTheme` (`#000000` OLED + `#00A8FF` electric blue, `tabular-nums`) and `HudCanvasScreen` (`HorizontalPager` across `N` dynamic pages, zero persistent nav chrome, `HUD Mirror` horizontal flip, subtle `⚙ / +` parked trigger) — **Evidence:** Compiled in `:app:assembleDebug`.
- [x] 3.2 Implement borderless Torque Evo `Canvas` components (`AnalogDialWidget`, `SemiDigitalArcWidget`, `DigitalReadoutWidget`, `BarGaugeWidget`, `SparklineChartWidget`, `MiniMapWidget` with Google Maps Compose + keyless vector fallback, `DtcCheckerWidget`, `PerformanceTimerWidget`, `DataLoggerWidget`) — **Evidence:** Compiled in `:app:assembleDebug`.
- [x] 3.3 Implement Edit Mode (reorder ◀/▶, display-type cycling ◐, size cycling ⤢, delete ×), Component Picker sheet (`Sensor → Display Type → Size`), Page Manager (`+ Add New Blank Page` / delete), and Parked Setup sheet (Wi-Fi config, Mode `09` VIN, Mode `04` Clear DTCs, Trip CSV/JSON export, UDS Mode `22` custom PIDs) — **Evidence:** Compiled in `:app:assembleDebug`.
- **Expected Result:** Debug APK `app/build/outputs/apk/debug/app-debug.apk` built cleanly (18 MB) and all 14 unit tests pass.

## Phase 4: Verification & Delivery
- [x] 4.1 Record verification evidence in `openspec/changes/torque-evo-obd-app/verification.md` and update `PROJECT_STATE.md`.
- [ ] 4.2 Owner tests APK on Android device/Wi-Fi ELM327 adapter and accepts result before archiving OpenSpec change and merging to `main`.
- **Expected Result:** Owner receives verified APK and build/install instructions.
