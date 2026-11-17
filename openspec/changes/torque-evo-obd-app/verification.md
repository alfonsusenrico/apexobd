# Verification Record: Torque Evo Android OBD-II App

- **Branch:** `feat/torque-evo-obd-app`
- **Date:** 2026-11-17
- **Build Environment:** macOS, Homebrew OpenJDK 17 (`17.0.21.0.2`), Gradle `9.8.2`, Android SDK Platform `android-35` & Build Tools `35.0.0`
- **Hardware Tested:** Realme `RMX1921` (`adb` serial `3646fbc7`), Wi-Fi ELM327 `OBDII v1.5` at `192.168.0.10:35000`, live vehicle ECU `7E8` (`ISO 15765-4 CAN 11/500`)

## Commands & Results

1. **Live Car ECU Discovery Scan**
   - Evidence: `research/evidence/2026-11-17-live-car-ecu-dump.txt`
   - Result: Connected to `192.168.0.10:35000`, detected `AUTO, ISO 15765-4 (CAN 11/500)` (`ATDPN=A6`), discovered 16 Mode `01` data PIDs (`04, 05, 06, 07, 0B, 0C, 0D, 0E, 0F, 10, 11, 14, 2E, 33, 42, 45`) at `43–50 ms` single-PID RTT (`01XX1`), verified Mode `03`/`07` DTC scan (`0` faults), and mapped `Mode 21` response behavior (`7E0 2100/2104/2110/2120/2130`).

2. **Full Unit Tests & Debug APK Build + Install**
   - Command: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.21.0.2/libexec/openjdk.jdk/Contents/Home gradle :obd-core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain && adb install -r app/build/outputs/apk/debug/app-debug.apk`
   - Result: `BUILD SUCCESSFUL in 11s`, `17/17` unit tests passed (`0` skipped, `0` failed), APK installed and launched on `RMX1921`.
   - Unit Test Counts:
     - `com.apexobd.core.formula.FormulaEvaluatorTest`: 3 passed
     - `com.apexobd.core.protocol.DtcDecoderTest`: 4 passed
     - `com.apexobd.core.protocol.ObdPidRegistryTest`: 5 passed
     - `com.apexobd.core.scheduler.EngineAndPerformanceTest`: 4 passed
     - `com.apexobd.app.TripExporterTest`: 1 passed
