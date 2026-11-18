# Verification — `can-detect-2024-load-fuel-deepscan-ux`

Verified on working tree of branch `feat/torque-evo-obd-app` on `2026-11-18`.

## Automated Checks Passed

- **Unit Tests (`:obd-core:test`):** Passed (`33/33` tests, `0` failures, `0` skipped)
  - `FormulaEvaluatorTest`: 3/3
  - `DtcDecoderTest`: 4/4
  - `ObdPidRegistryTest`: 7/7 (added `0124` WR O2 Ratio & `014C` Commanded Throttle checks against real 2024 Avanza dump payloads)
  - `TelemetryCalculatorTest`: 12/12 (added 2024 Avanza 37-PID `0104 Engine Load` airflow/KPL/HP/DFCO simulation without `0110 MAF` or `010B MAP`)
  - `EngineAndPerformanceTest`: 7/7 (added clone `ATDP = Auto` / `ATDPN = AUTO, ISO 15765-4 (CAN 11/500)` regression test verifying `isCanProtocol = true` and `ATST19` emission)
- **Unit Tests (`:app:testDebugUnitTest`):** Passed (`10/10` tests, `0` failures, `0` skipped)
  - `HudGridLayoutTest`: 3/3
  - `TripExporterTest`: 7/7
- **Android Lint (`:app:lintDebug`):** Passed (`0` errors, `51` warnings)
- **Debug APK Build (`:app:assembleDebug`):** Passed (`app/build/outputs/apk/debug/app-debug.apk`)
- **OpenSpec Strict Validation (`openspec validate --all --strict`):** Passed (`5/5` changes passed, `0` failed)

## On-Device Verification (`RMX1921` / `3646fbc7`, in-place `adb install -r`, zero `pm clear`)

- **App Launch & Data Preservation (`v7-04-home-after.png`):** Verified owner's updated `Avanza K3VE` (`1298 cc · AT · MPV · 2014 · 1200 kg · Tank 44.8/45 L`, `116949 km` odometer, `4 Page(s)`, `5 Run(s)`, `192.168.0.10:35000 · Wi-Fi`) and real `files/ecu_dump.txt` preserved cleanly with dark vector map rendered.
- **`ECU PIDs` Deep Scan & Share Dump UI (`v7-03-ecupids-sheet.png`):** Verified **`Share Dump`** button alongside **`Deep Scan`**, `Checking OBD connection...` status line, and newly decoded `014C · Commanded Throttle Actuator`. Crash log (`adb -s 3646fbc7 logcat -b crash -d`) returned empty.
- **Remaining Live In-Car Check:** Plugging back into either vehicle (`2014 Avanza K3VE 4AT` or `2024 Avanza 1.5G MT`) will verify `ATST19` (`~45ms` single-PID / packed CAN polling), live `0104` Load-based KPL/Power on the 2024 Avanza, and live `Deep Scan` progress bar + `2123..2134` OEM dump + auto-resume inside the `ECU PIDs` sheet.
