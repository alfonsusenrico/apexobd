package com.apexobd.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apexobd.core.model.AirflowUnit
import com.apexobd.core.model.FuelEconomyUnit
import com.apexobd.core.model.PowerUnit
import com.apexobd.core.model.PressureUnit
import com.apexobd.core.model.SpeedUnit
import com.apexobd.core.model.TempUnit
import com.apexobd.core.model.TorqueUnit
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.model.VolumeUnit
import com.apexobd.core.scheduler.PollingProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "apex_obd_prefs")

enum class MapProvider(val label: String) {
    OFFLINE_MAPLIBRE("Offline Vector (MapLibre)"),
    TRACK_ONLY("Track Path Only (OLED)")
}

enum class HudGridScale(val label: String, val cols: Int, val rows: Int) {
    LARGE_6X3("Large (6×3)", 6, 3),
    STANDARD_8X4("Standard (8×4)", 8, 4),
    BALANCED_10X5("Balanced (10×5)", 10, 5),
    DYNAMIC_12X6("Dense (12×6)", 12, 6)
}

data class UserSettings(
    val wifiHost: String = "192.168.0.10",
    val wifiPort: Int = 35000,
    val useSimulator: Boolean = false,
    val hudMirror: Boolean = false,
    val keepScreenOn: Boolean = true,
    val gridSnapEnabled: Boolean = true,
    val hudGridScale: HudGridScale = HudGridScale.STANDARD_8X4,
    val pauseFuelCalculation: Boolean = false,
    val dualCellularKeepalive: Boolean = true,
    val mapProvider: MapProvider = MapProvider.OFFLINE_MAPLIBRE,
    val activePmtilesPath: String? = null,
    val currencyCode: String = "IDR",
    val pollingProfile: PollingProfile = PollingProfile.BALANCED,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val tempUnit: TempUnit = TempUnit.CELSIUS,
    val pressureUnit: PressureUnit = PressureUnit.BAR,
    val fuelEconomyUnit: FuelEconomyUnit = FuelEconomyUnit.KPL,
    val volumeUnit: VolumeUnit = VolumeUnit.LITERS,
    val airflowUnit: AirflowUnit = AirflowUnit.G_PER_S,
    val powerUnit: PowerUnit = PowerUnit.HP,
    val torqueUnit: TorqueUnit = TorqueUnit.NM
) {
    fun toUnitPreferences(): UnitPreferences = UnitPreferences(
        speed = speedUnit,
        temperature = tempUnit,
        pressure = pressureUnit,
        fuelEconomy = fuelEconomyUnit,
        volume = volumeUnit,
        airflow = airflowUnit,
        power = powerUnit,
        torque = torqueUnit
    )
}

class AppPreferences(private val context: Context) {

