# Verification Report — `gps-glide-dte-create-card`

- **Date:** `2026-11-19`
- **Branch:** `fix/hud-labels-bars-smoothing` (branched from `main` at `e35b0b7`)
- **Artifact:** `app/build/outputs/apk/debug/app-debug.apk` (`60M`, SHA-256 `c0178cec6a16242a25cd070f58547cedcc179269ea639e7920c48d63190ed5ce`)
- **Device:** `RMX1921` (`3646fbc7`, in-place `adb -s 3646fbc7 install -r`)

## Automated Verification

| Check | Outcome | Evidence |
| --- | --- | --- |
| `gradle :obd-core:test` (JDK 17) | Passed | `tests=42, failures=0, errors=0, skipped=0` |
| `gradle :app:testDebugUnitTest` (JDK 17) | Passed | `tests=20, failures=0, errors=0, skipped=0` |
| `gradle :app:lintDebug` (JDK 17) | Passed | `0 errors`, `53 warnings` |
| `gradle :app:assembleDebug` (JDK 17) | Passed | `BUILD SUCCESSFUL`, APK `app/build/outputs/apk/debug/app-debug.apk` (`60M`) |
| `openspec validate --all --strict` | Passed | `Totals: 9 passed, 0 failed (9 items)` |

## Device Verification (`RMX1921` / `3646fbc7`)

- Installed in place via `adb -s 3646fbc7 install -r app/build/outputs/apk/debug/app-debug.apk` (`Performing Streamed Install / Success`).
- `dumpsys package com.apexobd.app` reported `versionCode=1 minSdk=26 targetSdk=35`, `versionName=1.0.0`, `lastUpdateTime=2026-11-19 00:18:09`.
- Launched `com.apexobd.app/.MainActivity` and inspected `logcat -b crash -d`: empty (`0` crashes).
- **UI & runtime scope limits:**
  - `uiautomator dump /sdcard/ui.xml` returned `ERROR: null root node returned by UiTestAutomationBridge.` because the phone screen was locked/asleep, so on-device `Create Card` rendering (`1:1`, `4:3`, `9:16`, custom photo picker, `Surrounding Roads (60%)` via `MapSnapshotter`, `Export` dialog) was not visually captured after install.
  - Real-world 60 Hz `gps_speed` / throttle / engine load responsiveness and revised DTE (`10%` reserve + `allTimeTotalAvgKpl` cap) await the next road test on `Avanza K3VE`.
