# Verification Report — `hud-labels-bars-smoothing`

Date: `2026-11-18`
Branch: `fix/hud-labels-bars-smoothing`

## 1. Automated JVM & Build Verification

- `:obd-core:test`: **39 / 39 passed** (`0` failures, `0` skipped)
  - `FormulaEvaluatorTest`: 3 passed
  - `DtcDecoderTest`: 4 passed
  - `ObdPidRegistryTest`: 8 passed (added test verifying `220.0 km/h` speed ceiling and `PollTier.HIGH` for `engine_load`, `throttle`, `commanded_throttle`, `map`)
  - `TelemetryCalculatorTest`: 12 passed (added regression test confirming steady 2000ms hold on `throttle` / `engine_load` does not inflate `AdaptiveSession` ramp duration past `155 ms`)
  - `EngineAndPerformanceTest`: 8 passed (added single-PID CAN scheduling test confirming visible `04`, `0C`, `0D`, `11`, and `45` all poll in the high-priority loop without starving `engine_load` or `throttle`)
  - `ElmResponseBufferTest`: 4 passed
- `:app:testDebugUnitTest`: **18 / 18 passed** (`0` failures, `0` skipped)
  - `HudGridLayoutTest`: 3 passed
  - `HudWidgetStyleTest`: 2 passed (added smooth spectrum color progression test for `Blue -> Green -> Yellow -> Orange -> Red` and high-contrast `labelColor` luminance check across all 7 HUD themes)
  - `TransportCodecTest`: 4 passed
  - `TripExporterTest`: 7 passed
  - `TripShareStatsCalculatorTest`: 2 passed
- `:app:lintDebug`: passed (`0` errors)
- `:app:assembleDebug`: succeeded (`app/build/outputs/apk/debug/app-debug.apk`, `60M`)
- `openspec validate --all --strict`: **8 / 8 passed** (`0` failed)

## 2. Live Device Verification (`RMX1921` / `3646fbc7`)

- Installed in-place via `adb -s 3646fbc7 install -r app/build/outputs/apk/debug/app-debug.apk` (owner car profiles `Avanza K3VE` and `Avanza 2NR`, car photo, and 5 trip runs preserved intact).
- Inspected Page 2 HUD Dashboard screenshot (`v7-hud-widgets.png`) and `logcat -b crash -d`:
  - All widget component headers (`RPM`, `LOAD`, `IAT`, `AMBIENT`, `TIMING`, `VOLTAGE`, `COOLANT`, `KPL · AVG`, `GPS SPD`) render with high-contrast popped white `labelColor` against `#000000` OLED.
  - Boxed Bar (`GPS SPD`) at `0 km/h` renders all discrete boxes empty dark unlit (`Color(0x14FFFFFF)` fill + subtle border) with neutral left stripe and `0 .. 220` bounds instead of phantom pre-colored segments.
  - Zero crashes in `logcat -b crash -d`.
- **Scope note:** Live vehicle road-test of the updated polling tier & smoothing curve on the 2024 Avanza MT is queued for your next drive with the Wi-Fi OBD2 adapter connected.
