package com.apexobd.core.model

enum class FuelType(
    val label: String,
    val stoichiometricAfr: Double,
    val densityGramsPerLiter: Double
) {
    // Retail RON 90-95 gasoline averages ~732 g/L (~725-738 g/L depending on aromatics/temperature)
    GASOLINE("Gasoline", 14.7, 732.0),
    DIESEL("Diesel", 14.5, 832.0),
    ELECTRIC("Electric", 1.0, 1000.0)
}

enum class FuelCoastingMode(
    val label: String,
    val subtitle: String
) {
    REALISTIC_DFCO("ECU DFCO Cut (99.9)", "Cuts fuel only above AT/MT overrun RPM & verified vacuum"),
    AIRFLOW_CONTINUOUS("Continuous Airflow (20–50)", "Torque-style airflow fuel calculation during coasting")
}

enum class VehicleBodyType(
    val label: String,
    val defaultCdA: Double,
    val defaultCrr: Double
) {
    SEDAN("Sedan", 0.62, 0.012),
    HATCHBACK("Hatchback", 0.66, 0.012),
    MPV("MPV", 0.78, 0.013),
    CROSSOVER("Crossover", 0.74, 0.013),
    SUV("SUV", 0.88, 0.014),
    PICKUP("Pickup", 1.05, 0.015),
    COUPE("Coupe", 0.58, 0.011),
    WAGON("Wagon", 0.68, 0.012),
    EV("EV", 0.60, 0.011)
}

enum class TransmissionType(
    val label: String,
    val shortCode: String,
    val defaultDrivetrainLossPct: Double
) {
    MANUAL("Manual (MT)", "MT", 12.0),
    AUTOMATIC("Automatic (AT)", "AT", 18.0),
    CVT("CVT", "CVT", 16.0),
    DCT("Dual-Clutch (DCT)", "DCT", 14.0),
    SINGLE_SPEED_EV("Single-Speed EV", "EV", 8.0)
}

enum class SpeedUnit(val symbol: String) { KMH("km/h"), MPH("mph") }
enum class TempUnit(val symbol: String) { CELSIUS("°C"), FAHRENHEIT("°F") }
enum class PressureUnit(val symbol: String) { BAR("bar"), PSI("psi"), KPA("kPa") }
enum class FuelEconomyUnit(val symbol: String) { KPL("km/L"), L_PER_100KM("L/100km"), MPG_US("mpg") }
enum class VolumeUnit(val symbol: String) { LITERS("L"), GALLONS_US("gal") }
enum class AirflowUnit(val symbol: String) { G_PER_S("g/s"), KG_PER_H("kg/h") }
enum class PowerUnit(val symbol: String) { HP("HP"), KW("kW") }
enum class TorqueUnit(val symbol: String) { NM("Nm"), LB_FT("lb-ft") }

data class UnitPreferences(
    val speed: SpeedUnit = SpeedUnit.KMH,
    val temperature: TempUnit = TempUnit.CELSIUS,
    val pressure: PressureUnit = PressureUnit.BAR,
    val fuelEconomy: FuelEconomyUnit = FuelEconomyUnit.KPL,
    val volume: VolumeUnit = VolumeUnit.LITERS,
    val airflow: AirflowUnit = AirflowUnit.G_PER_S,
    val power: PowerUnit = PowerUnit.HP,
    val torque: TorqueUnit = TorqueUnit.NM
) {
    fun distanceSymbol(): String = if (speed == SpeedUnit.MPH) "mi" else "km"
    fun volumeSymbol(fuelType: FuelType = FuelType.GASOLINE): String = when {
        fuelType == FuelType.ELECTRIC -> "kWh"
        volume == VolumeUnit.GALLONS_US -> "gal"
        else -> "L"
    }
    fun flowSymbol(fuelType: FuelType = FuelType.GASOLINE): String = when {
        fuelType == FuelType.ELECTRIC -> "kW"
        volume == VolumeUnit.GALLONS_US -> "gal/h"
        else -> "L/h"
    }
}

