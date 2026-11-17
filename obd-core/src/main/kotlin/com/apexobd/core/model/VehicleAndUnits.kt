package com.apexobd.core.model

enum class FuelType(
    val label: String,
    val stoichiometricAfr: Double,
    val densityGramsPerLiter: Double
) {
    GASOLINE("Gasoline (RON 90-98)", 14.7, 745.0),
    E10("Gasoline E10", 14.1, 749.0),
    E85("Ethanol E85", 9.8, 785.0),
    DIESEL("Diesel", 14.5, 832.0)
}

enum class SpeedUnit(val symbol: String) { KMH("km/h"), MPH("mph") }
enum class TempUnit(val symbol: String) { CELSIUS("°C"), FAHRENHEIT("°F") }
enum class PressureUnit(val symbol: String) { BAR("bar"), PSI("psi"), KPA("kPa") }
enum class FuelEconomyUnit(val symbol: String) { KPL("km/L"), L_PER_100KM("L/100km"), MPG_US("mpg") }
enum class AirflowUnit(val symbol: String) { G_PER_S("g/s"), KG_PER_H("kg/h") }
enum class PowerUnit(val symbol: String) { HP("HP"), KW("kW") }
enum class TorqueUnit(val symbol: String) { NM("Nm"), LB_FT("lb-ft") }

data class UnitPreferences(
    val speed: SpeedUnit = SpeedUnit.KMH,
    val temperature: TempUnit = TempUnit.CELSIUS,
    val pressure: PressureUnit = PressureUnit.BAR,
    val fuelEconomy: FuelEconomyUnit = FuelEconomyUnit.KPL,
    val airflow: AirflowUnit = AirflowUnit.G_PER_S,
    val power: PowerUnit = PowerUnit.HP,
    val torque: TorqueUnit = TorqueUnit.NM
)

data class VehicleProfile(
    val id: Long = 1L,
    val name: String = "Avanza 1.3L",
    val displacementLiters: Double = 1.3,
    val weightKg: Double = 1175.0,
    val fuelType: FuelType = FuelType.GASOLINE,
    val volumetricEfficiencyPct: Double = 85.0,
    val mafTrimPct: Double = 100.0,
    val drivetrainLossPct: Double = 15.0,
    val redlineRpm: Int = 7000,
    val idleSpeedThresholdKmh: Double = 2.0
)

enum class SensorQuantity {
    RAW,
    SPEED,
    TEMPERATURE,
    PRESSURE_KPA,
    BOOST_KPA,
    FUEL_ECONOMY_KPL,
    AIRFLOW_GS,
    POWER_HP,
    TORQUE_NM
}

object UnitConverter {
    fun convert(valueMetric: Double, quantity: SensorQuantity, prefs: UnitPreferences): Pair<Double, String> {
        return when (quantity) {
            SensorQuantity.SPEED -> when (prefs.speed) {
                SpeedUnit.KMH -> valueMetric to SpeedUnit.KMH.symbol
                SpeedUnit.MPH -> (valueMetric * 0.621371) to SpeedUnit.MPH.symbol
            }
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
            SensorQuantity.FUEL_ECONOMY_KPL -> when (prefs.fuelEconomy) {
                FuelEconomyUnit.KPL -> valueMetric to FuelEconomyUnit.KPL.symbol
                FuelEconomyUnit.L_PER_100KM -> {
                    val l100 = if (valueMetric > 0.1) 100.0 / valueMetric else 0.0
                    l100 to FuelEconomyUnit.L_PER_100KM.symbol
                }
                FuelEconomyUnit.MPG_US -> (valueMetric * 2.35215) to FuelEconomyUnit.MPG_US.symbol
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