    private object Keys {
        val WIFI_HOST = stringPreferencesKey("wifi_host")
        val WIFI_PORT = intPreferencesKey("wifi_port")
        val USE_SIMULATOR = booleanPreferencesKey("use_simulator")
        val HUD_MIRROR = booleanPreferencesKey("hud_mirror")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val GRID_SNAP = booleanPreferencesKey("grid_snap")
        val HUD_GRID_SCALE = stringPreferencesKey("hud_grid_scale")
        val PAUSE_FUEL = booleanPreferencesKey("pause_fuel_calculation")
        val DUAL_CELLULAR = booleanPreferencesKey("dual_cellular_keepalive")
        val MAP_PROVIDER = stringPreferencesKey("map_provider")
        val ACTIVE_PMTILES_PATH = stringPreferencesKey("active_pmtiles_path")
        val CURRENCY_CODE = stringPreferencesKey("currency_code")
        val POLLING_PROFILE = stringPreferencesKey("polling_profile")
        val SPEED_UNIT = stringPreferencesKey("speed_unit")
        val TEMP_UNIT = stringPreferencesKey("temp_unit")
        val PRESSURE_UNIT = stringPreferencesKey("pressure_unit")
        val FUEL_ECON_UNIT = stringPreferencesKey("fuel_econ_unit")
        val VOLUME_UNIT = stringPreferencesKey("volume_unit")
        val AIRFLOW_UNIT = stringPreferencesKey("airflow_unit")
        val POWER_UNIT = stringPreferencesKey("power_unit")
        val TORQUE_UNIT = stringPreferencesKey("torque_unit")
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            wifiHost = prefs[Keys.WIFI_HOST] ?: "192.168.0.10",
            wifiPort = prefs[Keys.WIFI_PORT] ?: 35000,
            useSimulator = prefs[Keys.USE_SIMULATOR] ?: false,
            hudMirror = prefs[Keys.HUD_MIRROR] ?: false,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            gridSnapEnabled = prefs[Keys.GRID_SNAP] ?: true,
            hudGridScale = enumOrDefault(prefs[Keys.HUD_GRID_SCALE], HudGridScale.STANDARD_8X4),
            pauseFuelCalculation = prefs[Keys.PAUSE_FUEL] ?: false,
            dualCellularKeepalive = prefs[Keys.DUAL_CELLULAR] ?: true,
            mapProvider = enumOrDefault(prefs[Keys.MAP_PROVIDER], MapProvider.OFFLINE_MAPLIBRE),
            activePmtilesPath = prefs[Keys.ACTIVE_PMTILES_PATH]?.takeIf { it.isNotBlank() },
            currencyCode = prefs[Keys.CURRENCY_CODE] ?: "IDR",
            pollingProfile = enumOrDefault(prefs[Keys.POLLING_PROFILE], PollingProfile.BALANCED),
            speedUnit = enumOrDefault(prefs[Keys.SPEED_UNIT], SpeedUnit.KMH),
            tempUnit = enumOrDefault(prefs[Keys.TEMP_UNIT], TempUnit.CELSIUS),
            pressureUnit = enumOrDefault(prefs[Keys.PRESSURE_UNIT], PressureUnit.BAR),
            fuelEconomyUnit = enumOrDefault(prefs[Keys.FUEL_ECON_UNIT], FuelEconomyUnit.KPL),
            volumeUnit = enumOrDefault(prefs[Keys.VOLUME_UNIT], VolumeUnit.LITERS),
            airflowUnit = enumOrDefault(prefs[Keys.AIRFLOW_UNIT], AirflowUnit.G_PER_S),
            powerUnit = enumOrDefault(prefs[Keys.POWER_UNIT], PowerUnit.HP),
            torqueUnit = enumOrDefault(prefs[Keys.TORQUE_UNIT], TorqueUnit.NM)
        )
    }

    suspend fun updateWifiEndpoint(host: String, port: Int) {
        context.dataStore.edit {
            it[Keys.WIFI_HOST] = host.trim()
            it[Keys.WIFI_PORT] = port
        }
    }

    suspend fun setUseSimulator(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_SIMULATOR] = enabled }
    }

    suspend fun setHudMirror(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HUD_MIRROR] = enabled }
    }

    suspend fun setGridSnapEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GRID_SNAP] = enabled }
    }

    suspend fun setHudGridScale(scale: HudGridScale) {
        context.dataStore.edit { it[Keys.HUD_GRID_SCALE] = scale.name }
    }

    suspend fun setPauseFuelCalculation(paused: Boolean) {
        context.dataStore.edit { it[Keys.PAUSE_FUEL] = paused }
    }

    suspend fun setDualCellularKeepalive(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DUAL_CELLULAR] = enabled }
    }

    suspend fun setMapProvider(provider: MapProvider) {
        context.dataStore.edit { it[Keys.MAP_PROVIDER] = provider.name }
    }

    suspend fun setActivePmtilesPath(path: String?) {
        context.dataStore.edit {
            if (path.isNullOrBlank()) it.remove(Keys.ACTIVE_PMTILES_PATH) else it[Keys.ACTIVE_PMTILES_PATH] = path
        }
    }

    suspend fun setCurrencyCode(code: String) {
        context.dataStore.edit { it[Keys.CURRENCY_CODE] = code.trim().uppercase() }
    }

    suspend fun setPollingProfile(profile: PollingProfile) {
        context.dataStore.edit { it[Keys.POLLING_PROFILE] = profile.name }
    }

    suspend fun updateUnits(
        speed: SpeedUnit? = null,
        temp: TempUnit? = null,
        pressure: PressureUnit? = null,
        fuelEconomy: FuelEconomyUnit? = null,
        volume: VolumeUnit? = null,
        airflow: AirflowUnit? = null,
        power: PowerUnit? = null,
        torque: TorqueUnit? = null
    ) {
        context.dataStore.edit {
            speed?.let { v -> it[Keys.SPEED_UNIT] = v.name }
            temp?.let { v -> it[Keys.TEMP_UNIT] = v.name }
            pressure?.let { v -> it[Keys.PRESSURE_UNIT] = v.name }
            fuelEconomy?.let { v -> it[Keys.FUEL_ECON_UNIT] = v.name }
            volume?.let { v -> it[Keys.VOLUME_UNIT] = v.name }
            airflow?.let { v -> it[Keys.AIRFLOW_UNIT] = v.name }
            power?.let { v -> it[Keys.POWER_UNIT] = v.name }
            torque?.let { v -> it[Keys.TORQUE_UNIT] = v.name }
        }
    }
}
