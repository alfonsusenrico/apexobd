# Change: Low-Latency Wi-Fi OBD Hardening, Always-Connected Background Listener, and 7C0 Fuel-Level Deep Scan

## Why
1. Road testing on the 2024 Toyota Avanza showed smooth gauge motion between frames, but intermittent, inconsistent pauses where all metrics stopped and then resumed. Live ECU dumps from both vehicles confirmed exact `+102 ms` 802.11 DTIM beacon sleep spikes (`38 ms -> 142 ms`) plus app-side stall sources (missing `WIFI_MODE_FULL_LOW_LATENCY` lock, Play Services FLP Wi-Fi scans pulling the radio off-channel, per-byte `setsockopt` + 2000 ms timeout stalls, `ATAT2` premature `NO DATA`, and 15-second multi-PID re-probes on non-multi-PID ECUs).
2. Disconnecting the OBD adapter whenever leaving HUD Dashboard to Home Menu forces repeated handshakes and prevents background telemetry listening. The user wants the adapter connected continuously whenever the app is opened, running in the background with a persistent notification that terminates the background service when clicked.
3. Neither vehicle exposes standard Mode 01 PID `012F` (Fuel Tank Level) on the Engine ECU (`7E0`/`7E8`) because Toyota/Daihatsu/ASEAN cars wire the fuel sender to the Combination Meter (`7C0`/`7C8`). Deep Scan previously only probed `7E0`/`7E1` and custom PIDs ignored `headerHex`.

## What Changes
- **Wi-Fi / Polling Low-Latency Hardening (`obd-core` & `app`):**
  - Hold `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` (fallback `WIFI_MODE_FULL_HIGH_PERF`) and `PowerManager.PARTIAL_WAKE_LOCK` in `WifiNetworkBinder` while connected.
  - Prefer pure hardware GNSS (`LocationManager.GPS_PROVIDER`) in `LocationTracker` while Wi-Fi OBD is active so Play Services FLP does not trigger off-channel Wi-Fi scans.
  - Switch `WifiTcpTransport` to bulk `ByteArray(256)` reads, cached `soTimeout`, `IPTOS_LOWDELAY`, `keepAlive`, and `\r` resync on timeout; tighten live CAN poll timeout from `2000 ms` to `650 ms`.
  - Use reliable `ATAT1` + `ATST19` on CAN (`Elm327Client`) and disable session multi-PID retries when an ECU has never accepted multi-PID (`ObdPollingEngine`).
- **Always-Connected Background Listener & Tap-to-Terminate Notification (`app`):**
  - Scope `HudViewModel` to `ApexObdApplication` (`ViewModelStoreOwner`) so active OBD polling survives Activity backgrounding and Back navigation.
  - Upgrade `LoggingForegroundService` (`connectedDevice|location`) to start automatically on app open, display live background listener status in the ongoing notification, and terminate the background service + disconnect the adapter when the notification is clicked.
  - Show live adapter status dot on Home Menu and Stop/Connect controls in `AdapterConnectionSheet`.
- **Digital Fuel Level Discovery & Custom Header Support (`obd-core` & `app`):**
  - Implement CAN header switching (`ATSH<hdr>` + `ATCRA<reply>` with guaranteed `ATCRA` + `ATSH7DF` restore) for `CustomPidDefinition.headerHex` in `ObdPollingEngine`, multi-frame ISO-TP decoding in `FormulaEvaluator`, and an optional Header field in `EcuPidsSheet`.
  - Extend strictly read-only `EcuDiscoveryDumper` to walk all `7E0` Mode 21 bitmasks and probe `7C0` (Combination Meter), `750` (Body ECU), `7E1`, and `7E2` for digital fuel level PIDs.
