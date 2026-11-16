# OBD-II Protocol & Android Wi-Fi Architecture Research

Date: 2026-11-16

## 1. OBD-II / SAE J1979 & UDS Protocol Capabilities

### 1.1 Standard SAE J1979 / ISO 15031-5 Modes
- **Mode `01` (Current Powertrain Data):** Real-time sensor PIDs discovered via bitmask PIDs `00`, `20`, `40`, `60`, `80`, `A0`. Covers ~100+ standard parameters:
  - Engine & Air: RPM (`0C`), Load (`04`, `43`), Throttle (`11`, `45`, `47`-`4C`), MAF (`10`), MAP/Boost (`0B`), Intake Air Temp (`0F`), Timing Advance (`0E`), Run Time (`1F`).
  - Temperatures & Pressures: Coolant Temp (`05`, `67`), Ambient Air Temp (`46`), Barometric Pressure (`33`), Fuel Pressure (`0A`, `22`, `23`, `59`), Oil Temp (`5C`), Exhaust/DPF Temps (`78`-`7B`, `91`-`98`), Turbo/Boost (`6F`, `70`, `A7`-`AC`).
  - Fuel & Emissions: Fuel System Status (`03`), Short/Long Term Fuel Trim Banks 1 & 2 (`06`-`09`), O2 Sensors / Wideband Lambda (`14`-`1B`, `24`-`2B`, `34`-`3B`, `44`), Catalyst Temps (`3C`-`3F`), EGR (`2C`, `2D`), EVAP (`2E`, `32`, `52`, `A3`), Fuel Type (`51`), Fuel Level (`2F`), Fuel Rate (`5E`, `A2`).
  - Electrical & Status: Control Module Voltage (`42`), MIL & Readiness Status (`01`, `41`), Distance/Time with MIL On (`21`, `4D`), Warm-ups/Distance/Time since DTCs cleared (`30`, `31`, `4E`), Transmission Actual Gear (`A4`).
- **Mode `02` (Freeze Frame):** Snapshot of Mode `01` PIDs captured at the moment a DTC was set (`0202` = DTC that caused freeze frame).
- **Mode `03` / `07` / `0A` (DTCs):**
  - `03`: Confirmed / Stored DTCs (MIL on).
  - `07`: Pending DTCs (current/last drive cycle).
  - `0A`: Permanent DTCs (cannot be cleared by Mode `04`; ECU clears only after monitor passes).
  - Decoding: 2 bytes per DTC. First 2 bits = prefix (`00`=P, `01`=C, `10`=B, `11`=U), next 2 bits = digit 1 (`0`-`3`), remaining 12 bits = 3 hex digits.
- **Mode `04` (Clear/Reset DTCs & Monitors):** Clears confirmed/pending DTCs, freeze frame, and resets readiness monitors. Requires ignition ON (engine typically OFF).
- **Mode `05` / `06` (O2 & On-Board Monitoring):** Component/system test results (misfire, catalyst, EVAP, O2 limits). Mode `06` supersedes `05` on CAN (ISO 15765-4).
- **Mode `08` (On-Board System Control):** Bidirectional routine control (e.g., EVAP leak test).
- **Mode `09` (Vehicle Info):** VIN (`0902`), Calibration IDs (`0904`), CVN (`0906`), ECU Name (`090A`).
- **UDS Mode `22` (ISO 14229 ReadDataByIdentifier):** 2-byte DIDs (`0000`–`FFFF`) for OEM-specific sensors (transmission fluid temp, oil pressure, DPF soot mass, TPMS pressures, hybrid/EV battery cell voltages/SOC). Supports Torque-style custom PID formulas (`A`, `B`, `C`, `D` byte expressions).

## 2. Wi-Fi ELM327 Transport & Polling Architecture

### 2.1 Wi-Fi TCP Socket Behavior
- Typical Wi-Fi ELM327 adapters act as a Wi-Fi Access Point (`192.168.0.10`, TCP port `35000`; some use `192.168.0.10:23` or `192.168.4.1:35000`).
- **Android Network Binding Caveat:** Because the ELM327 Wi-Fi AP has no internet access, Android may refuse to route traffic over Wi-Fi if cellular data is active. The app must use `ConnectivityManager.bindProcessToNetwork(wifiNetwork)` (or `NetworkRequest` with `NET_CAPABILITY_NOT_METERED` / `TRANSPORT_WIFI`) when opening the TCP socket, while keeping the `ObdTransport` interface clean.
- **Protocol Framing:**
  - Commands are ASCII terminated with `\r`.
  - Responses are terminated by the ELM327 prompt character `>`.
  - Init sequence: `ATZ` (reset) -> `ATE0` (echo off) -> `ATL0` (linefeeds off) -> `ATS0` (spaces off, optional/faster) -> `ATH0` (headers off for standard, `ATH1` when parsing multi-ECU) -> `ATAT1` (adaptive timing) -> `ATSP0` (auto-detect vehicle protocol).

### 2.2 High-Frequency Polling Scheduler
- ELM327 is half-duplex: one command in flight at a time (FIFO queue with strict `>` prompt synchronization and timeout recovery).
- **Priority Tiers:**
  - **High Priority (every cycle):** Visible dashboard PIDs (e.g., RPM, Speed, Boost, Throttle).
  - **Medium Priority (every 5-10 cycles):** Slow-changing visible PIDs (Coolant Temp, Oil Temp, Battery Voltage, Fuel Level).
  - **On-Demand:** DTC read/clear, Freeze Frame, Mode `06` monitors, Mode `09` VIN.
- **CAN Multi-PID & Response Count Optimization:** On CAN (`ISO 15765-4`), append expected frame count `1` (e.g., `010C1`) so the ELM327 stops waiting for additional ECUs immediately after receiving the first reply, cutting latency significantly.

## 3. Kotlin / Android Tech Stack
- **Language:** Kotlin 2.x + Coroutines & `StateFlow`
- **UI:** Jetpack Compose + Material 3 + custom `Canvas` gauges/charts
- **Storage:** Room (SQLite) for trips, telemetry samples, custom PIDs, and saved dashboard layouts; DataStore for adapter/app preferences
- **Location:** Android `FusedLocationProviderClient` (or `LocationManager`) for GPS speed & route tracking
- **Transport Abstraction:** `ObdTransport` interface with `WifiTcpTransport` (primary) and `SimulatedObdTransport` (demo/testing mode without a car).
