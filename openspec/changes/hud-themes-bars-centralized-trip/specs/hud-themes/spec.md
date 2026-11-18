# hud-themes Specification

## Purpose
Full-canvas HUD visual theming, independent backdrop and clear-card layer controls, and component border visibility settings.

## ADDED Requirements

### Requirement: 7 Selectable Pre-Template HUD Themes with Canvas & Card Layer Separation
The application SHALL persist and apply 7 HUD theme presets to both the full HUD Canvas background and component palettes, and SHALL support independent `Canvas Background` (`Match Theme`, `Glassy Dimmed`, `Pure Black`) and `Component Style` (`Themed`, `Clear Glass`) overrides.

#### Scenario: Applying Glassy Dimmed canvas with Clear Glass components
- **WHEN** the user selects `Glassy Dimmed` as canvas background with `Clear Glass` component style in Settings or HUD Canvas appearance controls
- **THEN** the entire HUD Canvas backdrop renders the dimmed midnight frosted gradient while telemetry tiles render with neutral translucent glass styling (`OLED Pure Blue`, `Glassy Dimmed`, `Motorsport Crimson`, `Pit-Lane Emerald`, `Cluster Amber`, `Carbon Titanium`, `Night Violet` available as presets)

### Requirement: Component Border Visibility Setting
The application SHALL persist a `showWidgetBorders` preference (`true` default) toggleable in both Settings and the `HUD Canvas Menu`, hiding resting component card borders when disabled while keeping drag-and-drop tile outlines and selection borders visible during edit mode.

#### Scenario: Toggling Component Borders Off
- **WHEN** the user disables `Component Borders` from the HUD Canvas Menu or Settings
- **THEN** resting HUD telemetry widgets render without outer rectangular borders against the active canvas theme, while entering `Move / Drag Components` mode still reveals tile grid lines and the active selection border
