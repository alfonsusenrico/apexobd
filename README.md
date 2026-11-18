# ApexOBD

Android Kotlin + Jetpack Compose OBD-II HUD, diagnostics, data logging, and GPS trip tracking application.

## Key Features
- **Torque Evo HUD Dashboard:** Pure `#000000` OLED + `#00A8FF` electric blue borderless cockpit gauges across swipeable custom pages (`HorizontalPager`) with zero persistent navigation chrome while driving.
- **Dynamic Component Builder:** Add Mode `01`, GPS, calculated (`HP`, `Torque`, `Boost`, `KPL`), or UDS Mode `22` sensors rendered as `Analog Dial`, `Semi-Digital`, `Digital`, `Bar`, `Segmented Bar`, `Chart`, or `Mini-Map`, with freeform XY or grid drag-and-drop and visual theme presets.
- **Multi-Vehicle Profiles & Refuel Calibration:** Per-vehicle engine specs, drivetrain/Cd×A settings, supported PID caching, fuel refuel checkpoints, DTE range estimation, and scoped trip logs.
- **Offline Vector & Track Maps:** Built on MapLibre Compose (`OpenFreeMap` styles + `.pmtiles` offline archive import + region frame downloads) plus `OLED Track Only` vector trail mode with zero API keys required.
- **Multi-Mechanism OBD2 Adapter Support:** Connects via **Wi-Fi (TCP/IP)** with dual cellular data keepalive, **Bluetooth Classic (SPP/RFCOMM)**, **Bluetooth Low Energy (BLE GATT)**, or **USB-Serial (OTG)** (`FTDI`, `CP210x`, `CH340`, `CDC-ACM`).
- **Diagnostics, Deep Scan & Shareable Route Cards:** Modes `01`–`0A`, Freeze Frame (`02`), DTC scan & Mode `04` clear, VIN (`09`), Torque CSV PID import, read-only UDS/Mode `22` deep ECU discovery dump, and customizable route card PNG composer.

## Build & Run
1. Ensure JDK 17 and Android SDK (Platform 35) are configured (`sdk.dir` in `local.properties`).
2. Run core protocol and app JVM unit tests:
   ```bash
   ./gradlew :obd-core:test :app:testDebugUnitTest
   ```
3. Run Android lint and build debug APK:
   ```bash
   ./gradlew :app:lintDebug :app:assembleDebug
   ```
