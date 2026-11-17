# Design: ApexOBD (Torque Evo) Android OBD-II App

## Context
Owner approved the revised 2026-11-17 **2-Page Architecture**:
1. **Page 1 — Home Menu (`#07090D`, Static Summary Only, Zero Live OBD Polling):** Distinct elevated Vehicle Profile card (`Gasoline / Diesel / Electric`, displacement, weight, tank capacity, est. fuel left, odometer), 4-cell static strip (`All time avg`, `Odometer`, `Est. Range`, `Best 0–100`), primary `Open HUD Canvas` launcher, compact Last Saved Run preview map + external `Navigate ↗`, 2×2 action modules (`Refuel Log`, `Fault Codes`, `Run Logs & Maps`, `ECU PIDs`), and distinct Settings card.
2. **Page 2 — Pure `#000000` Blank-Default Zero-Button HUD Canvas:** Opens blank on Page 1; swipe horizontally across dynamic pages; hold-tap anywhere reveals the hidden Context Menu (`+ Add New Component`, `Move / Edit Components`, `Toggle Grid Snap`, `+ Add New Page`, persisted `Pause Fuel Avg`, `← Back to Home Menu`). Components follow automotive HMI readability with horizontal baseline `value + inline unit` pairing, graduated radial ticks with redline warning arc, flat cockpit semi-arcs, linear hairline range bars, and SVG gradient area charts.

## Numbered Architecture Decisions

1. **Two-Module Gradle Architecture (`:obd-core` Pure Kotlin JVM + `:app` Android Compose)**
   - Keeps ELM327 state machine, low-latency blocking socket transport, Mode `01` bitmask discovery, multi-sensor derived fuel/torque/efficiency equations, UnitConverter, DTC decoder, UDS Mode `22` evaluator, and Simulator in `:obd-core` for sub-second JVM unit testing.

2. **Wi-Fi OBD Socket Binding + Simultaneous Cellular Data Request**
   - `WifiNetworkBinder` binds the ELM327 TCP `SocketFactory` directly to `TRANSPORT_WIFI`, and holds an optional `NetworkRequest` for `TRANSPORT_CELLULAR` + `NET_CAPABILITY_INTERNET` while connected so online maps/apps stay reachable over cellular when the Wi-Fi OBD adapter has no internet.

3. **Dual Optional Map Architecture (`Offline MapLibre` + `Optional Google Maps` + `Track-Only`)**
   - *Default:* **MapLibre Native Android** + **OpenFreeMap / local `.pmtiles`** (zero API key, offline bounding-box & zoom region downloader with MB/tile preview, hardware-accelerated dark vector tiles).
   - *Optional:* **Google Maps Compose** when `MAPS_API_KEY` is configured and Cellular Data is up alongside Wi-Fi OBD, plus **Track Path Only (`#000000`)** low-battery mode and one-tap external `Navigate ↗` intent.

4. **Multi-Sensor Derived Telemetry Engine & Persisted Fuel Checkpoints**
   - Uses `015E` fuel rate when present; otherwise fuses `MAF (0110)` or dynamic `Load`-aware Speed-Density (`MAP 010B`, `RPM 010C`, `IAT 010F`, `Baro 0133`), `Lambda (0144)` or `STFT/LTFT (0106–0109)`, Coolant warmup enrichment (`0105`), WOT enrichment, Gasoline/Diesel/Electric fuel physics, and DBW-safe DFCO (`Rel TPS 0145 <= 1.5%` + high vacuum `MAP < 0.28 * Baro`).
   - Torque & HP combine native `0162/0163` when supported or Willans-line BSFC + Timing Advance (`010E`) spark efficiency − mechanical FMEP, cross-blended with longitudinal road dynamics (`weight`, `IMU accel`, `GPS grade`, air density from `Baro` + `IAT`).
   - Refuel Checkpoints (`RefuelLogEntity`) compute refuel-cycle distance & efficiency independently of OBD `All time avg` and per-run trips while updating estimated remaining fuel inside tank capacity.

5. **Zero-Waste Proportional Canvas (`8×4` Landscape / `4×8` Portrait + Freeform XY)**
   - When `gridSnap` is enabled, components fill proportional `1fr` cells on an `8×4` (Landscape) / `4×8` (Portrait) grid; when toggled off, coordinates persist as normalized/dp offsets `(freeformX, freeformY)` for pixel-free placement. Active OBD polling runs on HUD Canvas or during an active Run Recording, and pauses when parked idle on Home Menu to save phone battery.
