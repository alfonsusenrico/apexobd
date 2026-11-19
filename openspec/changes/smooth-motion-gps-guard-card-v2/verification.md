# Verification Report — `smooth-motion-gps-guard-card-v2`

- **Date:** `2026-11-19`
- **Branch:** `fix/hud-labels-bars-smoothing` (branched from `main` at `e35b0b7`)
- **Artifact:** `app/build/outputs/apk/debug/app-debug.apk` (`60M`, SHA-256 `3400a00802ec9d3411d6662f17fd79276ebabd718b29a0f1b51cc1e9a7e3128c`)
- **Device:** `RMX1921` (`3646fbc7`, `1080x2340`, `420 dpi`, in-place `adb -s 3646fbc7 install -r`)

## Automated Verification

| Check | Outcome | Evidence |
| --- | --- | --- |
| `gradle :obd-core:test` (JDK 17) | Passed | `tests=43, failures=0, errors=0, skipped=0` |
| `gradle :app:testDebugUnitTest` (JDK 17) | Passed | `tests=23, failures=0, errors=0, skipped=0` |
| `gradle :app:lintDebug` (JDK 17) | Passed | `0 errors`, `53 warnings` (zero deprecation warnings in Kotlin build) |
| `gradle :app:assembleDebug` (JDK 17) | Passed | `BUILD SUCCESSFUL`, APK `app/build/outputs/apk/debug/app-debug.apk` (`60M`) |
| `openspec validate --all --strict` | Passed | `Totals: 10 passed, 0 failed (10 items)` |

## Device & Visual Verification (`RMX1921` / `3646fbc7`)

- Installed in place via `adb -s 3646fbc7 install -r app/build/outputs/apk/debug/app-debug.apk` (`lastUpdateTime=2026-11-19 07:50:17`, `versionCode=1`, `versionName=1.0.0`) with empty `logcat -b crash -d` (`0` crashes).
- **Home & Runs & Maps Sheets:** Verified clean bottom viewport margin and safe gesture clearance on `HomeMenuScreen` and `RunLogsAndMapsSheet`.
- **Create Route Card (`Session 14`):** Visually verified the recovered Surabaya route trace (previously a cross-Java straight diagonal line), seamless elliptical vignette fade on `Surrounding Roads` (`25%` default + `Main Roads Only` checkbox + `10%–60%` opacity slider), simplified `"1:1"` / `"4:3"` / `"9:16"` chips, unified `"Custom Photo"` background chip, and unobstructed bottom `"Cancel"` / `"Export"` actions on `RMX1921`.
- **HUD Dashboard:** Visually verified truncated integer `GPS SPD` (`0 km/h` empty boxed gauge), immediate live GPS lock on HUD `Live GPS` mini-map, and GPS-off entry prompt + `"GPS OFF · Tap to Enable"` badge when device Location was toggled off (restored to `location_mode=3` after check).
- **Road Test Scope Limit:** Live adapter 60 Hz continuous glide and optional `Live Display Micro-Pulse (Test)` on-road comparison (`rpm`, `engine_load`, `rel_throttle`, `kpl_road_avg`) await the next drive with the Wi-Fi OBD2 adapter connected.