data class VehicleProfile(
    val id: Long = 1L,
    val name: String = "Avanza 1.3L",
    val displacementLiters: Double = 1.3,
    val engineCc: Int = (displacementLiters * 1000.0).toInt().coerceAtLeast(100),
    val carType: VehicleBodyType = VehicleBodyType.MPV,
    val transmission: TransmissionType = TransmissionType.MANUAL,
    val modelYear: Int? = null,
    val photoUri: String? = null,
    val weightKg: Double = 1175.0,
    val fuelType: FuelType = FuelType.GASOLINE,
    val tankCapacityLiters: Double = 45.0,
    val estimatedRemainingFuelLiters: Double = 28.4,
    val volumetricEfficiencyPct: Double = 85.0,
    val mafTrimPct: Double = 100.0,
    val drivetrainLossPct: Double = 15.0,
    val dragCoefficientAreaCdA: Double = 0.72,
    val rollingResistanceCrr: Double = 0.013,
    val redlineRpm: Int = 7000,
    val idleSpeedThresholdKmh: Double = 2.0,
    val allTimeOdometerKm: Double = 0.0,
    val allTimeRoadDistanceKm: Double = 0.0,
    val allTimeRoadFuelLiters: Double = 0.0,
    val allTimeTotalDistanceKm: Double = allTimeRoadDistanceKm,
    val allTimeTotalFuelLiters: Double = allTimeRoadFuelLiters,
    val fuelUsedSinceFullRefuelLiters: Double = 0.0,
    val recentDteKpl: Double? = null,
    val best0To100Sec: Double? = null
)

enum class SensorQuantity {
    RAW,
    SPEED,
    DISTANCE_KM,
    TEMPERATURE,
    PRESSURE_KPA,
    BOOST_KPA,
    FUEL_ECONOMY_KPL,
    VOLUME_L,
    FUEL_FLOW_LPH,
    AIRFLOW_GS,
    POWER_HP,
    TORQUE_NM,
    DURATION_SEC
}

object UnitConverter {
    fun convertDistanceKm(km: Double, prefs: UnitPreferences): Pair<Double, String> =
        if (prefs.speed == SpeedUnit.MPH) (km * 0.621371) to "mi" else km to "km"

    fun convertVolume(litersOrKwh: Double, prefs: UnitPreferences, fuelType: FuelType = FuelType.GASOLINE): Pair<Double, String> =
        when {
            fuelType == FuelType.ELECTRIC -> litersOrKwh to "kWh"
            prefs.volume == VolumeUnit.GALLONS_US -> (litersOrKwh * 0.264172) to "gal"
            else -> litersOrKwh to "L"
        }

    fun convertFuelEconomy(valueKpl: Double, prefs: UnitPreferences, fuelType: FuelType = FuelType.GASOLINE): Pair<Double, String> {
        if (fuelType == FuelType.ELECTRIC) {
            return if (prefs.speed == SpeedUnit.MPH) (valueKpl * 0.621371) to "mi/kWh" else valueKpl to "km/kWh"
        }
        return when (prefs.fuelEconomy) {
            FuelEconomyUnit.KPL -> valueKpl to FuelEconomyUnit.KPL.symbol
            FuelEconomyUnit.L_PER_100KM -> {
                val l100 = if (valueKpl > 0.1) 100.0 / valueKpl else 0.0
                l100 to FuelEconomyUnit.L_PER_100KM.symbol
            }
            FuelEconomyUnit.MPG_US -> (valueKpl * 2.35215) to FuelEconomyUnit.MPG_US.symbol
        }
    }

