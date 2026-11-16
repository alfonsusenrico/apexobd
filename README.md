# ApexOBD (Torque Evo)

Android Kotlin + Jetpack Compose OBD-II HUD, diagnostics, data logging, and GPS trip tracking app.

## Key Features
- **Torque Evo HUD Canvas:** Pure `#000000` OLED + `#00A8FF` electric blue borderless gauges across `N` swipeable dynamic pages (`HorizontalPager`) with zero persistent navigation chrome while driving.
- **Dynamic Component Builder:** Add any Mode `01`, GPS, calculated (`HP`, `Torque`, `Boost`), or UDS Mode `22` sensor in `Analog Dial`, `Semi-Digital`, `Digital`, `Bar`, or `Chart` style (`1x1`, `2x1`, `2x2`), reorder/resize/morph in Edit Mode, and add/delete custom pages.
- **Google Mini-Map & HUD Mirror:** Live GPS mini-map widget (uses Google Maps Compose when `MAPS_API_KEY` is configured in `local.properties`, with an automatic dark vector GPS trail fallback otherwise) and horizontal `HUD Mirror Mode`.
- **Wi-Fi ELM327 + Simulator:** Connects to `192.168.0.10:35000` bound directly to the Android Wi-Fi `Network` (even when cellular data is on), plus a built-in `SimulatedObdTransport` for offline testing.
- **Full Diagnostics & Logging:** Modes `01`–`0A`, Freeze Frame (`02`), DTC scan & safe Mode `04` clear, VIN (`09`), UDS Mode `22` + Torque CSV import, `0–100 km/h` timer, and foreground session recording with CSV/JSON export.

## Build & Run
1. Ensure JDK 17 and Android SDK (Platform 35) are configured (`sdk.dir` in `local.properties`).
2. *(Optional for live Google Map tiles)* Add your Google Maps Android SDK key to `local.properties`:
   ```properties
   MAPS_API_KEY=your_google_maps_android_key
   ```
3. Run core protocol & formula tests:
   ```bash
   ./gradlew :obd-core:test
   ```
4. Build debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```
