package com.apexobd.core.model

enum class FuelType(
    val label: String,
    val stoichiometricAfr: Double,
    val densityGramsPerLiter: Double
) {
    GASOLINE("Gasoline", 14.7, 745.0),
    DIESEL("Diesel", 14.5, 832.0),
    ELECTRIC("Electric", 1.0, 1000.0)
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
    TORQUE_NM
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
            SensorQuantity.RAW -> valueMetric to ""
        }
    }
}
