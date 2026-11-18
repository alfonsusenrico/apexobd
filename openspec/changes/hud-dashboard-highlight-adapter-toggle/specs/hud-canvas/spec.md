# hud-canvas Specification

## Purpose
Highlight the HUD Dashboard as the primary centerpiece on Home (`Open HUD Dashboard`), compact the Last Saved Run preview strip, and relocate Settings to a top-right gear icon alongside an instant OBD2 adapter connection toggle.

## ADDED Requirements

### Requirement: HUD Dashboard Highlighted Centerpiece
`HomeMenuScreen` SHALL title the driving display feature **HUD Dashboard** and render the `HUD Dashboard` card as the primary highlighted centerpiece on Home with a plain dark obsidian surface, restrained accent border, and compact `Open HUD ->` button (without redundant kickers, theme summaries, widget count badges, or helper captions), while presenting `Last Saved Run` as a compact secondary summary strip.

#### Scenario: Viewing Home screen in portrait or landscape
- **WHEN** the user opens Home in portrait or landscape
- **THEN** the `Open HUD Dashboard` card occupies the focal centerpiece highlighting quick entry into the live HUD, while `Last Saved Run` sits cleanly as a compact route thumbnail strip rather than dominating the viewport

### Requirement: Top-Right Connection Toggle Pill & Settings Gear Icon
`HomeMenuScreen` SHALL remove the bottom `Settings` card and display both a tappable top-right OBD2 adapter connection status/mechanism control (`Wi-Fi`, `BT`, `BLE`, `USB`) and an adjacent `Settings` gear `IconButton` in the top header row.

#### Scenario: Opening adapter connection controls or global settings from Home header
- **WHEN** the user taps the top-right connection pill or the adjacent `Settings` gear icon
- **THEN** tapping the connection pill opens `AdapterConnectionSheet` to switch mechanisms or adapter targets in one tap, and tapping the gear icon opens `SettingsSheet` without scrolling to the bottom
