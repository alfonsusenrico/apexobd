# ApexOBD (Torque Evo)

Android-only OBD-II HUD, diagnostics, data logging, and GPS trip tracking application built with Kotlin and Jetpack Compose.

## Stack & Conventions
- **Language:** Kotlin (JVM 17, Coroutines, StateFlow)
- **UI:** Jetpack Compose + Material 3 + custom Canvas gauges (`Torque Evo` pure `#000000` OLED + `#00A8FF` blue aesthetic)
- **Design surface:** Mobile automotive HUD & telemetry dashboard (`ui-ux-quality` applies)
- **Capabilities:** `obd-protocol`, `hud-canvas`, `diagnostics`, `logging-tracking`, `custom-pids`
- **Architecture:** Single-activity MVVM with clean `obd-core` module (`ObdTransport` interface: `WifiTcpTransport` + `SimulatedObdTransport`), Room persistence, and Foreground Service for background logging.

## Rules
- Keep `PROJECT_STATE.md` and `JOURNAL.md` local and untracked.
- Never hardcode Google Maps API keys or secrets; optionally read `MAPS_API_KEY` from `local.properties` (`BuildConfig`) for optional online Google Maps mode while defaulting to free MapLibre offline vector maps.
- Keep the 2-page architecture: Page 1 static/summary Home Menu (no live polling while idle on Home) and Page 2 zero-navigation-chrome `#000000` blank-default HUD Canvas (`HorizontalPager` with hold-tap hidden context menu).
