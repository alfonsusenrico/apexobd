# ApexOBD (Torque Evo)

Android-only OBD-II HUD, diagnostics, data logging, and GPS trip tracking application built with Kotlin and Jetpack Compose.

## Stack & Conventions
- **Language:** Kotlin (JVM 17, Coroutines, StateFlow)
- **UI:** Jetpack Compose + Material 3 + custom Canvas gauges (`Torque Evo` pure `#000000` OLED + `#00A8FF` blue aesthetic)
- **Design surface:** Mobile automotive HUD & telemetry dashboard (`ui-ux-quality` applies)
- **Capabilities:** `obd-protocol`, `hud-canvas`, `diagnostics`, `logging-tracking`, `custom-pids`
- **Architecture:** Single-activity MVVM with clean `obd-core` module (`ObdTransport` interface: `WifiTcpTransport` + `SimulatedObdTransport`), Room persistence, and Foreground Service for background logging.

## Rules
- **Delivery:** us
- Keep `PROJECT_STATE.md` and `JOURNAL.md` local and untracked.
- Keep `README.md` strictly user-facing product branding (no developer build guides or technical internals).
- Default to free MapLibre offline/vector maps (`OpenFreeMap` + `.pmtiles`) and `OLED Track Only` mode with zero Google Maps API keys or external navigation dependencies.
- Keep the 2-page architecture: Page 1 summary Home Menu and Page 2 zero-navigation-chrome `#000000` blank-default HUD Canvas (`VerticalPager` with hold-tap context and component edit menus). Keep the OBD-II adapter listener connected continuously via the persistent foreground background service whenever the app is opened (showing an ongoing notification that terminates the background service when tapped).

## Release
- **CI:** `.github/workflows/ci.yml` runs `:obd-core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` on every PR and push to `main`.
- **Release Trigger:** Tag `vMAJOR.MINOR.PATCH` on a green commit on `main`.
- **Release Workflow:** `.github/workflows/release.yml` verifies tests and lint, signs `ApexOBD-<tag>-release.apk` and `ApexOBD-<tag>-release.aab` using GitHub Actions Secrets (`APEX_KEYSTORE_BASE64`, `APEX_KEYSTORE_PASSWORD`, `APEX_KEY_ALIAS`, `APEX_KEY_PASSWORD`), verifies APK v2/v3 signatures with `apksigner`, publishes the GitHub Release with `SHA256SUMS.txt`, and automatically uploads the signed `.aab` to Google Play (`internal` track) once `PLAY_SERVICE_ACCOUNT_JSON` is configured.
- **Local Signing:** Uses gitignored `keystore.properties` pointing to `~/.config/apexobd/apexobd-release.jks` (never commit keystores or credentials).
