# Tasks

## Phase 1 — OpenSpec, Multi-Transport Core & Android Transports
- [x] 1. Add `ObdConnectionType` and shared `ElmResponseBuffer` / framing utilities to `obd-core/src/main/kotlin/com/apexobd/core/transport/ObdTransport.kt` + unit tests.
- [x] 2. Declare optional Bluetooth/BLE/USB permissions & features in `AndroidManifest.xml` and add runtime `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` requests cleanly on Android 12+ in `MainActivity.kt`.
- [x] 3. Implement `BluetoothClassicObdTransport.kt`, `BluetoothLeObdTransport.kt`, and `UsbSerialObdTransport.kt` under `app/src/main/kotlin/com/apexobd/app/platform/` + `AppPreferences.kt` connection fields with seamless migration from legacy `USE_SIMULATOR`.

## Phase 2 — ViewModel Factory, Adapter Connection Sheet & Home HUD Dashboard Highlight
- [x] 4. Wire shared `buildTransport(settings)` in `HudViewModel.kt` across `reconnectTransport`, `forceConnectAndDiscoverForExplorer`, and `runDeepEcuDump`, plus paired BT / BLE scan / USB enumeration helpers and quick connection switches.
- [x] 5. Build `AdapterConnectionSheet` in `HomeSheets.kt` (1-tap mechanism toggle for `Wi-Fi`, `BT Classic`, `BLE`, `USB OTG`, adapter pickers, baud & endpoint config), rename `HUD Canvas` labels -> `HUD Dashboard`, and remove redundant connection duplication where streamlined.
- [x] 6. Redesign `HomeMenuScreen.kt` to highlight the `HUD Dashboard` centerpiece card (`Modifier.weight(1f)`, plain dark obsidian surface, restrained `1 dp` accent border, crisp title + `Open HUD` CTA pill with redundant captions removed across cards and sheets), compact `Last Saved Run` into a sleek strip, remove the bottom `Settings` card, and place the Connection pill toggle + standalone `Settings` gear icon in the top-right header.

## Phase 3 — Verification & Live Install
- [x] 7. Add unit tests covering `ObdConnectionType`, `ElmResponseBuffer`, USB baud/endpoint selection, GATT UUID matching, and `UserSettings` adapter header labels; run `:obd-core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and `openspec validate --all --strict`.
- [x] 8. Install APK in-place (`adb install -r`) on `RMX1921` (`3646fbc7`, zero `pm clear`), capture and visually inspect updated Home HUD Dashboard centerpiece, compact Last Saved Run strip, top-right Connection toggle + Gear icon, `AdapterConnectionSheet` mode toggles (`Wi-Fi`, `BT`, `BLE`, `USB`), `SettingsSheet`, and clean `logcat -b crash -d`.