    fun convert(
        valueMetric: Double,
        quantity: SensorQuantity,
        prefs: UnitPreferences,
        fuelType: FuelType = FuelType.GASOLINE
    ): Pair<Double, String> {
        return when (quantity) {
            SensorQuantity.SPEED -> when (prefs.speed) {
                SpeedUnit.KMH -> valueMetric to SpeedUnit.KMH.symbol
                SpeedUnit.MPH -> (valueMetric * 0.621371) to SpeedUnit.MPH.symbol
            }
            SensorQuantity.DISTANCE_KM -> convertDistanceKm(valueMetric, prefs)
            SensorQuantity.TEMPERATURE -> when (prefs.temperature) {
                TempUnit.CELSIUS -> valueMetric to TempUnit.CELSIUS.symbol
                TempUnit.FAHRENHEIT -> ((valueMetric * 9.0 / 5.0) + 32.0) to TempUnit.FAHRENHEIT.symbol
            }
            SensorQuantity.PRESSURE_KPA -> when (prefs.pressure) {
                PressureUnit.KPA -> valueMetric to PressureUnit.KPA.symbol
                PressureUnit.BAR -> (valueMetric / 100.0) to PressureUnit.BAR.symbol
                PressureUnit.PSI -> (valueMetric * 0.145038) to PressureUnit.PSI.symbol
            }
            SensorQuantity.BOOST_KPA -> when (prefs.pressure) {
                PressureUnit.BAR -> (valueMetric / 100.0) to PressureUnit.BAR.symbol
                PressureUnit.PSI -> (valueMetric * 0.145038) to PressureUnit.PSI.symbol
                PressureUnit.KPA -> valueMetric to PressureUnit.KPA.symbol
            }
            SensorQuantity.FUEL_ECONOMY_KPL -> convertFuelEconomy(valueMetric, prefs, fuelType)
            SensorQuantity.VOLUME_L -> convertVolume(valueMetric, prefs, fuelType)
            SensorQuantity.FUEL_FLOW_LPH -> when {
                fuelType == FuelType.ELECTRIC -> valueMetric to "kW"
                prefs.volume == VolumeUnit.GALLONS_US -> (valueMetric * 0.264172) to "gal/h"
                else -> valueMetric to "L/h"
            }
            SensorQuantity.AIRFLOW_GS -> when (prefs.airflow) {
                AirflowUnit.G_PER_S -> valueMetric to AirflowUnit.G_PER_S.symbol
                AirflowUnit.KG_PER_H -> (valueMetric * 3.6) to AirflowUnit.KG_PER_H.symbol
            }
            SensorQuantity.POWER_HP -> when (prefs.power) {
                PowerUnit.HP -> valueMetric to PowerUnit.HP.symbol
                PowerUnit.KW -> (valueMetric * 0.7457) to PowerUnit.KW.symbol
            }
            SensorQuantity.TORQUE_NM -> when (prefs.torque) {
                TorqueUnit.NM -> valueMetric to TorqueUnit.NM.symbol
                TorqueUnit.LB_FT -> (valueMetric * 0.737562) to TorqueUnit.LB_FT.symbol
            }
            SensorQuantity.DURATION_SEC -> valueMetric to "mm:ss"
            SensorQuantity.RAW -> valueMetric to ""
        }
    }

    fun toInternalMetric(
        displayValue: Double,
        quantity: SensorQuantity,
        prefs: UnitPreferences,
        fuelType: FuelType = FuelType.GASOLINE
    ): Double {
        return when (quantity) {
            SensorQuantity.SPEED, SensorQuantity.DISTANCE_KM -> when (prefs.speed) {
                SpeedUnit.KMH -> displayValue
                SpeedUnit.MPH -> displayValue / 0.621371
            }
            SensorQuantity.TEMPERATURE -> when (prefs.temperature) {
                TempUnit.CELSIUS -> displayValue
                TempUnit.FAHRENHEIT -> (displayValue - 32.0) * 5.0 / 9.0
            }
            SensorQuantity.PRESSURE_KPA, SensorQuantity.BOOST_KPA -> when (prefs.pressure) {
                PressureUnit.KPA -> displayValue
                PressureUnit.BAR -> displayValue * 100.0
                PressureUnit.PSI -> displayValue / 0.145038
            }
            SensorQuantity.FUEL_ECONOMY_KPL -> when {
                fuelType == FuelType.ELECTRIC -> if (prefs.speed == SpeedUnit.MPH) displayValue / 0.621371 else displayValue
                prefs.fuelEconomy == FuelEconomyUnit.KPL -> displayValue
                prefs.fuelEconomy == FuelEconomyUnit.L_PER_100KM -> if (displayValue > 0.05) 100.0 / displayValue else 0.0
                else -> displayValue / 2.35215
            }
            SensorQuantity.VOLUME_L, SensorQuantity.FUEL_FLOW_LPH -> when {
                fuelType == FuelType.ELECTRIC -> displayValue
                prefs.volume == VolumeUnit.GALLONS_US -> displayValue / 0.264172
                else -> displayValue
            }
            SensorQuantity.AIRFLOW_GS -> when (prefs.airflow) {
                AirflowUnit.G_PER_S -> displayValue
                AirflowUnit.KG_PER_H -> displayValue / 3.6
            }
            SensorQuantity.POWER_HP -> when (prefs.power) {
                PowerUnit.HP -> displayValue
                PowerUnit.KW -> displayValue / 0.7457
            }
            SensorQuantity.TORQUE_NM -> when (prefs.torque) {
                TorqueUnit.NM -> displayValue
                TorqueUnit.LB_FT -> displayValue / 0.737562
            }
            SensorQuantity.DURATION_SEC, SensorQuantity.RAW -> displayValue
        }
    }
}
