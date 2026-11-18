# Verification — `branding-profile-scoping-card-picker`

**Date:** `2026-11-18`  
**Branch:** `feat/torque-evo-obd-app`  
**Owner Selections Applied:** Wordmark **B** (Apex Cut + Blue OBD Pill), App Icon **2** (Stacked Monogram), `Sport Arrow` default map cursor, `Trip Efficiency` formatted as percentage (`%`), full layout rework dropped in favor of interactive `ShareCardComposerDialog`, fixed car pill order, tabular run cards, and inline feature settings.

## Automated Checks (Post-Final-Edit)
- `:obd-core:test`: **33 / 33 passed** (`0` failures, `0` skipped, `0` errors)
- `:app:testDebugUnitTest`: **12 / 12 passed** (`0` failures, `0` skipped, `0` errors), including `TripShareStatsCalculatorTest` (`summarize`, `computeMetricsFromStats`, `TripCardConfig`, `18 / 48 km/h`, `68%`)
- `:app:lintDebug`: **0 errors**, `52` warnings (`app/build/reports/lint-results-debug.html`)
- `:app:assembleDebug`: succeeded (`app/build/outputs/apk/debug/app-debug.apk`, `59M`)
- `openspec validate --all --strict`: **6 / 6 passed** (`0` failed)

## Live Device Checks (`RMX1921`, `3646fbc7`, In-Place `adb install -r`)
- Verified `Avanza K3VE` (`116949 km`, `5` runs) and `Avanza 2NR` (`0 km`, `0` runs) remain isolated with preserved data and stable pill ordering when switching.
- Verified Wordmark B decal + clickable `192.168.0.10:35000 · Wi-Fi` chip on Home.
- Verified `RunLogsAndMapsSheet` tabular run cards (`AVG / TOP` showing `18 / 48 km/h` on legacy Session #2 and Session #4), swipe-locked scroll, and inline `Map Style & Cursor` controls.
- Verified `ShareCardComposerDialog` with pinned live preview, `Car Photo` / `Plain Dark` / `Transparent` alpha checkerboard background modes, individual layer checkboxes (`App Brand Icon`, `Vehicle Name`, `Vehicle Specs`, `Date`, `Time`, `Route Path`, `Start / End Dots`, `Metrics Backing`, `Show Telemetry Metrics Grid`), max-6 metric picker (`68%` / `74%` Efficiency), and pinned bottom `Cancel` / `Share Image (PNG)` buttons.
- Verified `logcat -b crash -d` completely empty after testing.
