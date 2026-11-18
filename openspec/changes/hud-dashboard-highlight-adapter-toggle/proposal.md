# Proposal

Status: **Approved by owner instruction on `2026-11-18`** (highlight HUD Dashboard on Home instead of an oversized Last Saved Run map, rename HUD Canvas to HUD Dashboard, move Settings to top-right icon beside a multi-mechanism OBD2 connection selector/toggle supporting Wi-Fi, Bluetooth Classic SPP, Bluetooth LE GATT, and USB Serial OTG without a UI Demo Simulator option).

## Why

1. **Last Saved Run Map Dominates Home Instead of HUD Dashboard (`HomeMenuScreen.kt`):** On portrait screens, the `Last Saved Run` map card expands via `Modifier.weight(1f)` to occupy roughly half the display while the primary driving feature (`Open HUD Canvas`) is reduced to a small flat banner above it. Users should see **HUD Dashboard** (renamed from `HUD Canvas`) as the visual centerpiece, with a live miniature preview of their configured Page 1 gauge layout, while `Last Saved Run` stays glanceable and compact.
2. **Duplicate / Bulky Bottom Settings Card (`HomeMenuScreen.kt`):** A large `Adapter, Units, Map Mode & Polling` card sits anchored at the bottom of Home instead of using a compact top-right settings icon beside the connection control.
3. **Single Wi-Fi / Simulator Connection Control (`ObdTransport.kt`, `AppPreferences.kt`, `HudViewModel.kt`, `HomeMenuScreen.kt`, `HomeSheets.kt`):** The connection settings only offer Wi-Fi TCP and Demo Simulator without Bluetooth Classic SPP, Bluetooth Low Energy (BLE GATT), or USB Serial OTG adapter mechanisms or an adapter mechanism toggle directly accessible from the top-right connection bar.

## What Changes

- **HUD Dashboard Centerpiece & Rename (`HomeMenuScreen.kt`, `HudCanvasScreen.kt`, `HomeSheets.kt`):**
  - Rename user-facing `HUD Canvas` labels to `HUD Dashboard`.
  - Promote the **HUD Dashboard** launch card on Home to the primary focal centerpiece (`Modifier.weight(1f)`), using a clean plain dark obsidian surface, restrained accent border, crisp `"HUD Dashboard"` title, and compact `"Open HUD ->"` CTA without background graphics, theme metadata, or redundant captions.
  - Compact `Last Saved Run` into a sleek summary + mini route map strip (`98 dp` portrait / `92 dp` landscape) that opens `Run Logs & Maps` on tap.
- **Top-Right Connection Mechanism Toggle + Settings Gear Icon (`HomeMenuScreen.kt`, `HomeSheets.kt`):**
  - Remove the bottom `Settings` card from Home completely.
  - Place a compact **Connection Mechanism & Status Pill** (`Wi-Fi`, `BT`, `BLE`, `USB`, `SIM` + endpoint/adapter name) followed immediately by a **`Settings` gear `IconButton`** on the top-right of the Home header.
  - Tapping the Connection pill opens a dedicated `AdapterConnectionSheet` right from the header with 1-tap switching across **Wi-Fi (TCP/IP)**, **Bluetooth Classic (SPP)**, **Bluetooth LE (BLE GATT)**, and **USB Serial (OTG)**, paired/scanned device pickers, USB permission status, and cellular keepalive toggle (with no Demo Simulator menu in the app).
- **Multi-Transport OBD-II Implementations (`app/platform/` + `obd-core`):**
  - Add `ObdConnectionType` enum (`WIFI_TCP`, `BLUETOOTH_CLASSIC`, `BLUETOOTH_LE`, `USB_SERIAL`, `SIMULATOR`) with clean preference migration preserving existing Wi-Fi defaults (`192.168.0.10:35000`).
  - Implement real `ObdTransport` classes in `app`:
    - `BluetoothClassicObdTransport` (RFCOMM SPP `00001101-...` + paired device discovery & fallback socket)
    - `BluetoothLeObdTransport` (GATT client supporting `FFF0`, `FFE0`, `18F0`, ISSC `49535343-...`, and Nordic UART `6E400001-...` notify/write ELM327 adapters + BLE scanner)
    - `UsbSerialObdTransport` (native Android `UsbManager` host API supporting FTDI, CP210x, CH340, and CDC-ACM wired ELM327/OBDLink cables + runtime permission intent helper)
  - Declare Bluetooth/BLE (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` with `neverForLocation`) and optional USB/BT hardware flags cleanly in `AndroidManifest.xml` and request Bluetooth runtime permissions cleanly in `MainActivity.kt`.

## Capabilities

### Modified Capabilities
- `hud-canvas`: `HUD Dashboard` centerpiece hero card with live Page 1 miniature preview on Home, compact `Last Saved Run` strip, top-right Settings gear icon, and top-right Connection Mechanism & Status quick-toggle pill.
- `obd-protocol`: `ObdConnectionType` multi-mechanism support adding real `BluetoothClassicObdTransport` (SPP), `BluetoothLeObdTransport` (GATT), and `UsbSerialObdTransport` (USB OTG) implementations alongside `WifiTcpTransport` and `SimulatedObdTransport`.

## Impact

- `obd-core`: Add `ObdConnectionType` and shared `ElmPromptFraming` / prompt-extraction helpers cleanly tested in JVM unit tests.
- `app`: `AndroidManifest.xml`, `MainActivity.kt`, `AppPreferences.kt`, new transports (`BluetoothClassicObdTransport.kt`, `BluetoothLeObdTransport.kt`, `UsbSerialObdTransport.kt`), `HudViewModel.kt`, `HomeMenuScreen.kt`, `HomeSheets.kt`, `HudCanvasScreen.kt`, and unit tests.
