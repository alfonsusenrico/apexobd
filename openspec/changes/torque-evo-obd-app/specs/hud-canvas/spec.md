# hud-canvas Specification

## Purpose
2-page driving UX: static/summary Home Menu (`#07090D`) and blank-default pure `#000000` zero-button N-page HUD Canvas with hold-tap hidden context menu, `8×4`/`4×8` Grid Snap + Freeform XY drag, dual optional Maps (`MapLibre Offline` / `Google Maps Online` / `Track Only`), and automotive-refined gauges.

## ADDED Requirements

### Requirement: 2-Page Architecture (Static Home Menu + Blank-Default Zero-Button HUD Canvas)
The application SHALL separate Page 1 (`Home Menu` showing only static summary data and vehicle management with live OBD polling paused while idle on Home) from Page 2 (`HUD Canvas` starting blank with zero persistent chrome and revealing its menu only on long-press anywhere).

#### Scenario: Blank default HUD and hold-tap context menu
- **WHEN** the user opens the HUD Canvas for the first time and long-presses anywhere on the screen
- **THEN** no default widgets or visible buttons clutter the screen, and the hidden context menu opens with `+ Add New Component`, `Move / Edit Components`, `Toggle Grid Snap`, `+ Add New Page`, persisted `Pause Fuel Avg`, and `← Back to Home Menu`

### Requirement: Sensor-Landscape Lock & Proportional Multi-Scale Grid Layout
The HUD Canvas SHALL automatically lock orientation to `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` while active (restoring natural orientation on return to Home), SHALL lay out components proportionally across user-selectable grid scale presets (`6×3`, `8×4`, `10×5`, `12×6`) when Grid Snap is active or support freeform XY pixel positioning when Grid Snap is toggled off, and SHALL support deleting non-sole `VehicleProfile` entries in Home management.

#### Scenario: Filtering and previewing telemetry in the fullscreen Component Studio
- **WHEN** the user opens `+ Add New Component` from the HUD Canvas context menu
- **THEN** a fullscreen non-dismiss-outside Landscape Component Studio opens displaying an A–Z scrollable sensor list on the left (available sensors enabled with realtime value and unit; ECU-unsupported sensors disabled, dimmed, and marked `Unavailable`; no PID hex codes or short label clutter) above Visual Style and Grid Scale-adapted Size selectors, alongside a live proportionally scaled widget preview and `+ Add to Canvas` action on the right

### Requirement: Dual Optional Maps, External Navigation, and HUD Mirror Mode
The HUD Mini-Map and Home Last Run card SHALL support configurable map backends (`MapLibre Offline Vector` with an interactive Google-Maps-style pan/zoom selection frame for `z5–z14` regions plus local `.pmtiles` archive import/URL loading, optional `Google Maps Online` using `MAPS_API_KEY`, or `OLED Track Only`), a one-tap external `Navigate ↗` intent, and horizontal `HUD Mirror Mode`.

#### Scenario: Dual Wi-Fi OBD and offline/online map operation
- **WHEN** the user selects `MapLibre Offline` or `Google Maps Online` while connected to Wi-Fi ELM327
- **THEN** OBD sockets bind to Wi-Fi while online maps request simultaneous cellular data or offline tiles render cleanly without API failures
