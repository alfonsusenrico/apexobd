# diagnostics Specification Delta

## ADDED Requirements

### Requirement: Universal Multi-ECU DTC Scan Liveness and Toyota Mode 2106 Readiness Fallback
`DtcDecoder` SHALL decode both single-frame and multi-frame ISO-TP responses for Modes `03`, `07`, and `0A`, fall back to Toyota Engine ECM Mode `2106` (`61 06 <A> <B> <C> <D>`) when standard Mode `0101` is unsupported, and track `ecuResponded` across all diagnostic queries (including a `0100` liveness check when all DTC modes return `NO DATA`) so vehicles with zero fault codes are never misreported as offline.

#### Scenario: 2014 Avanza K3-VE with 0 confirmed codes and Mode 2106 readiness
- **WHEN** `03` and `0A` return `NO DATA`, `07` returns `47 00`, `0101` returns `NO DATA`, and `7E0 2106` returns `00C | 0:61060007E100 | 1:03060000000000`
- **THEN** `DtcDecoder.scanAll` reports `ecuResponded = true`, `0` confirmed/pending/permanent DTCs, `milOn = false`, and 7 ready monitors without showing an adapter/ECU offline error
