# hud-canvas Specification

## ADDED Requirements

### Requirement: VSync `withFrameNanos` Driver & Deferred Per-Sensor State Reads
`HudCanvasScreen` SHALL drive UI visual smoothing on hardware display VSync via `withFrameNanos`, SHALL isolate live channel updates into per-sensor `State<Double?>` slots (`HudLiveValueStore`) plus O(1) cached `catalogItemFor` metadata lookup so root `HudCanvasScreen`, `VerticalPager`, `MiniMapWidget`, and idle tiles remain un-invalidated during 60–120 Hz telemetry animations.

#### Scenario: Rapid RPM and Load updates do not trigger `HudCanvasScreen` root or `MiniMapWidget` recomposition
- **WHEN** `rpm`, `engine_load`, and `kpl_instant` animate at VSync (`withFrameNanos`) on Page 1 alongside `MiniMapWidget`
- **THEN** only the updating sensor widgets' Draw phases and changed formatted digit leaves update while `MiniMapWidget` and unchanged tiles experience zero recompositions

### Requirement: Draw-Phase `Canvas` Gauge Rendering
`AnalogDialWidget`, `SemiDigitalArcWidget`, `BarGaugeWidget`, `SegmentedBarGaugeWidget`, and `SparklineChartWidget` SHALL read live telemetry providers inside Draw/Canvas scopes (and render `SegmentedBarGaugeWidget` segments within a single `Canvas` rather than per-segment `Box` subtrees), keeping outer `BoxWithConstraints` layout and card chrome cached across live frames.

#### Scenario: `SegmentedBarGaugeWidget` animates live speed without recomposing 18 Box children
- **WHEN** `gps_speed` updates continuously inside `SegmentedBarGaugeWidget`
- **THEN** all `12`/`18` gradient boxes draw inside a single `Canvas` draw pass synchronized with VSync while numeric digits update in an isolated leaf composable
