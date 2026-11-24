## ADDED Requirements

### Requirement: Ultra-Fine HUD Grid Scales and Visible Edit Grid
The HUD canvas MUST support fine-grained `16×8` (`FINE_16X8`) and `24×12` (`ULTRA_24X12`) grid scales with tighter cell gaps and outer padding, and MUST render visible grid guide lines whenever Edit Mode is active regardless of snap mode.

#### Scenario: User switches to Ultra 24x12 grid in Edit Mode
- **WHEN** the user enables Edit Mode and selects `Ultra (24×12)` grid scale
- **THEN** the canvas renders a `24×12` visible dashed grid with compact cell gaps and allows components to be positioned and sized at single-cell precision

### Requirement: Direct On-Component Edge-Arrow Resize and Hold-Middle Move
In Edit Mode, the selected HUD component MUST display resize arrow handles on all four sides (`LEFT`, `RIGHT`, `TOP`, `BOTTOM`) that resize the component directly when dragged, and holding the middle of a component MUST allow dragging it freely across the canvas without opening a modal menu.

#### Scenario: User drags a side resize arrow
- **WHEN** the user drags the left, right, top, or bottom arrow handle of the selected component
- **THEN** the component previews the new grid bounds live and commits the clamped origin and span atomically on release

#### Scenario: User holds the middle of a component to move
- **WHEN** the user presses and holds the center area of a component in Edit Mode and drags
- **THEN** the component lifts above sibling widgets and moves to the dropped position

### Requirement: Overlapping Component Clickability and Layer Promotion
Overlapping components MUST remain selectable and editable; tapping an overlapped component MUST select it and promote it to the front layer (`sortOrder` and `zIndex`), and Edit Mode MUST provide a layer-cycle control to reach fully covered components.

#### Scenario: Tapping a partially overlapped component
- **WHEN** two components overlap and the user taps the partially covered component in Edit Mode
- **THEN** the tapped component becomes selected, renders above its neighbor, and persists its promoted front layer order

### Requirement: Glanceable Component Labels, Units, and Density-Scaled RPM Labels
Compact widgets displaying `XX.XX` values MUST preserve legible label and unit sizes (`>= 9.5 sp` with high contrast) by placing the unit in the top header row when narrow tiles would otherwise compress the value row, and all four RPM radial gauge styles MUST scale their `RPM` label using density-independent pixels (`dp.toPx()`).

#### Scenario: Compact digital tile displays 99.99 km/L
- **WHEN** a narrow `DigitalReadoutWidget` displays a two-digit two-decimal value such as `99.99` with unit `km/L`
- **THEN** the unit renders in the header row beside the label while the digits occupy the full width of the value row without shrinking the label below legible size
