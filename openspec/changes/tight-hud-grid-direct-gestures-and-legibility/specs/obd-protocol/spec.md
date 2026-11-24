## ADDED Requirements

### Requirement: Fuel Economy Display Ceiling
Instantaneous and averaged KPL display outputs MUST cap at `99.99` km/L during deceleration fuel cut-off (DFCO) or ultra-high-efficiency readings while leaving underlying accumulated distance and fuel totals un-clamped.

#### Scenario: True DFCO overrun caps instant KPL display at 99.99
- **WHEN** the vehicle enters verified DFCO coasting with near-zero fuel flow
- **THEN** `instantKpl` reports `99.99` km/L while cumulative fuel and distance counters remain exact
