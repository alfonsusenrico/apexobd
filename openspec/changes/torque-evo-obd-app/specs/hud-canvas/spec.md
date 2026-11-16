# hud-canvas Specification

## Purpose
Zero-navigation-chrome, swipeable N-page Torque Evo HUD canvas with dynamic user-created components, drag-and-drop editing, and HUD Mirror Mode.

## ADDED Requirements

### Requirement: Zero-Chrome Swipeable N-Page Driving Canvas
The application SHALL launch directly onto a pure `#000000` OLED fullscreen `HorizontalPager` displaying `N` user-configured pages without persistent bottom navigation bars or top tabs, allowing horizontal swiping between pages and dynamic page creation/deletion.

#### Scenario: Swiping across user-configured driving pages
- **WHEN** the user swipes horizontally on the HUD canvas
- **THEN** the pager transitions smoothly between Page 1 (Tracking), Page 2 (DTC Checker), Page 3 (Performance HP/Torque), and any additional user-created pages

#### Scenario: Adding a new blank page
- **WHEN** the user selects "+ Add New Blank Page" from the canvas menu
- **THEN** a new page is persisted in Room and immediately opened as the active swipe page

### Requirement: Dynamic Component Catalog, Display Types, and Sizes
Each widget on any page SHALL be configurable by Sensor/Feature, Display Type (`Analog Dial`, `Semi-Digital`, `Digital`, `Bar`, `Chart`, `Special Widget`), and Size (`1x1`, `2x1`, `2x2`, `4x2`), rendered in the Torque Evo `#000000` + `#00A8FF` borderless aesthetic with `tabular-nums` typography.

#### Scenario: Adding and morphing a component
- **WHEN** the user opens the Component Picker, picks `Intake Flow / MAF`, `Chart`, and `2x1`, or taps `◐` in Edit Mode to cycle display types
- **THEN** the widget renders immediately on the active page and persists across app restarts

#### Scenario: Drag-and-drop reordering and resizing in Edit Mode
- **WHEN** the user activates Edit Mode, drags a widget to a new slot, or cycles its size (`⤢`)
- **THEN** the page layout updates immediately and saves the new order and span to Room

### Requirement: Embedded Google Mini-Map and HUD Mirror Mode
The HUD SHALL provide a `Google Mini-Map` component that renders live GPS position and route trail, and SHALL support toggling horizontal `HUD Mirror Mode` for windshield reflection as well as Landscape/Portrait orientation and screen wake-lock while connected.

#### Scenario: HUD Mirror Mode reflection
- **WHEN** the user enables HUD Mirror Mode
- **THEN** the entire HUD canvas flips horizontally (`scaleX = -1f`) while preserving live gauge updates
