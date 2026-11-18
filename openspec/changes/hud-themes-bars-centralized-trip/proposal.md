# Proposal

Approved by owner on 2026-11-17 after UI preview review (`ui-design-preview.md` and `preview-1`..`preview-4`).

## Why

The `Digital` and `Bar` HUD widgets looked nearly identical because both rendered a horizontal bottom bar, KPL tiles displayed verbose text labels and a wrapping rectangular `"Pause Idle"` box button instead of matching the Map circular shutter toggle, the HUD lacked selectable full-canvas themes (including the dimmed glassy aesthetic) and border visibility controls, and trip metrics ran automatically from app startup instead of following the manual Map Run Record trigger with an explicit save-or-discard confirmation on stop.

## What Changes

- Separate `Digital` (pure large tabular numeric tile without a bottom bar) from `Line Bar` (preserved smooth gradient bar) and add `Boxed Bar` (segmented LED level meter with warn/redline zones and min/mid/max scale).
- Add a persisted `showWidgetBorders` toggle in Settings and `HUD Canvas Menu` to hide resting card borders while keeping edit-mode drag/selection outlines active.
- Rename KPL components and catalogs strictly to `KPL · AVG`, `KPL · TRIP`, and `KPL · INST`, and convert the `KPL · AVG` idle-pause button into a bottom-right circular shutter-ring toggle matching `MiniMapWidget`.
- Introduce 7 selectable full-canvas HUD themes (`OLED Pure Blue`, `Glassy Dimmed`, `Motorsport Crimson`, `Pit-Lane Emerald`, `Cluster Amber`, `Carbon Titanium`, `Night Violet`) plus independent `Canvas Background` (`Match Theme`, `Glassy Dimmed`, `Pure Black`) and `Component Style` (`Themed`, `Clear Glass`) controls.
- Centralize all 5 trip metrics (`KPL · TRIP`, `Trip Fuel Used`, `Trip Range`, `Trip Time`, `Trip Avg Speed`) on the manual Map Run Record trigger so they remain idle on launch/OBD connect, record detailed summary and per-sample telemetry into Room during an active run, and prompt the user on Stop with a Trip Summary Save/Discard modal.

## Capabilities

### New Capabilities
- `hud-themes`: Full-canvas HUD theme templates, canvas backdrop overrides, clear vs themed card surfaces, and component border visibility preferences.

### Modified Capabilities
- `hud-canvas`: Distinct `Digital`, `Line Bar`, and `Boxed Bar` widget styles, `KPL · AVG` circular shutter toggle, and border/theme quick controls in HUD Canvas Menu.
- `logging-tracking`: Centralized Map-Record-triggered trip accumulation (`KPL · TRIP`, `Trip Fuel`, `Trip Range`, `Trip Time`, `Trip Avg Speed`) and Stop confirmation modal to save or discard local trip logs.

## Impact

- `obd-core`: Updated `DerivedSensorCatalog` labels (`KPL · AVG`, `KPL · TRIP`, `KPL · INST`), added unit tests if label assertions check short names.
- `app`: Updates to `ApexDatabase` (v4 -> v5 migration adding `avgSpeedKmh` & `isSaved` on `trip_sessions`), `AppPreferences`, `TorqueEvoTheme`, `TorqueEvoWidgets`, `HudCanvasScreen`, `HomeMenuScreen`, `HomeSheets`, and `HudViewModel`.
