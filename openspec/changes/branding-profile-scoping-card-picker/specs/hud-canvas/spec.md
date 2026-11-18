# hud-canvas Specification

## Purpose
Swipe-locked Home modal dialogs with explicit close & dirty-form protection, 1-tap vehicle profile switcher, AEM-inspired slanted white `APEXOBD` header decal, selectable heading-rotated map cursor icons, and adaptive OLED launcher icon.

## ADDED Requirements

### Requirement: Swipe-Locked Modal Forms with Explicit Close Controls
Home modal bottom sheets (`VehicleProfileSheet`, `RefuelLogSheet`, `FaultCodesSheet`, `RunLogsAndMapsSheet`, `EcuPidsSheet`, `SettingsSheet`) SHALL disable accidental swipe-to-dismiss and outside-scrim dismissal, providing explicit close controls and prompting before discarding unsaved vehicle profile edits.

#### Scenario: Scrolling down inside the Add/Edit Car Profile form
- **WHEN** the user drags downward near the top of `VehicleProfileSheet` or taps beside the dialog
- **THEN** the sheet stays open without discarding partially entered car fields, and closing via `✕` or Back prompts before discarding unsaved edits

### Requirement: 1-Tap Vehicle Profile Switcher on Home Menu
`HomeMenuScreen` SHALL provide a 1-tap vehicle profile switcher listing all saved cars and keeping summary cards, refuel status, last run map preview, saved run count, and offline PID cache synchronized with the selected vehicle (blocking switches while a trip recording is active).

#### Scenario: Switching between Avanza K3VE and Avanza 1.5G MT
- **WHEN** the user taps an inactive car pill on Home while idle
- **THEN** the tapped vehicle immediately becomes active without reordering the pill bar, and Home summary stats, refuel history, last run map, and ECU PID list update to that vehicle's records

### Requirement: Contextual In-Feature Settings Controls
Map display settings (`Map Style`, `Camera Mode`, `Map Cursor`, `High-Contrast Roads`) SHALL be accessible directly inside `RunLogsAndMapsSheet`, HUD display settings (`Grid Scale`, `Gauge Smoothing`, `HUD Mirror`) SHALL be accessible inside the HUD Canvas Appearance dialog, and the Home connection status badge SHALL open adapter connection settings directly.

#### Scenario: Adjusting map cursor and style from Runs & Maps without opening global Settings
- **WHEN** the user opens `Run Logs & Maps` and selects `Map Options`
- **THEN** map style, camera perspective, cursor icon, and road contrast can be changed in place

### Requirement: Selectable Heading-Rotated Map Cursor Icons
`DualMapCanvas` and `SettingsSheet` SHALL allow choosing between `Sport Arrow` (default), `Top-Down Car`, and `Classic Dot` location cursors and SHALL rotate directional icons (`Sport Arrow`, `Top-Down Car`) in lockstep with smoothed heading across both MapLibre and `OLED Track Only` modes.

#### Scenario: Changing map cursor to Top-Down Car while navigating
- **WHEN** the user selects `Top-Down Car` in Map settings
- **THEN** the live location marker renders a crisp top-down car silhouette oriented to the vehicle's driving heading

### Requirement: Slanted White APEXOBD Decal & Adaptive Android App Icon
`HomeMenuScreen` and `TripCardRenderer` SHALL share a classic motorsport slanted white `APEXOBD` decal renderer, and `AndroidManifest.xml` SHALL declare a dark OLED Android Adaptive Launcher Icon (`@mipmap/ic_launcher` / `@mipmap/ic_launcher_round`) instead of the system Android robot placeholder.

#### Scenario: Viewing Home header and Android app launcher
- **WHEN** the app is installed and launched on Android (`API 26+`)
- **THEN** the home launcher displays the custom `ApexOBD` OLED adaptive icon and the Home top bar renders the slanted white `APEXOBD` decal
