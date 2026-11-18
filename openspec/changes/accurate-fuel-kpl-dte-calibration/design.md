# Design — Accurate Real-World Fuel, KPL & Estimated Range Calibration

## Context

Empirical analysis of the `21,285` logged samples across Sessions 2, 4, 5, 6, and 7 on the owner's `2014 Toyota Avanza 1.3L K3-VE 4AT` (`apex_obd.db`) established:
- Stationary idle (`<= 2 km/h`) consumed `7.6%–10.5%` of total recorded trip fuel in commutes (`Session 2`, `Session 4`), before counting unrecorded warm-ups and parked idle.
- Apex DFCO (`> 12 km/h` and `> 1150 RPM`) falsely zeroed `7.3%–16.3%` of city samples at median `1277–1358 RPM` (`MAP 28–33 kPa`, `MAF 2.7–3.0 g/s`), undercounting trip fuel by `4.7%–10.4%`.
- `recentDteKpl` reached `15.85 km/L` by tracking only moving slices up to `45 km/L`, turning `44.82 L` remaining fuel into `710 km` `Est. Range` vs `~450 km` real pump capacity (`9.5–10.5 km/L`).
- `0110 MAF` divides to `69.1%–75.0%` apparent VE in city load (`< 45%` load), while `Speed-Density VE = 85%` yields `10.80 km/L` (OBD) / `10.31 km/L` (GPS), matching Torque Enhanced (`10.7 km/L`) and pump checkpoints.

## Decisions

### 1. Idle-Inclusive Total Economy & Realistic DTE
- **Profile Storage (`MIGRATION_6_7`):** Add `allTimeTotalDistanceKm`, `allTimeTotalFuelLiters`, and `fuelUsedSinceFullRefuelLiters` (preserving `allTimeRoadDistanceKm` and `allTimeRoadFuelLiters` so `roadOnlyAvg` remains inspectable as an engineering/driving-style metric). For existing rows during migration, compute `allTimeTotalDistanceKm = max(allTimeRoadDistanceKm, sum(saved distanceKm))` and realistic seed `recentDteKpl` from saved sessions (`~10.8 km/L` / null if empty, removing the inflated `15.85` value).
- **Home & HUD `All-Time Avg` (`kpl_road_avg` -> `KPL · AVG`):** Divide total distance by **total fuel (road + idle)** so Home and HUD `All-Time Avg` reflect real fuel burned from the tank.
- **Conservative DTE Window (`Est. Range`):**
  - Update `recentDteKpl` over combined `(distanceKm, totalFuelWithIdleLiters)` slices (`>= 0.15 km` or total fuel `>= 0.025 L`, bounded `2.0..28.0 km/L`, smoothing constant `10 km`) so sitting in traffic pulls range down proportionally while avoiding `45 km/L` false spikes.
  - Blend `0.50 * recentDteKpl + 0.50 * allTimeTotalAvg` (`0.65 / 0.35` only when tank `<= 25%`).

### 2. Transmission-Aware Realistic ECU DFCO + Optional Torque-Style Airflow Mode
- **Why Realistic Strict DFCO Is Recommended as Default:** Modern port-injected Toyota/Daihatsu ECUs genuinely cut injection (`0 ms`) on closed-throttle deceleration while engine braking keeps RPM above the fuel-resume point (`99.9 km/L` observed on the 2024 Avanza OEM MID). Generic MAF math in Torque Standard cannot detect injector cut and keeps guessing `20–50 km/L` from vacuum pumping air.
- **Thresholds by Drivetrain (`TransmissionType`):**
  - Require verified MAP vacuum (`mapKpa != null && mapKpa <= baro * 0.28`, eliminating blind DFCO when MAP is absent or stale), closed throttle (`relThrottlePct <= 1.5` or `throttlePct <= 12.0`), warm coolant (`(coolantCelsius ?: 80.0) >= 65.0`), AND:
    - `MANUAL` / `SINGLE_SPEED_EV`: `speedKmh >= 18.0` and `rpm >= 1500.0`
    - `AUTOMATIC` / `CVT` / `DCT`: `speedKmh >= 26.0` and `rpm >= 1700.0` (eliminates 4AT torque-converter unlock city false positives around `1200–1500 RPM`)
  - If SAE PID `0103` (`Fuel System Status`) is active and reports open-loop decel (`status == 4`) with closed throttle, or wideband O2 (`0124`/`0134`/`0144`) shows extreme lean (`lambda >= 1.75`) off-throttle above idle speed, allow DFCO even near threshold boundary. Conversely, if `0103` reports active closed-loop feedback (`status == 2`) at `1200–1600 RPM`, suppress DFCO even if TPS is `0%`.
- **Optional Setting:** Add `FuelCoastingMode` (`REALISTIC_DFCO` [Default] vs `AIRFLOW_CONTINUOUS` Torque-style) in Settings/Profile for side-by-side comparison.

### 3. Cold Enrichment Fix, Retail Fuel Density & Refuel Pump Calibration
- **Cold Enrichment:** Apply `coldFactor` whenever `coolantCelsius < 70.0` unless closed-loop fuel trims (`trimSumPct`) are actively negative (`< -3.0%`). Raise moderate acceleration enrichment linearly between load `70%..85%` (`lambda 1.0 -> 0.85`).
- **Fuel Density:** Set default gasoline density to `732.0 g/L` (`FuelType.GASOLINE`) matching commercial RON 90–95 retail gasoline.
- **Pump Calibration (`mafTrimPct` + Full-Tank Refuel):**
  - Expose `Fuel Calibration Trim (%)` (`75.0..135.0%`, default `100.0%`) in `VehicleProfileSheet`.
  - Add `"Full Tank Fill-Up"` toggle (default ON when logging a full fill) and `"Auto-Calibrate Fuel Trim"` switch in `RefuelLogSheet`: when two consecutive Full Tank entries exist and `fuelUsedSinceFullRefuelLiters > 5.0 L`, compute bounded `suggestedTrim = currentTrim * (actualPumpLiters / fuelUsedSinceFullRefuelLiters).coerceIn(0.80, 1.25)` (`35%` step blend, clamped `75%..135%`) and reset `fuelUsedSinceFullRefuelLiters = 0.0`.
