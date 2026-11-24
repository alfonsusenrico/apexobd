# Proposal: Tight HUD Grid, Direct On-Component Gestures, Legibility, and 99.99 KPL Cap

## Summary
Upgrade the HUD canvas layout density, direct manipulation UX, widget label/unit legibility, RPM radial gauge typography, and KPL display ceiling (`v1.2.2`, `versionCode = 7`).

## Motivation
- Current maximum grid density (`12×6`) leaves coarse snapping steps and wide gaps when arranging dense multi-gauge automotive HUDs.
- Resizing widgets currently requires opening the component modal menu; users expect direct 4-side edge-arrow resize handles and hold-middle drag-to-move with a visible grid in Edit/Free mode.
- Overlapping widgets can block touch selection of underlying components unless tapping or cycling brings them to the front and persists z-order.
- In compact tiles displaying `XX.XX` values (e.g. `14.25`, `99.99`), component titles and unit labels shrink below comfortable glanceable size; all 4 RPM radial gauges also clamp the `RPM` label in raw pixels (`8..15 px`), making it tiny on high-DPI phones.
- Instant and average KPL displays cap at `99.90` instead of `99.99` during true DFCO coasting.

## Scope
1. Add `FINE_16X8 (16×8)` and `ULTRA_24X12 (24×12)` to `HudGridScale`, tighten cell gaps/padding for dense grids, and add a quick grid-scale cycle pill to the Edit Mode bar.
2. Show the grid overlay in Edit Mode (both tile-snap and free modes), add 4 direct side resize arrow handles (`◄`, `►`, `▲`, `▼`) on the selected widget, require hold-in-middle drag to move, and resolve overlapping widget selection via click-to-front + persisted `sortOrder` + layer-cycle pill.
3. Raise minimum label/unit font sizes and contrast across compact widgets (`TorqueEvoWidgets.kt`), auto-placing the unit in the top header row when narrow tiles hold `XX.XX` values.
4. Convert `RPM` label and scale numeral sizing in all 4 RPM radial gauges (`RpmRadialWidgets.kt`) from raw pixels to density-independent `dp.toPx()`.
5. Raise the KPL display ceiling from `99.9` to `99.99` across `obd-core` and `app` while keeping underlying distance/fuel accumulation exact.
6. Bump app version to `1.2.2` (`versionCode = 7`).

## Approval
- Approved by owner in session on 2026-11-24.
