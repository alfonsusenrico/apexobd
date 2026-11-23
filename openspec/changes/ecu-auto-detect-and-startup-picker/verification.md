# Verification: Automatic ECU Vehicle Detection, Fresh-Open Vehicle Picker, and True Background Exit

## Automated Evidence
- `:obd-core:test`: PASSED (including `VehicleIdentityMatcherTest` covering 2014 Avanza K3-VE vs 2024 Avanza 2NR-VE Mode 01 PID fingerprint matching and exact VIN matching)
- `:app:testDebugUnitTest`: PASSED
- `:app:lintDebug`: PASSED (0 errors)
- `:app:assembleDebug`: PASSED (release-signed `v1.2.0`, `versionCode = 5`)
- `openspec validate --all --strict`: PASSED (16/16 items)
