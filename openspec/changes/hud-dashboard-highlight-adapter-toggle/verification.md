# Verification Report — `hud-dashboard-highlight-adapter-toggle`

Date: `2026-11-18`
Branch: `feat/torque-evo-obd-app`

## 1. Automated JVM & Build Verification

- `:obd-core:test`: **37 / 37 passed** (`0` failures, `0` skipped)
  - `FormulaEvaluatorTest`: 3 passed
  - `DtcDecoderTest`: 4 passed
  - `ObdPidRegistryTest`: 7 passed
  - `TelemetryCalculatorTest`: 12 passed
  - `EngineAndPerformanceTest`: 7 passed
  - `ElmResponseBufferTest`: 4 passed (`appendByte` prompt framing & reset, `appendBytes` NUL skipping & partial reads, `formatCommandBytes`, `ObdConnectionType` entries)
- `:app:testDebugUnitTest`: **16 / 16 passed** (`0` failures, `0` skipped)
  - `HudGridLayoutTest`: 3 passed
  - `TransportCodecTest`: 4 passed (`BluetoothClassicObdTransport.isLikelyObdName`, `BleElmGattCatalog.matchesPrefix`, `UsbSerialCodec` chip family + baud payloads, `UserSettings.adapterChipLabel`)
  - `TripExporterTest`: 7 passed
  - `TripShareStatsCalculatorTest`: 2 passed
- `:app:lintDebug`: passed cleanly (`BUILD SUCCESSFUL`, 0 errors)
- `:app:assembleDebug`: succeeded (`app/build/outputs/apk/debug/app-debug.apk`, `60M`)
- `openspec validate --all --strict`: **7 / 7 passed** (`0` failed)

## 2. Live Device Verification (`RMX1921` / `3646fbc7`)

- Installed in-place via `adb -s 3646fbc7 install -r app/build/outputs/apk/debug/app-debug.apk` (owner vehicle profiles `Avanza K3VE`, `Avanza 2NR`, car photo, and 5 trip runs preserved intact).
- Verified visually via device screenshots (`screencap`) and clean `logcat -b crash -d`:
  - Top header renders `APEXOBD` decal on the left, tappable `Wi-Fi · 192.168.0.10` adapter pill toggle and standalone `Settings` gear icon on the top-right.
  - `HUD Dashboard` centerpiece (`Modifier.weight(1f)`) dominates Home cleanly with a plain dark obsidian card (`#0A0D13`), restrained `1 dp` Electric Blue border, crisp `HUD Dashboard` title, and compact `Open HUD ->` pill button with zero kickers, widget-count badges, or helper captions.
  - All Home cards/tiles (`Car Profile`, `Last Saved Run`, `Refuel Log`, `Fault Codes`, `Run Logs & Maps`, `ECU PIDs`) and modal sheets (`HomeSheets.kt`) audited and stripped of redundant captions.
  - Bottom `Settings` card removed cleanly, with `SettingsSheet` accessible from the top-right gear icon and `AdapterConnectionSheet` accessible from the top-right connection pill (`Wi-Fi`, `Bluetooth Classic`, `BLE`, `USB OTG` toggles only, with `Demo Simulator` menu removed and active mode set to `Wi-Fi · 192.168.0.10`).
- **Scope note:** Live physical hardware handshake was not exercised for Bluetooth Classic SPP, Bluetooth LE GATT, or USB Serial OTG because only the Wi-Fi OBD-II adapter is available locally. Those transports are verified by clean Android compilation, zero lint errors, UI mechanism switching, and JVM unit tests on framing/GATT/serial codecs.
