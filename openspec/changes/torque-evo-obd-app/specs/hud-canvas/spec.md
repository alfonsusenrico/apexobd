# hud-canvas Specification

## Purpose
2-page driving UX: static/summary Home Menu (`#07090D`) and blank-default pure `#000000` zero-button N-page HUD Canvas with hold-tap hidden context menu, `8×4`/`4×8` Grid Snap + Freeform XY drag, dual optional Maps (`MapLibre Offline` / `Google Maps Online` / `Track Only`), and automotive-refined gauges.

## ADDED Requirements

### Requirement: 2-Page Architecture (Static Home Menu + Blank-Default Zero-Button HUD Canvas)
The application SHALL separate Page 1 (`Home Menu` showing only static summary data and vehicle management with live OBD polling paused while idle on Home) from Page 2 (`HUD Canvas` starting blank with zero persistent chrome and revealing its menu only on long-press anywhere).

#### Scenario: Blank default HUD and hold-tap context menu
- **WHEN** the user opens the HUD Canvas for the first time and long-presses anywhere on the screen
- **THEN** no default widgets or visible buttons clutter the screen, and the hidden context menu opens with `+ Add New Component`, `Move / Edit Components`, `Toggle Grid Snap`, `+ Add New Page`, persisted `Pause Fuel Avg`, and `← Back to Home Menu`

### Requirement: Proportional Grid-Snap & Freeform XY Component Layout
The HUD Canvas SHALL lay out components proportionally across `8×4` (Landscape) / `4×8` (Portrait) snap cells when Grid Snap is active, and SHALL support freeform XY pixel positioning when Grid Snap is toggled off, using horizontal baseline `value + inline unit` automotive typography across Radial Dial, Semi-Arc, Digital Tile, Line Bar, Area Chart, and Special Widgets (Available PIDs & Derived Sensors only).

#### Scenario: Filtering picker to supported vehicle telemetry
- **WHEN** connected to an ECU whose bitmask supports 16 Mode `01` PIDs
- **THEN** the component picker offers only those 16 Mode `01` PIDs plus derivable calculated sensors and phone GPS/IMU sensors

### Requirement: Dual Optional Maps, External Navigation, and HUD Mirror Mode
The HUD Mini-Map and Home Last Run card SHALL support configurable map backends (`MapLibre Offline Vector` with local bounding-box & zoom downloader, optional `Google Maps Online` using `MAPS_API_KEY`, or `OLED Track Only`), a one-tap external `Navigate ↗` intent, and horizontal `HUD Mirror Mode`.

#### Scenario: Dual Wi-Fi OBD and offline/online map operation
- **WHEN** the user selects `MapLibre Offline` or `Google Maps Online` while connected to Wi-Fi ELM327
- **THEN** OBD sockets bind to Wi-Fi while online maps request simultaneous cellular data or offline tiles render cleanly without API failures
