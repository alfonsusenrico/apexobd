# Product Plan: Modern Android OBD-II HUD & Diagnostics App (Single-Phase)

Date: 2026-11-16
Target Platform: Android (Kotlin + Jetpack Compose)
Primary Adapter: Wi-Fi ELM327 (TCP Socket, default `192.168.0.10:35000`) + Demo Simulator
Status: Planning & Visual Preview Stage (Awaiting Owner Approval of UI Preview)

## 1. Product Vision
A modern, distraction-free, automotive-grade replacement for Torque:
- Crisp **OLED Cockpit Dark** theme for night/windshield HUD + **High-Contrast Daylight** theme.
- **Customizable component-based HUD** with drag/reorder/resize widgets, Landscape & Portrait layouts, and **Windshield HUD Mirror Mode**.
- Full **SAE J1979** diagnostics (`01`–`0A`) + **UDS Mode `22`** custom/extended PIDs.
- High-frequency **Data Logging** (CSV/JSON export) and **GPS Trip & Performance Tracking** (0–100 km/h, route & telemetry correlation).

## 2. Module Breakdown (All in One Phase)

1. **Core OBD-II Engine (`obd-core`)**
   - `ObdTransport` (`WifiTcpTransport` with Android Wi-Fi network binding + `SimulatedObdTransport` for offline testing).
   - ELM327 AT state machine (`ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATAT1`, `ATSP0`, timeout & reconnect recovery).
   - Bitmask PID discovery (`0100`–`01A0`) and full SAE J1979 Mode `01` decoder (~100+ PIDs).
   - Priority-tiered polling scheduler (High / Medium / On-Demand).
   - Mode `02` Freeze Frame, Mode `03`/`07`/`0A` DTCs (with built-in SAE P/C/B/U database), Mode `04` Clear DTCs, Mode `05`/`06` Monitors, Mode `09` VIN/CALID.
   - UDS Mode `22` & custom formula evaluator (Torque CSV import + manual custom PID editor).

2. **Swipeable N-Page Dynamic HUD Canvas (`feature-hud`)**
   - **Zero persistent navigation chrome while driving:** opens directly into a fullscreen horizontal pager (`HorizontalPager`) where the user simply swipes left/right between `N` dynamically created pages with minimal movement and visual distraction.
   - **Everything is a component:** sensor gauges (`Analog Dial`, `Semi-Digital Arc`, `Digital Readout`, `Bar / Line`, `Live Sparkline Chart`), `Google Mini-Map`, `Live DTC Checker & Clear (Mode 04)`, `0–100 km/h Timer`, `HP / Torque`, and `Data Logger Quick-Record` widgets can be placed on any page.
   - Edit Mode (parked): add/remove components from the full OBD-II / GPS / Calculated catalog, drag-and-drop reorder, resize (`1x1`, `2x1`, `2x2`), add/delete custom pages, configure min/max/warning/critical thresholds and units (Metric / Imperial).
   - Selectable visual styles (`Pure OLED Blue`, `Motorsport Lime`, `Liquid Glass Map`, `Precision Bento`, `Co-Pilot Teal`), **HUD Mirror Mode** (horizontal flip), Landscape & Portrait support, and screen wake-lock while connected.

3. **Diagnostics & DTC Clearance (`feature-diagnostics`)**
   - Categorized DTC list: Confirmed (`03`), Pending (`07`), Permanent (`0A`) with code, description, and Freeze Frame (`02`) snapshot.
   - Readiness Monitors (`01` / `06`) status grid.
   - Safe **Clear DTCs (`04`)** flow with explicit confirmation modal explaining monitor reset.

4. **Data Logging & GPS Trip Tracking (`feature-logging`)**
   - Foreground service for reliable background/screen-off recording while driving.
   - High-frequency Room/SQLite recording of selected PIDs + GPS coordinates/speed.
   - Trip summary: distance, duration, avg/max speed, peak RPM/boost, estimated fuel economy.
   - **0–100 km/h (0–60 mph)** automated acceleration timer.
   - Session viewer with multi-channel chart + route trace, plus **CSV & JSON export** via Android Share Sheet.

5. **Settings & Vehicle Profiles (`feature-settings`)**
   - Wi-Fi Adapter config (IP, port, auto-connect, polling aggressiveness, protocol override).
   - Vehicle profile (VIN auto-read via Mode `09`, displacement, fuel type, redline RPM).
   - Theme selector (OLED Dark / Daylight / Auto), Units (km/h vs mph, °C vs °F, bar vs psi, L/100km vs mpg).

## 3. Approval Gate
1. Owner reviews and approves the **Interactive Visual Preview** (`research/prototypes/ui-preview/index.html`).
2. Once approved, formalize OpenSpec specs (`openspec/changes/...`) and scaffold the Android Kotlin/Compose project.
