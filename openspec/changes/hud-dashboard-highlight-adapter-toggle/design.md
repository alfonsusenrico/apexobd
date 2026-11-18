# Technical Design — HUD Dashboard Hero & Multi-Transport Adapter Switcher

## 1. Home Hierarchy Shift (`HomeMenuScreen.kt`)

- **Top Header Bar:**
  - Left: `ApexBrandLogo.ComposeWordmark(height = 17.dp)` + `"v1.0"`.
  - Right:
    - Tappable Connection Toggle Pill displaying active mechanism (`Wi-Fi · 192.168.0.10`, `BT · OBDII`, `BLE · Adapter`, `USB · 38400`) opening `AdapterConnectionSheet`.
    - Clean `IconButton` (`Icons.Default.Settings`, `34.dp`) opening `SettingsSheet`.
- **Centerpiece (`Modifier.weight(1f)` in Portrait, primary top-left `weight(1f)` block in Landscape):**
  - **`HudDashboardHeroCard`:** Tappable (`onOpenHudCanvas()`), styled cleanly in plain dark obsidian (`Color(0xFF0A0D13)`) with a restrained `1 dp` Electric Blue border (`0x7300A8FF`), crisp `"HUD Dashboard"` title, and compact `"Open HUD ->"` pill CTA with zero redundant kickers, telemetry setup metadata, or helper captions.
- **Compact `Last Saved Run` Card (`height = 104.dp` portrait, compact strip in landscape):**
  - Left column: `"Last Saved Run · 17 Nov"` + key trip metrics (`2.3 km · 14.8 km/L · 5 min`) + hint `"Tap for Saved Runs & Map Settings"`.
  - Right thumbnail (`width = 112.dp`): `DualMapCanvas` route preview (clipped rounded box). Tapping the card opens `RunLogsAndMapsSheet`.
- **Bottom Card Removal:** Remove bottom `settingsCard()` from both Portrait and Landscape compositions.

## 2. Multi-Mechanism OBD2 Transports (`obd-core` + `app/platform/`)

- **`ObdConnectionType` (`obd-core` `ObdTransport.kt`):**
  - `WIFI_TCP`, `BLUETOOTH_CLASSIC`, `BLUETOOTH_LE`, `USB_SERIAL`. (`SIMULATOR` removed from selectable UI and `ObdConnectionType` enum; `SimulatedObdTransport` remains solely as a JVM unit-test stub inside `obd-core`.)
- **Pure Frame Accumulation Helper (`ElmResponseBuffer` in `obd-core`):**
  - Thread-safe / clean byte-to-prompt accumulator shared cleanly across `BluetoothClassicObdTransport`, `BluetoothLeObdTransport`, and `UsbSerialObdTransport` (no duplicate prompt-scanning loops!) and unit tested directly on JVM.
- **`BluetoothClassicObdTransport` (`app/platform/`):**
  - Connects via `BluetoothAdapter.getRemoteDevice(address)` and standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB` (with fallback `createRfcommSocket(1)` reflection if service discovery fails on clone adapters).
  - Helper `listBondedAdapters(context)` filters/prioritizes paired Bluetooth Classic devices (checking `BLUETOOTH_CONNECT` safely on API 31+).
- **`BluetoothLeObdTransport` (`app/platform/`):**
  - Connects via `BluetoothDevice.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)`.
  - Discovers known ELM327 GATT UART profiles (`FFF0/FFF1/FFF2`, `FFE0/FFE1`, `18F0/2AF0/2AF1`, ISSC `49535343`, Nordic `6E400001`, or generic writable + notifiable custom service), enables CCCD notifications (`0x2902`), chunks outgoing commands safely by `maxWriteBytes`, and fulfills `CompletableDeferred<String>` upon `'>'`.
  - Includes bounded 4-second BLE scan helper returning nearby BLE device summaries.
- **`UsbSerialObdTransport` (`app/platform/`):**
  - Enumerates `UsbManager.deviceList`, detects FTDI (`0x0403`), Silicon Labs CP210x (`0x10C4`), QinHeng CH340 (`0x1A86`), or standard USB CDC-ACM interfaces, configures baud rate (`38400` / `115200` / `500000` selectable or auto-38400 default), claims interface, and exchanges ELM327 ASCII commands over bulk endpoints with deadline enforcement.
- **Preferences & Quick Adapter Sheet (`AppPreferences.kt`, `HomeSheets.kt`, `HudViewModel.kt`):**
  - Persist `connectionType`, `wifiHost`, `wifiPort`, `btAddress`, `btName`, `bleAddress`, `bleName`, `usbDeviceId`, `usbBaudRate`. Legacy `USE_SIMULATOR` seamlessly maps cleanly if `CONNECTION_TYPE` was not yet stored.
  - Shared `HudViewModel.buildTransport(settings: UserSettings)` factory builds the right `ObdTransport` cleanly across `reconnectTransport` and `runDeepEcuDump`.
