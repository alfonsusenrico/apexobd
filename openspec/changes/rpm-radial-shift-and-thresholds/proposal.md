# Proposal: Dedicated RPM Radial Tachometers, Shift Lamp, and Optional Per-Widget Thresholds

## Approval
- Approved by owner on 2026-11-20 after interactive Canvas preview iterations (`rpm_radial_preview_v3.png` with Option C shift dot repositioned inside the center chrono ring).

## Why
Generic radial gauges lack RPM-specific motorsport readability (numbered x1000 scale, permanent vehicle redline zone, and progressive shift lamp). Drivers also benefit from optional per-widget warning and critical thresholds on any metric (such as high coolant temperature or low battery voltage) without adding clutter when unconfigured.

## What Changes
- Add 4 dedicated selectable RPM radial tachometer models (`RPM_TACH_GT`, `RPM_TACH_F1_LEDS`, `RPM_TACH_CHRONO`, `RPM_TACH_HALO`) for `rpm` and `cluster_rpm` widgets, preserving the active Electric Blue accent sweep with a permanent Red redline zone.
- Add an optional progressive shift lamp (Off -> Green -> Yellow -> Flashing Red, no text label) driven by user-configured shift RPM and vehicle redline.
- Add optional per-widget warning and critical thresholds with explicit `Above (>=)` / `Below (<=)` direction (`alertBelow` persisted via Room migration v7 -> v8) and unit-aware editing in the Hold-Widget edit menu.
- Render steady Amber warning and flashing Red critical overlays (border + beacon dot + digit tint) only when thresholds are explicitly configured.

## Non-Goals
- No automatic default warning thresholds on newly added widgets (all thresholds remain optional and off until configured).
- No audio buzzer or push notifications.
