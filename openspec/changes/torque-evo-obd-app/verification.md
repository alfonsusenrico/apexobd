# Verification Record: ApexOBD (Torque Evo) Android App

- **Branch:** `feat/torque-evo-obd-app`
- **Date:** 2026-11-17
- **Build Environment:** macOS, Homebrew OpenJDK 17 (`17.0.21.0.2`), Gradle `9.8.2`, Android SDK Platform `android-35` & Build Tools `35.0.0`, MapLibre `android-sdk-opengl:13.7.1`
- **Hardware Tested:** Realme `RMX1921` (`adb` serial `3646fbc7`, `1080×2340 @ 420 dpi` = `411×891 dp`), Wi-Fi ELM327 `OBDII v1.5` at `192.168.0.10:35000`, live vehicle ECU `7E8` (`ISO 15765-4 CAN 11/500`)

## Commands & Results

1. **Live Car ECU Discovery Scan**
   - Evidence: `research/evidence/2026-11-17-live-car-ecu-dump.txt`
   - Result: Connected to `192.168.0.10:35000`, detected `AUTO, ISO 15765-4 (CAN 11/500)` (`ATDPN=A6`), discovered 16 Mode `01` data PIDs (`04, 05, 06, 07, 0B, 0C, 0D, 0E, 0F, 10, 11, 14, 2E, 33, 42, 45`) at `43–50 ms` single-PID RTT (`01XX1`), verified Mode `03`/`07` DTC scan (`0` faults), and mapped `Mode 21` response behavior (`7E0 2100/2104/2110/2120/2130`).

2. **Full Unit Tests & Debug APK Build (Post-Audit Fixes)**
   - Command: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.21.0.2/libexec/openjdk.jdk/Contents/Home gradle :obd-core:test :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --no-daemon --console=plain`
   - Result: `BUILD SUCCESSFUL in 1m 2s`, `49 actionable tasks: 49 executed`, `26/26` unit tests passed (`0` skipped, `0` failed, `0` errors), APK output `app/build/outputs/apk/debug/app-debug.apk`.
   - Executed Unit Test Suites:
     - `com.apexobd.core.formula.FormulaEvaluatorTest`: 3 passed
     - `com.apexobd.core.protocol.DtcDecoderTest`: 4 passed
     - `com.apexobd.core.protocol.ObdPidRegistryTest`: 5 passed
     - `com.apexobd.core.protocol.TelemetryCalculatorTest`: 6 passed
     - `com.apexobd.core.scheduler.EngineAndPerformanceTest`: 5 passed (including `Elm327Client and DtcDecoder handle disconnected transport safely without throwing`)
     - `com.apexobd.app.TripExporterTest`: 3 passed (including offline bounds inset & zoom-out size estimate scaling)

3. **OpenSpec Schema Validation**
   - Command: `openspec validate --all --strict`
   - Result: `1 passed, 0 failed` (`change/torque-evo-obd-app`).

4. **Real-Device Full UI, Crash & Interaction Audit (`RMX1921`)**
   - **Fault Codes (Offline Wi-Fi mode):** Stayed alive (`PID 9235` / `PID 27783`, zero `AndroidRuntime:E` fatal exceptions), displayed `Adapter Offline / No ECU Response` banner, disabled `Clear Fault Codes (Mode 04)` while offline.
   - **ECU PIDs & Live Telemetry (Demo Simulator ON):** Displayed `35` discovered PIDs including `%`-unit PIDs (`0104` `66.7 %`, `0111` `66.7 %`), RPM, speed, coolant, IAT, MAP, and MAF with zero crashes (`PID 9239`).
   - **Offline Vector Maps Frame Picker (`OfflineMapDownloaderDialog`):** Removed `Wide (z13)` / `Streets (z14)` / `High (z15)` zoom chips (`z5–z14` fixed); verified panning & zooming out (`Zoom 10.5` → `Zoom 8.5` across Surabaya/Bangkalan/Mojokerto) dynamically updated `Frame Size` from `86 tiles (~1.4 MB)` to `903 tiles (~14.6 MB)`; verified `Whole-Island (.pmtiles)` tab layout.
   - **Refuel Checkpoint Log & Vehicle Profile:** Verified `#00A8FF` chips across all modals, aligned single-row `Currency` chips (`IDR`..`GBP`), aligned 2-column `Volume`/`Price` and `RON`/`Odometer` inputs plus full-width `Fuel Name (Optional)`, and verified checkpoint creation in Room.
   - **Home Portrait & Landscape + HUD Canvas Portrait & Landscape:** Verified zero clipping across all 4 Home modules (`Refuel Log`, `Fault Codes`, `Run Logs & Maps`, `ECU PIDs`) and Settings in Landscape (`16`→`v2-10`); verified HUD Pages 1–3 (`AnalogDialWidget` label clearance, `SemiDigitalArcWidget`, `1×1` stacked `BarGaugeWidget`, `PerfTimerWidget` `ARMED`, `DataLoggerWidget` `Ready` + `Start Run`), Context Menu (`Clear Page Widgets`), and tap-to-select floating Bottom Edit Bar (`◀`, `▶`, `Style`, `Size`, `×`, `Done ✓`).
