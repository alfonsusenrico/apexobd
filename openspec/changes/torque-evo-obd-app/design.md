# Design: Torque Evo Android OBD-II App

## Context
Owner selected **Concept 1: Torque Evo** — pure `#000000` OLED background, `#00A8FF` electric blue borderless dials and readouts, zero persistent navigation chrome during driving, and swipeable N-page dynamic canvas where any sensor or functional widget (Mini-Map, DTC Checker, HP/Torque, 0–100 timer, Data Logger) can be added, resized, morphed, and reordered.

## Goals & Non-Goals
- **Goals:** High-FPS (`60fps`) Compose `Canvas` dials with `tabular-nums`, rock-solid Wi-Fi ELM327 socket handling (even when cellular data is active), comprehensive SAE J1979 + UDS Mode `22` coverage, reliable background telemetry logging, and fast JVM unit tests for the entire OBD-II engine.
- **Non-Goals:** Hardcoded multi-screen navigation tabs during driving; Bluetooth SPP/BLE implementation in this release.

## Numbered Architecture Decisions

1. **Two-Module Gradle Architecture (`:obd-core` Pure Kotlin JVM + `:app` Android Compose)**
   - *Decision:* Keep all ELM327 framing, AT initialization, bitmask PID discovery, SAE J1979 decoders, DTC parser, UDS Mode `22` formula evaluator, HP/Torque physics math, and `SimulatedObdTransport` in pure Kotlin (`:obd-core`).
   - *Rejected Alternative:* Putting protocol code directly inside `:app`. Rejected because pure JVM unit tests run in <2s without an Android SDK emulator and isolate hardware parsing bugs cleanly.

2. **Wi-Fi Network Binding (`ConnectivityManager.bindProcessToNetwork` / `SocketFactory`)**
   - *Decision:* On Android, Wi-Fi ELM327 dongles provide no internet, causing Android to route sockets over cellular unless bound. `WifiNetworkBinder` requests `TRANSPORT_WIFI` and binds the socket's `SocketFactory` directly to the Wi-Fi `Network` (while falling back gracefully if only loopback/LAN is active).
   - *Rejected Alternative:* Assuming `Socket(ip, port)` routes over Wi-Fi automatically. Rejected because modern Android versions fail with `ECONNREFUSED` or `ENETUNREACH` over cellular.

3. **Zero-Chrome Swipeable `HorizontalPager` + `CanvasPage` Persistence in Room**
   - *Decision:* `MainActivity` opens directly onto `HudCanvasScreen` (`HorizontalPager` across `N` user-defined `HudPageEntity` rows). Each `HudWidgetEntity` stores `(pageId, sortOrder, sensorKey, displayType, spanCols, spanRows, warnThreshold, critThreshold)`. A subtle top-right `⚙ / +` pill (auto-dimmed while driving) opens the Parked Setup & Component Picker sheet.
   - *Rejected Alternative:* BottomNavigationBar or NavigationRail. Rejected by owner to maximize glanceability and minimize driving distraction.

4. **Embedded Google Mini-Map with Keyless Vector Fallback**
   - *Decision:* `MiniMapWidget` renders `GoogleMap` (from `maps-compose`) configured in dark automotive JSON style following the live GPS `LatLng` trail when `BuildConfig.MAPS_API_KEY` is non-empty; if no API key is configured yet, it renders a live dark-mode GPS polyline vector canvas so the app works immediately out of the box without crashing.
   - *Rejected Alternative:* Crashing or showing a blank box without a Maps key. Rejected for reliability.

5. **Safe Recursive-Descent Expression Evaluator for UDS Mode `22` / Torque CSV**
   - *Decision:* Implement a lightweight pure-Kotlin arithmetic expression parser supporting `A`, `B`, `C`, `D` (and `E`..`H`) byte variables, `+`, `-`, `*`, `/`, parentheses, bitshifts (`<<`, `>>`), bitwise `&`, `|`, and `SIGNED(x)`.
   - *Rejected Alternative:* Reflection, Rhino/JS engine, or eval. Rejected due to APK bloat and security/performance overhead.

## Failure & Recovery Behaviour
- **ELM327 Timeouts / `NO DATA` / `STOPPED` / `BUS INIT: ... ERROR`:** Re-sync on `>` prompt, backoff retry up to 3 times, mark unsupported PIDs temporarily skipped, and auto-reconnect Wi-Fi TCP socket with exponential backoff (1s → 2s → 5s).
- **Clear DTCs (`Mode 04`):** Always requires explicit confirmation dialog stating readiness monitor reset and recommends ignition ON / engine OFF.
- **High-Temperature / Low-Voltage / Fault Alerts:** Highlight only the breaching widget's numeric/arc state in amber/red plus a subtle border pulse—never block the whole screen while driving.

## Risks
- Local machine currently has `openjdk@17` and `gradle` installed, while `~/Library/Android/sdk` access was restricted by the standard sandbox and may need Android SDK Platform 35 installed/verified outside sandbox before `assembleDebug`. Unit tests in `:obd-core` can run on JDK 17.
