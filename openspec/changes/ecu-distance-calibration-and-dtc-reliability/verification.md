# Verification Record: `ecu-distance-calibration-and-dtc-reliability`

- **Date:** 2026-11-22
- **Base Revision:** `46bd038` (`main`)
- **Device Tested:** Connected Android device `3646fbc7` (`RMX1921`)

## Commands & Results
1. `openspec validate --all --strict`:
   - `14 passed, 0 failed (14 items)`
2. `gradle -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home :obd-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`:
   - `:obd-core:test`: `57/57` passed (`0` failures, `0` skipped) — includes new tests for `7E0 2131` discovery without fabricating `fuel_level` or `ecu_odometer`, ISO-TP multi-frame and multi-ECU DTC decoding, `2014 Avanza K3-VE` `07` + `2106` readiness fallback, `7E0` physical Mode 04 clear fallback, `fullRefuelCycleKpl` DTE isolation, and background-idle reconnect backoff scaling (`2.5s -> 30s`).
   - `:app:testDebugUnitTest`: `28/28` passed (`0` failures, `0` skipped).
   - `:app:lintDebug`: passed (`0` errors).
   - `:app:assembleDebug`: succeeded (`app/build/outputs/apk/debug/app-debug.apk`).
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk` & Room `MIGRATION_8_9` check:
   - Installed and launched `com.apexobd.app/.MainActivity` on `3646fbc7`.
   - Verified `PRAGMA user_version = 9` and columns `fullRefuelCycleKpl`, `ecuDistanceBaselineKm`, `odometerAtBaselineKm`, `lastSeenEcuDistanceKm` on `vehicle_profiles` plus `cycleFuelLiters` on `refuel_logs`.
