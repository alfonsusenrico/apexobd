# Verification Record: Torque Evo Android OBD-II App

- **Branch:** `feat/torque-evo-obd-app`
- **Date:** 2026-11-16
- **Build Environment:** macOS, Homebrew OpenJDK 17 (`17.0.21.0.2`), Gradle `9.8.2`, Android SDK Platform `android-35` & Build Tools `35.0.0`

## Commands & Results

1. **OpenSpec Strict Validation**
   - Command: `openspec validate --all --strict`
   - Result: `Totals: 1 passed, 0 failed (1 items)`

2. **Full Unit Tests & Debug APK Build**
   - Command: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.21.0.2/libexec/openjdk.jdk/Contents/Home gradle :obd-core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`
   - Result: `BUILD SUCCESSFUL in 17s`
   - Unit Test Counts:
     - `com.apexobd.core.formula.FormulaEvaluatorTest`: 3 passed, 0 failed, 0 skipped
     - `com.apexobd.core.protocol.DtcDecoderTest`: 4 passed, 0 failed, 0 skipped
     - `com.apexobd.core.protocol.ObdPidRegistryTest`: 3 passed, 0 failed, 0 skipped
     - `com.apexobd.core.scheduler.EngineAndPerformanceTest`: 3 passed, 0 failed, 0 skipped
     - `com.apexobd.app.TripExporterTest`: 1 passed, 0 failed, 0 skipped
     - **Total:** 14/14 passed
   - Artifact: `app/build/outputs/apk/debug/app-debug.apk` (18 MB)

## Not Verified (Pending Physical Device / Hardware)
- Live UI rendering on an Android phone/emulator (`adb devices` reported 0 attached devices at verification time).
- Live TCP socket exchange against a physical Wi-Fi ELM327 dongle (`192.168.0.10:35000`) and Google Map tiles (keyless vector GPS trail fallback active until `MAPS_API_KEY` is added to `local.properties`).
