# logging-tracking Specification

## Purpose

Prevents unfixed GPS fallback coordinates from corrupting trip routes, repairs legacy partial-GPS routes such as `Session 14` during rendering, streamlines Route Card configuration controls, and ensures full viewport fit above Android navigation/gesture insets.

## ADDED Requirements

### Requirement: Valid-Fix GPS Logging & Legacy Sentinel/Teleport Filtering
`HudViewModel` SHALL persist `null` coordinates into `TelemetrySampleEntity` whenever `gps.hasRealFix` is `false`, and `TripCardRenderer` SHALL filter unfixed fallback sentinel coordinates (`-6.2088, 106.8456` when distant real coordinates exist) and isolated cross-region jumps (`> 0.15 deg`) so both new and historical partial-GPS runs plot their true road path.

#### Scenario: Historical morning session with initial Jakarta default recovers Surabaya route
- **WHEN** a trip contains initial `(-6.2088, 106.8456)` samples followed by thousands of points near `(-7.309, 112.678)`
- **THEN** `TripCardRenderer.extractCleanGpsPoints` strips the unfixed sentinel prefix and renders only the Surabaya path

### Requirement: Route Card Studio Refinements & Bottom Inset Compliance
The Create Route Card UI SHALL label aspect ratio chips strictly `"1:1"`, `"4:3"`, `"9:16"`, consolidate image picking into the single `"Custom Photo"` chip, provide a `Surrounding Roads` toggle with a `10%–60%` opacity slider (`default 25%`) and `Main Roads Only` checkbox (`default true`) blended via a seamless elliptical fade, and confine all sheet action controls above system navigation and gesture insets.

#### Scenario: Surrounding roads vignette and viewport insets fit without clipping
- **WHEN** a user opens `Create Route Card` on a `1080x2340` edge-to-edge device with `Surrounding Roads` enabled
- **THEN** surrounding roads fade seamlessly to `0%` opacity toward all borders with zero visible rectangular edge, and `"Cancel"` and `"Export"` sit completely above system bottom gesture bounds
