# hud-canvas Specification

## Purpose
2-page driving UX: static/summary Home Menu (`#07090D`) and blank-default pure `#000000` zero-button N-page HUD Canvas with hold-tap canvas & component edit menus, dynamic multi-scale Grid Snap (`6×3`/`8×4`/`10×5`/`12×6`) + Freeform XY drag, offline-first MapLibre vector maps + OLED Track Only mode, and automotive-refined gauges.

## ADDED Requirements

### Requirement: 2-Page Architecture (Static Home Menu + Blank-Default Zero-Button HUD Canvas)
The application SHALL separate Page 1 (`Home Menu` showing only static summary data and vehicle management with live OBD polling paused while idle on Home) from Page 2 (`HUD Canvas` starting blank with zero persistent chrome, opening page controls on hold-tap over empty canvas and opening a Component Edit Menu on hold-tap over any placed component).

#### Scenario: Blank default HUD and direct component hold-tap menu
- **WHEN** the user long-presses empty HUD Canvas space, the hidden canvas menu opens with `+ Add New Component`, `Move / Edit Components`, `Toggle Grid Snap`, `Grid Scale`, `+ Add New Page`, persisted `Pause Fuel Avg`, and `← Back to Home Menu`
- **WHEN** the user long-presses an existing placed component on the HUD Canvas (including non-interactive `Map (Live GPS)`)
- **THEN** that component is selected and its dedicated Edit Component menu opens directly with visual display type options (for numeric sensors), dynamic tile size (`1×1..cols×rows`), move/reorder actions, and component removal

### Requirement: Sensor-Landscape Lock & Dynamic Tile Sizing
The HUD Canvas SHALL lock to `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` and restore natural orientation on return to Home, SHALL support dynamic tile sizing (`1×1..cols×rows`) across grid scales (`6×3`, `8×4`, `10×5`, `12×6`), SHALL support drag-and-drop onto arbitrary `(col, row)` tiles without left-to-right auto-compaction or onto freeform XY coordinates, and SHALL support vehicle profile deletion.

#### Scenario: Filtering and dynamically sizing telemetry in Component Studio
- **WHEN** the user opens `+ Add New Component` from the HUD Canvas context menu
- **THEN** a fullscreen non-dismiss-outside Landscape Component Studio opens displaying an A–Z scrollable sensor list on the left (including `Map (Live GPS)` under `M`; available sensors enabled with realtime value and unit; ECU-unsupported sensors disabled, dimmed, and marked `Unavailable`; no PID hex codes or short label clutter) above Visual Style and dynamic `1..cols × 1..rows` interactive grid sizing controls, alongside a live proportionally scaled widget preview and `+ Add to Canvas` action on the right

### Requirement: Offline Vector Maps and HUD Mirror Mode
The HUD Mini-Map and Home Last Run card SHALL use `MapLibre Offline Vector` (`z5–z14` pan/zoom region downloads and local `.pmtiles` archives) or `OLED Track Only` without external Google Maps navigation buttons or `MAPS_API_KEY`, SHALL reset GPS trails across sim/real switches or `> 500 m` jumps while showing live Mini-Map trails only during active recording, and SHALL support `HUD Mirror Mode`.

#### Scenario: Dual Wi-Fi OBD and offline vector map operation
- **WHEN** the user views the HUD Mini-Map or Home Last Run card with or without cellular connectivity
- **THEN** OBD sockets optionally keep cellular data alive alongside Wi-Fi while local `.pmtiles` regions or OLED track lines render cleanly without external navigation intents or synthetic jump lines
