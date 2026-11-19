# Verification Report — `c1-smooth-can-vsync-hud`

- **Date:** `2026-11-19`
- **Branch:** `fix/hud-labels-bars-smoothing` (branched from `main` at `e35b0b7`, working tree verified post-final edit)
- **Artifact:** `app/build/outputs/apk/debug/app-debug.apk` (`62,810,124` bytes, SHA-256 `a7f28bbfb81d933d1776f11e640d650cd770b208ff7923adf0c761a4b28d7dc`)
- **Device:** `RMX1921` (`3646fbc7`, in-place `adb install -r app/build/outputs/apk/debug/app-debug.apk`)

## Automated Verification (Post Final Code Edit)

| Check | Outcome | Evidence |
| --- | --- | --- |
| `gradle :obd-core:test --offline --rerun-tasks` (JDK 17) | Passed | `45` tests, `0` failed, `0` skipped |
| `gradle :app:testDebugUnitTest --offline --rerun-tasks` (JDK 17) | Passed | `24` tests, `0` failed, `0` skipped |
| `gradle :app:lintDebug --offline --rerun-tasks` (JDK 17) | Passed | `0` errors (`lint-results-debug.xml`) |
| `gradle :app:assembleDebug --offline --rerun-tasks` (JDK 17) | Passed | `59` tasks executed, `BUILD SUCCESSFUL in 31s` |
| `openspec validate --all --strict` | Passed | `11 passed, 0 failed` (`c1-smooth-can-vsync-hud` valid) |

## Device & Session 15 Baseline Audit (`RMX1921` / `3646fbc7`)

- **Pre-fix Failing Baseline Confirmed:** Verified `RMX1921` ran `7161dd2` (`2026-11-19 07:50:17`), captured `Session 15` (`8,048` rows, `11.852 km`, `LIGHT_DAMPER`, `MAX_PERFORMANCE`, `17` Page 1 widgets) and `files/ecu_dump.txt` (`37` Mode 01 PIDs; `010C451` `40 ms` vs `01451` `144 ms`, `0142` `142 ms`, `0144` `139 ms`; `engine_load` `502 ms` median / `1.569%` median jump).
- **Updated Build Verification:** Installed `app-debug.apk` (`lastUpdateTime=2026-11-19 18:26:01`, `versionCode=1`, `versionName=1.0.0`), launched `MainActivity` via `monkey` (`Events injected: 1`), confirmed live PID `9370`, `dumpsys gfxinfo` (`70` startup frames rendered, 50th percentile `8 ms`, GPU 50th percentile `5 ms`), and empty crash logcat (`AndroidRuntime:E` / `System.err:W` clean).
- **What Was Not Verified Live:** End-to-end driving against the live `192.168.0.10:35000` ECU Wi-Fi adapter awaits the user's next road run (screen stayed locked during USB automation so `uiautomator dump` returned null root; HUD Canvas live adapter polling was verified in mock CAN transport unit tests).
