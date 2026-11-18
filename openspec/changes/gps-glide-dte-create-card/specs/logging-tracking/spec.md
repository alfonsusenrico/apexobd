# logging-tracking Specification

## Purpose

Delivers a persistent Route Card creator (`Create Card`) with asynchronous preview loading, customizable background photos, social-ready `1:1`, `4:3`, and `9:16` layouts, `60%` opacity surrounding map road context, and an explicit Share-vs-Download `Export` workflow.

## ADDED Requirements

### Requirement: Persistent Create Card Workflow & Multi-Aspect Layout
The app SHALL label the route card launcher `Create Card`, show a loading indicator while telemetry samples or previews generate, persist all chosen `TripCardConfig` options across launches, support adding a custom background image on the fly, and adapt card composition cleanly across `1:1` (`1080x1080`), `4:3` (`1440x1080`), and `9:16` (`1080x1920`).

#### Scenario: Re-opening Create Card after customizing layout and background
- **WHEN** a user selects `9:16`, picks a custom background image, disables vehicle specs, picks custom metrics, and later opens `Create Card` on another saved run
- **THEN** the previously saved aspect ratio, toggles, background preference, and metrics are pre-applied immediately without reconfiguring

#### Scenario: Slow trip load or card re-render shows loading feedback
- **WHEN** the user taps `Create Card` or changes layout/map/photo options while bitmap rendering is in progress
- **THEN** a visible progress animation indicates active generation until the updated card preview appears

### Requirement: Surrounding Area Roads Overlay at 60% Opacity
When enabled by the user, `Create Card` SHALL render surrounding map roads covering the geographic area spanned by the tracked path at `60%` opacity beneath the glowing tracked route polyline, falling back cleanly when offline map data or network is unavailable.

#### Scenario: Surrounding roads enabled over a card background
- **WHEN** `showSurroundingRoads` is enabled for a route with `>=2` GPS points
- **THEN** street/road lines across the bounding route area render at `60%` opacity (`alpha ~153`) while the recorded route stands out above them with its electric-blue glow and crisp core

#### Scenario: Offline environment with no cached map tiles for the route region
- **WHEN** `MapSnapshotter` cannot load tiles or times out
- **THEN** the card still completes cleanly displaying the glowing tracked route trace without crashing or hanging

### Requirement: Dual Export Action for App Sharing and Gallery Download
The primary action inside `Create Card` SHALL be labeled `Export` and prompt the user to either `Share to Apps` (`ACTION_SEND` chooser) or `Download PNG` (saving directly into public `Pictures/ApexOBD` storage).

#### Scenario: User chooses Download PNG on Export
- **WHEN** the user taps `Export` and selects `Download PNG`
- **THEN** the rendered PNG at the chosen aspect ratio (`1:1`, `4:3`, or `9:16`) is written to public `Pictures/ApexOBD` with user confirmation, while `Share to Apps` opens the system share sheet
