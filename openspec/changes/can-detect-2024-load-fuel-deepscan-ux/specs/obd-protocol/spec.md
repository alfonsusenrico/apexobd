# obd-protocol Specification

## Purpose
Reliable ELM327 CAN protocol detection across clone firmware strings and SAE `0104` Engine Load airflow/fuel estimation when `0110 MAF` and `010B MAP` are absent.

## ADDED Requirements

### Requirement: Resilient CAN Detection on Combined `ATDP` / `ATDPN` Responses
`Elm327Client.initialize` SHALL inspect all cleaned response lines across both `ATDP` and `ATDPN` for CAN protocol digits or `"CAN"` / `"15765"` substrings so CAN adapters never silently fall back to non-CAN sequential polling.

#### Scenario: Adapter returns `ATDPN = AUTO, ISO 15765-4 (CAN 11/500)`
- **WHEN** `ATDP` returns `"Auto"` and `ATDPN` returns `"AUTO, ISO 15765-4 (CAN 11/500)"`
- **THEN** `ElmHandshakeInfo.isCanProtocol` evaluates to `true`, enabling `ATST19` and single-frame CAN batching

### Requirement: Engine Load Airflow Fallback for Fuel & Power Without MAF/MAP
When neither `0110 MAF` nor `010B MAP` is reported while `0104 Engine Load` and `010C RPM` are active, `FuelEconomyTracker` and `ObdPollingEngine` SHALL estimate manifold vacuum and airflow from `Engine Load` + `Baro` + `IAT` + displacement so instant/trip/all-time KPL, DTE, DFCO, HP, and Torque compute accurately.

#### Scenario: 2024 Avanza (37 Mode 01 PIDs without MAF or MAP) computes live fuel economy
- **WHEN** `rpm`, `engine_load`, `iat`, `baro`, and `obd_speed` are polled with `maf == null` and `mapKpa == null`
- **THEN** fuel flow, KPL, fuel used, DTE, and HP/Torque populate non-null realistic values instead of `—`
