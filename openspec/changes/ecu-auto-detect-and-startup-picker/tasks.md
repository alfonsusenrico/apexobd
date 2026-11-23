# Tasks

## Phase 1: Core ECU Identity & Matcher (`obd-core`)
- [x] 1.1 Add `EcuIdentitySnapshot`, `VehicleIdentityCandidate`, and `VehicleIdentityMatcher` in `obd-core` and emit `ecuIdentity` from `ObdPollingEngine` on connect (including Mode `09 02` VIN read). (Evidence: unit tests in `VehicleIdentityMatcherTest.kt` & `:obd-core:test`)

## Phase 2: Room v11 Migration, Auto-Switch, Re-Bind & True App Exit (`app`)
- [x] 2.1 Add `vin: String? = null` to `VehicleProfileEntity` and `MIGRATION_10_11` (`version = 11`) in `ApexDatabase.kt`. (Evidence: `:app:compileDebugKotlin`)
- [x] 2.2 Wire ECU auto-detection, live capability re-binding on manual `selectVehicle`, startup vehicle picker gating, and true process exit in `HudViewModel.kt` and `MainActivity.kt`. (Evidence: `:app:testDebugUnitTest`)

## Phase 3: Startup Vehicle Picker UI & ECU Identity Badge
- [x] 3.1 Add `StartupVehiclePickerDialog` in `HomeSheets.kt`, wire it into `ApexAppRoot` in `HudCanvasScreen.kt`, and show bound ECU identity (`VIN` / `PID Signature` / native sensors) in `VehicleProfileSheet`. (Evidence: `:app:lintDebug`, `:app:assembleDebug`, and `openspec validate --all --strict`)
- **Expected Result:** `:obd-core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and `openspec validate --all --strict` pass with 0 errors.
