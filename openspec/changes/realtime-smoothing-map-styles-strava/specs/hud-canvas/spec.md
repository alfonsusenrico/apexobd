# hud-canvas Specification

## Purpose
HUD connection banner, vertical page navigation, maximized compact radial gauge geometry, configurable MapLibre styles/camera pitch, and lockstep map puck/camera smoothing.

## ADDED Requirements

### Requirement: Auto-Hiding Flashing Top OBD Connection Indicator
`HudCanvasScreen` SHALL render a compact top-center status pill that flashes while OBD2 is `Disconnected`, `Connecting`, or `Reconnecting`, briefly confirms initial connection, and automatically disappears once live telemetry is streaming.

#### Scenario: Connecting to OBD2 adapter then receiving live gauges
- **WHEN** the user opens HUD Canvas while the ELM327 adapter is connecting or disconnected in real mode
- **THEN** a pulsing top banner indicates adapter status (`FINDING OBD2 ADAPTER` / `OBD2 NOT CONNECTED`) and automatically dismisses shortly after `ConnectionState.Connected` is reached

### Requirement: Vertical Swipe HUD Canvas Pager
`HudCanvasScreen` SHALL use `VerticalPager` for multi-page navigation in both portrait and landscape orientations so swipe gestures match the vertical edge page indicator column.

#### Scenario: Switching HUD pages in landscape or portrait
- **WHEN** multiple HUD pages exist and Edit Mode is inactive
- **THEN** vertical swipe gestures smoothly transition between pages and synchronize the vertical right-edge indicator

### Requirement: Maximized Bottom-Reclaiming Radial Dial Sizing
`AnalogDialWidget` SHALL compute circle radius from the actual `250°` arc vertical and horizontal bounds (`w / 2.10`, `h / 1.68`) and shift circle center downward to eliminate dead bottom whitespace and enlarge gauge readouts on compact grid tiles.

#### Scenario: Rendering RPM Radial dial inside compact rectangular tile
- **WHEN** `AnalogDialWidget` renders on a compact `4×4` or `3×3` tile
- **THEN** no unused bottom buffer wastes vertical rows and the gauge ring scales up to fill the tile cleanly

### Requirement: Configurable GPS Map Style Presets, Camera Modes & Synchronous Interpolation
`DualMapCanvas` and `MiniMapWidget` SHALL support selectable map style presets (`Dark Contrast`, `Fiord Deep Navy`, `Liberty 3D`, `Bright Nav`, `Positron Minimal`, `OLED Track Only`), camera tilt/zoom modes (`3D Nav 45°`, `Low Chase 55°`, `Flat 0°`), and lockstep 60 Hz puck-and-camera interpolation with shortest-angle bearing wrap.

#### Scenario: Driving and turning with 60 Hz synchronous map camera and selectable style
- **WHEN** the vehicle moves and turns between 500–1000 ms GPS callbacks under a selected Map Style and Camera preset
- **THEN** both the puck GeoJSON marker and the map camera interpolate smoothly in lockstep without mid-animation stutter or desync
