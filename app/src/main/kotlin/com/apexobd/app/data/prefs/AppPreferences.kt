package com.apexobd.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apexobd.core.model.AirflowUnit
import com.apexobd.core.model.FuelCoastingMode
import com.apexobd.core.model.FuelEconomyUnit
import com.apexobd.core.model.PowerUnit
import com.apexobd.core.model.PressureUnit
import com.apexobd.core.model.SpeedUnit
import com.apexobd.core.model.TempUnit
import com.apexobd.core.model.TorqueUnit
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.model.VolumeUnit
import com.apexobd.core.protocol.GaugeSmoothingMode
import com.apexobd.core.scheduler.PollingProfile
import com.apexobd.core.transport.ObdConnectionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "apex_obd_prefs")

enum class MapProvider(val label: String) {
    OFFLINE_MAPLIBRE("Offline Vector (MapLibre)"),
    TRACK_ONLY("Track Path Only (OLED)")
}

enum class MapStylePreset(
    val label: String,
    val subtitle: String,
    val styleUrl: String?,
    val accentHex: Long,
    val bgHex: Long,
    val isDark: Boolean
) {
    DARK_CONTRAST("Dark Contrast", "Enhanced steel-blue roads", "https://tiles.openfreemap.org/styles/dark", 0xFF6388B5, 0xFF070B12, true),
    FIORD_NAVY("Fiord Navy", "Deep slate-blue cockpit", "https://tiles.openfreemap.org/styles/fiord", 0xFF8CA8CE, 0xFF2F3B52, true),
    LIBERTY_3D("Liberty 3D", "Daylight + 3D buildings", "https://tiles.openfreemap.org/styles/liberty", 0xFFF5B951, 0xFFF4F0EA, false),
    BRIGHT_NAV("Bright Nav", "Crisp daylight highways", "https://tiles.openfreemap.org/styles/bright", 0xFF10B981, 0xFFEFECE6, false),
    POSITRON_LIGHT("Positron Minimal", "Minimal monochrome light", "https://tiles.openfreemap.org/styles/positron", 0xFF64748B, 0xFFEFEFEF, false),
    OLED_TRACK("OLED Track", "#000000 pure cyan trail", null, 0xFF00C2FF, 0xFF000000, true)
}

enum class MapCameraMode(
    val label: String,
    val tiltDeg: Double,
    val zoomLevel: Double
) {
    NAV_3D_45("3D Nav 45°", 45.0, 16.2),
    CHASE_LOW_55("Low Chase 55°", 55.0, 16.8),
    TOP_DOWN_0("Flat Top-Down", 0.0, 15.4)
}

enum class MapCursorStyle(val label: String) {
    SPORT_ARROW("Sport Arrow"),
    TOP_DOWN_CAR("Top-Down Car"),
    CLASSIC_DOT("Classic Dot")
}

enum class ShareCardBackgroundMode(val label: String) {
    CAR_PHOTO("Car Photo"),
    CUSTOM_PHOTO("Custom Photo"),
    PLAIN_DARK("Plain Dark"),
    TRANSPARENT("Transparent")
}

enum class ShareCardAspectRatio(
    val label: String,
    val widthPx: Int,
    val heightPx: Int
) {
    SQUARE_1_1("1:1", 1080, 1080),
    LANDSCAPE_4_3("4:3", 1440, 1080),
    STORY_9_16("9:16", 1080, 1920)
}

data class TripCardConfig(
    val backgroundMode: ShareCardBackgroundMode = ShareCardBackgroundMode.CAR_PHOTO,
    val customPhotoPath: String? = null,
    val aspectRatio: ShareCardAspectRatio = ShareCardAspectRatio.SQUARE_1_1,
    val showSurroundingRoads: Boolean = false,
    val surroundingRoadsOpacityPct: Int = 25,
    val surroundingRoadsMainOnly: Boolean = true,
    val showAppMark: Boolean = true,
    val showVehicleName: Boolean = true,
    val showVehicleSpecs: Boolean = true,
    val showDate: Boolean = true,
    val showTime: Boolean = true,
    val showRouteLine: Boolean = true,
    val showStartEndMarkers: Boolean = true,
    val showMetricsPanelBg: Boolean = true,
    val showMetrics: Boolean = true,
    val selectedMetrics: List<ShareCardMetric> = ShareCardMetric.DEFAULT_SELECTION
)

enum class ShareCardMetric(
    val shortLabel: String,
    val cardHeader: String,
    val defaultSelected: Boolean = false
) {
    DISTANCE("Distance", "DISTANCE", defaultSelected = true),
    TRIP_TIME("Trip Time", "TRIP TIME", defaultSelected = true),
    AVG_ECONOMY("Avg Economy", "AVG ECONOMY", defaultSelected = true),
    AVG_TOP_SPEED("Avg / Top Speed", "AVG / TOP SPEED", defaultSelected = true),
    PEAK_RPM("Peak RPM", "PEAK RPM", defaultSelected = true),
    TRIP_EFFICIENCY("Trip Efficiency", "TRIP EFFICIENCY", defaultSelected = true),
    PEAK_AVG_HP("Peak / Avg HP", "PEAK / AVG HP"),
    PEAK_TORQUE("Peak Torque", "PEAK TORQUE"),
    FUEL_USED("Fuel Used", "FUEL USED"),
    MOVING_KPL("Moving Avg KPL", "MOVING KPL"),
    COOLANT_PEAK("Peak Coolant", "PEAK COOLANT"),
    MAX_THROTTLE("Peak Throttle", "MAX THROTTLE"),
    MAX_G_FORCE("Peak G-Force", "MAX G-FORCE"),
    AVG_VOLTAGE("Avg Voltage", "AVG VOLTAGE"),
    IDLE_TIME("Idle Time", "IDLE TIME");

    companion object {
        const val MAX_SELECTED = 6
        val DEFAULT_SELECTION: List<ShareCardMetric> = entries.filter { it.defaultSelected }

        fun parseStored(raw: String?): List<ShareCardMetric> {
            if (raw.isNullOrBlank()) return DEFAULT_SELECTION
            val parsed = raw.split(',')
                .mapNotNull { token -> runCatching { valueOf(token.trim()) }.getOrNull() }
                .distinct()
                .take(MAX_SELECTED)
            return parsed.ifEmpty { DEFAULT_SELECTION }
        }

        fun serialize(list: List<ShareCardMetric>): String =
            list.distinct().take(MAX_SELECTED).joinToString(",") { it.name }
    }
}

enum class HudGridScale(val label: String, val cols: Int, val rows: Int) {
    LARGE_6X3("Large (6×3)", 6, 3),
    STANDARD_8X4("Standard (8×4)", 8, 4),
    BALANCED_10X5("Balanced (10×5)", 10, 5),
    DYNAMIC_12X6("Dense (12×6)", 12, 6)
}

enum class HudThemePreset(val label: String, val accentHex: Long) {
    OLED_BLUE("OLED Pure Blue", 0xFF00A8FF),
    GLASSY_DIMMED("Glassy Dimmed", 0xFF38BDF8),
    MOTORSPORT_CRIMSON("Motorsport Crimson", 0xFFEF4444),
    PIT_EMERALD("Pit-Lane Emerald", 0xFF22C55E),
    CLUSTER_AMBER("Cluster Amber", 0xFFF59E0B),
    CARBON_TITANIUM("Carbon Titanium", 0xFFE2E8F0),
    NIGHT_VIOLET("Night Violet", 0xFFA78BFA)
}

enum class HudCanvasBackdrop(val label: String) {
    MATCH_THEME("Match Theme"),
    GLASSY_DIMMED("Glassy Dimmed"),
    PURE_BLACK("Pure Black (#000000)")
}

enum class HudCardStyle(val label: String) {
    THEMED("Themed"),
    CLEAR_GLASS("Clear Glass")
}

data class UserSettings(
    val connectionType: ObdConnectionType = ObdConnectionType.WIFI_TCP,
    val wifiHost: String = "192.168.0.10",
    val wifiPort: Int = 35000,
    val btDeviceAddress: String = "",
    val btDeviceName: String = "",
    val bleDeviceAddress: String = "",
    val bleDeviceName: String = "",
    val usbDeviceId: Int = 0,
    val usbBaudRate: Int = 38400,
    val hudMirror: Boolean = false,
    val keepScreenOn: Boolean = true,
    val gridSnapEnabled: Boolean = true,
    val showWidgetBorders: Boolean = true,
    val hudTheme: HudThemePreset = HudThemePreset.OLED_BLUE,
    val hudCanvasBackdrop: HudCanvasBackdrop = HudCanvasBackdrop.MATCH_THEME,
    val hudCardStyle: HudCardStyle = HudCardStyle.THEMED,
    val hudGridScale: HudGridScale = HudGridScale.STANDARD_8X4,
    val pauseFuelCalculation: Boolean = false,
    val dualCellularKeepalive: Boolean = true,
    val mapProvider: MapProvider = MapProvider.OFFLINE_MAPLIBRE,
    val mapStylePreset: MapStylePreset = MapStylePreset.DARK_CONTRAST,
    val mapCameraMode: MapCameraMode = MapCameraMode.NAV_3D_45,
    val mapCursorStyle: MapCursorStyle = MapCursorStyle.SPORT_ARROW,
    val highContrastMapRoads: Boolean = true,
    val shareCardMetrics: List<ShareCardMetric> = ShareCardMetric.DEFAULT_SELECTION,
    val tripCardConfig: TripCardConfig = TripCardConfig(),
    val activePmtilesPath: String? = null,
    val currencyCode: String = "IDR",
    val pollingProfile: PollingProfile = PollingProfile.BALANCED,
    val gaugeSmoothingMode: GaugeSmoothingMode = GaugeSmoothingMode.LIGHT_DAMPER,
    val liveMicroMotion: Boolean = false,
    val fuelCoastingMode: FuelCoastingMode = FuelCoastingMode.REALISTIC_DFCO,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val tempUnit: TempUnit = TempUnit.CELSIUS,
    val pressureUnit: PressureUnit = PressureUnit.BAR,
    val fuelEconomyUnit: FuelEconomyUnit = FuelEconomyUnit.KPL,
    val volumeUnit: VolumeUnit = VolumeUnit.LITERS,
    val airflowUnit: AirflowUnit = AirflowUnit.G_PER_S,
    val powerUnit: PowerUnit = PowerUnit.HP,
    val torqueUnit: TorqueUnit = TorqueUnit.NM
) {
    fun adapterChipLabel(): String = when (connectionType) {
        ObdConnectionType.WIFI_TCP -> "Wi-Fi · $wifiHost"
        ObdConnectionType.BLUETOOTH_CLASSIC -> "BT · ${btDeviceName.ifBlank { "Auto" }}"
        ObdConnectionType.BLUETOOTH_LE -> "BLE · ${bleDeviceName.ifBlank { "Select" }}"
        ObdConnectionType.USB_SERIAL -> "USB · $usbBaudRate"
    }

    fun adapterDetailedEndpoint(): String = when (connectionType) {
        ObdConnectionType.WIFI_TCP -> "$wifiHost:$wifiPort"
        ObdConnectionType.BLUETOOTH_CLASSIC -> btDeviceName.ifBlank { btDeviceAddress.ifBlank { "BT Classic" } }
        ObdConnectionType.BLUETOOTH_LE -> bleDeviceName.ifBlank { bleDeviceAddress.ifBlank { "BLE GATT" } }
        ObdConnectionType.USB_SERIAL -> "USB OTG @ $usbBaudRate"
    }

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
        val CONNECTION_TYPE = stringPreferencesKey("connection_type")
        val WIFI_HOST = stringPreferencesKey("wifi_host")
        val WIFI_PORT = intPreferencesKey("wifi_port")
        val BT_ADDRESS = stringPreferencesKey("bt_device_address")
        val BT_NAME = stringPreferencesKey("bt_device_name")
        val BLE_ADDRESS = stringPreferencesKey("ble_device_address")
        val BLE_NAME = stringPreferencesKey("ble_device_name")
        val USB_DEVICE_ID = intPreferencesKey("usb_device_id")
        val USB_BAUD_RATE = intPreferencesKey("usb_baud_rate")
        val HUD_MIRROR = booleanPreferencesKey("hud_mirror")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val GRID_SNAP = booleanPreferencesKey("grid_snap")
        val SHOW_WIDGET_BORDERS = booleanPreferencesKey("show_widget_borders")
        val HUD_THEME = stringPreferencesKey("hud_theme")
        val HUD_CANVAS_BACKDROP = stringPreferencesKey("hud_canvas_backdrop")
        val HUD_CARD_STYLE = stringPreferencesKey("hud_card_style")
        val HUD_GRID_SCALE = stringPreferencesKey("hud_grid_scale")
        val PAUSE_FUEL = booleanPreferencesKey("pause_fuel_calculation")
        val DUAL_CELLULAR = booleanPreferencesKey("dual_cellular_keepalive")
        val MAP_PROVIDER = stringPreferencesKey("map_provider")
        val MAP_STYLE_PRESET = stringPreferencesKey("map_style_preset")
        val MAP_CAMERA_MODE = stringPreferencesKey("map_camera_mode")
        val MAP_CURSOR_STYLE = stringPreferencesKey("map_cursor_style")
        val HIGH_CONTRAST_MAP_ROADS = booleanPreferencesKey("high_contrast_map_roads")
        val SHARE_CARD_METRICS = stringPreferencesKey("share_card_metrics")
        val SHARE_CARD_BG_MODE = stringPreferencesKey("share_card_bg_mode")
        val SHARE_CARD_CUSTOM_PHOTO = stringPreferencesKey("share_card_custom_photo")
        val SHARE_CARD_ASPECT_RATIO = stringPreferencesKey("share_card_aspect_ratio")
        val SHARE_CARD_SURROUNDING_ROADS = booleanPreferencesKey("share_card_surrounding_roads")
        val SHARE_CARD_ROADS_OPACITY_PCT = intPreferencesKey("share_card_roads_opacity_pct")
        val SHARE_CARD_ROADS_MAIN_ONLY = booleanPreferencesKey("share_card_roads_main_only")
        val SHARE_CARD_SHOW_APP_MARK = booleanPreferencesKey("share_card_show_app_mark")
        val SHARE_CARD_SHOW_VEHICLE_NAME = booleanPreferencesKey("share_card_show_vehicle_name")
        val SHARE_CARD_SHOW_VEHICLE_SPECS = booleanPreferencesKey("share_card_show_vehicle_specs")
        val SHARE_CARD_SHOW_DATE = booleanPreferencesKey("share_card_show_date")
        val SHARE_CARD_SHOW_TIME = booleanPreferencesKey("share_card_show_time")
        val SHARE_CARD_SHOW_ROUTE_LINE = booleanPreferencesKey("share_card_show_route_line")
        val SHARE_CARD_SHOW_MARKERS = booleanPreferencesKey("share_card_show_markers")
        val SHARE_CARD_SHOW_METRICS_BG = booleanPreferencesKey("share_card_show_metrics_bg")
        val SHARE_CARD_SHOW_METRICS = booleanPreferencesKey("share_card_show_metrics")
        val ACTIVE_PMTILES_PATH = stringPreferencesKey("active_pmtiles_path")
        val CURRENCY_CODE = stringPreferencesKey("currency_code")
        val POLLING_PROFILE = stringPreferencesKey("polling_profile")
        val GAUGE_SMOOTHING_MODE = stringPreferencesKey("gauge_smoothing_mode")
        val LIVE_MICRO_MOTION = booleanPreferencesKey("live_micro_motion")
        val FUEL_COASTING_MODE = stringPreferencesKey("fuel_coasting_mode")
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
        val connType = enumOrDefault(prefs[Keys.CONNECTION_TYPE], ObdConnectionType.WIFI_TCP)
        UserSettings(
            connectionType = connType,
            wifiHost = prefs[Keys.WIFI_HOST] ?: "192.168.0.10",
            wifiPort = prefs[Keys.WIFI_PORT] ?: 35000,
            btDeviceAddress = prefs[Keys.BT_ADDRESS] ?: "",
            btDeviceName = prefs[Keys.BT_NAME] ?: "",
            bleDeviceAddress = prefs[Keys.BLE_ADDRESS] ?: "",
            bleDeviceName = prefs[Keys.BLE_NAME] ?: "",
            usbDeviceId = prefs[Keys.USB_DEVICE_ID] ?: 0,
            usbBaudRate = prefs[Keys.USB_BAUD_RATE] ?: 38400,
            hudMirror = prefs[Keys.HUD_MIRROR] ?: false,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            gridSnapEnabled = prefs[Keys.GRID_SNAP] ?: true,
            showWidgetBorders = prefs[Keys.SHOW_WIDGET_BORDERS] ?: true,
            hudTheme = enumOrDefault(prefs[Keys.HUD_THEME], HudThemePreset.OLED_BLUE),
            hudCanvasBackdrop = enumOrDefault(prefs[Keys.HUD_CANVAS_BACKDROP], HudCanvasBackdrop.MATCH_THEME),
            hudCardStyle = enumOrDefault(prefs[Keys.HUD_CARD_STYLE], HudCardStyle.THEMED),
            hudGridScale = enumOrDefault(prefs[Keys.HUD_GRID_SCALE], HudGridScale.STANDARD_8X4),
            pauseFuelCalculation = prefs[Keys.PAUSE_FUEL] ?: false,
            dualCellularKeepalive = prefs[Keys.DUAL_CELLULAR] ?: true,
            mapProvider = enumOrDefault(prefs[Keys.MAP_PROVIDER], MapProvider.OFFLINE_MAPLIBRE),
            mapStylePreset = enumOrDefault(prefs[Keys.MAP_STYLE_PRESET], MapStylePreset.DARK_CONTRAST),
            mapCameraMode = enumOrDefault(prefs[Keys.MAP_CAMERA_MODE], MapCameraMode.NAV_3D_45),
            mapCursorStyle = enumOrDefault(prefs[Keys.MAP_CURSOR_STYLE], MapCursorStyle.SPORT_ARROW),
            highContrastMapRoads = prefs[Keys.HIGH_CONTRAST_MAP_ROADS] ?: true,
            shareCardMetrics = ShareCardMetric.parseStored(prefs[Keys.SHARE_CARD_METRICS]),
            tripCardConfig = TripCardConfig(
                backgroundMode = enumOrDefault(prefs[Keys.SHARE_CARD_BG_MODE], ShareCardBackgroundMode.CAR_PHOTO),
                customPhotoPath = prefs[Keys.SHARE_CARD_CUSTOM_PHOTO]?.takeIf { it.isNotBlank() },
                aspectRatio = enumOrDefault(prefs[Keys.SHARE_CARD_ASPECT_RATIO], ShareCardAspectRatio.SQUARE_1_1),
                showSurroundingRoads = prefs[Keys.SHARE_CARD_SURROUNDING_ROADS] ?: false,
                surroundingRoadsOpacityPct = (prefs[Keys.SHARE_CARD_ROADS_OPACITY_PCT] ?: 25).coerceIn(10, 60),
                surroundingRoadsMainOnly = prefs[Keys.SHARE_CARD_ROADS_MAIN_ONLY] ?: true,
                showAppMark = prefs[Keys.SHARE_CARD_SHOW_APP_MARK] ?: true,
                showVehicleName = prefs[Keys.SHARE_CARD_SHOW_VEHICLE_NAME] ?: true,
                showVehicleSpecs = prefs[Keys.SHARE_CARD_SHOW_VEHICLE_SPECS] ?: true,
                showDate = prefs[Keys.SHARE_CARD_SHOW_DATE] ?: true,
                showTime = prefs[Keys.SHARE_CARD_SHOW_TIME] ?: true,
                showRouteLine = prefs[Keys.SHARE_CARD_SHOW_ROUTE_LINE] ?: true,
                showStartEndMarkers = prefs[Keys.SHARE_CARD_SHOW_MARKERS] ?: true,
                showMetricsPanelBg = prefs[Keys.SHARE_CARD_SHOW_METRICS_BG] ?: true,
                showMetrics = prefs[Keys.SHARE_CARD_SHOW_METRICS] ?: true,
                selectedMetrics = ShareCardMetric.parseStored(prefs[Keys.SHARE_CARD_METRICS])
            ),
            activePmtilesPath = prefs[Keys.ACTIVE_PMTILES_PATH]?.takeIf { it.isNotBlank() },
            currencyCode = prefs[Keys.CURRENCY_CODE] ?: "IDR",
            pollingProfile = enumOrDefault(prefs[Keys.POLLING_PROFILE], PollingProfile.BALANCED),
            gaugeSmoothingMode = enumOrDefault(prefs[Keys.GAUGE_SMOOTHING_MODE], GaugeSmoothingMode.LIGHT_DAMPER),
            liveMicroMotion = prefs[Keys.LIVE_MICRO_MOTION] ?: false,
            fuelCoastingMode = enumOrDefault(prefs[Keys.FUEL_COASTING_MODE], FuelCoastingMode.REALISTIC_DFCO),
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

    suspend fun setConnectionType(type: ObdConnectionType) {
        context.dataStore.edit {
            it[Keys.CONNECTION_TYPE] = type.name
        }
    }

    suspend fun updateBluetoothTarget(address: String, name: String) {
        context.dataStore.edit {
            it[Keys.BT_ADDRESS] = address.trim().uppercase()
            it[Keys.BT_NAME] = name.trim()
        }
    }

    suspend fun updateBleTarget(address: String, name: String) {
        context.dataStore.edit {
            it[Keys.BLE_ADDRESS] = address.trim().uppercase()
            it[Keys.BLE_NAME] = name.trim()
        }
    }

    suspend fun updateUsbConfig(deviceId: Int, baudRate: Int) {
        context.dataStore.edit {
            it[Keys.USB_DEVICE_ID] = deviceId
            it[Keys.USB_BAUD_RATE] = baudRate
        }
    }

    suspend fun setHudMirror(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HUD_MIRROR] = enabled }
    }

    suspend fun setGridSnapEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GRID_SNAP] = enabled }
    }

    suspend fun setShowWidgetBorders(visible: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_WIDGET_BORDERS] = visible }
    }

    suspend fun setHudTheme(preset: HudThemePreset) {
        context.dataStore.edit { it[Keys.HUD_THEME] = preset.name }
    }

    suspend fun setHudCanvasBackdrop(backdrop: HudCanvasBackdrop) {
        context.dataStore.edit { it[Keys.HUD_CANVAS_BACKDROP] = backdrop.name }
    }

    suspend fun setHudCardStyle(style: HudCardStyle) {
        context.dataStore.edit { it[Keys.HUD_CARD_STYLE] = style.name }
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
        context.dataStore.edit {
            it[Keys.MAP_PROVIDER] = provider.name
            if (provider == MapProvider.TRACK_ONLY) {
                it[Keys.MAP_STYLE_PRESET] = MapStylePreset.OLED_TRACK.name
            } else if (enumOrDefault(it[Keys.MAP_STYLE_PRESET], MapStylePreset.DARK_CONTRAST) == MapStylePreset.OLED_TRACK) {
                it[Keys.MAP_STYLE_PRESET] = MapStylePreset.DARK_CONTRAST.name
            }
        }
    }

    suspend fun setMapStylePreset(style: MapStylePreset) {
        context.dataStore.edit {
            it[Keys.MAP_STYLE_PRESET] = style.name
            it[Keys.MAP_PROVIDER] = if (style == MapStylePreset.OLED_TRACK) {
                MapProvider.TRACK_ONLY.name
            } else {
                MapProvider.OFFLINE_MAPLIBRE.name
            }
        }
    }

    suspend fun setMapCameraMode(mode: MapCameraMode) {
        context.dataStore.edit { it[Keys.MAP_CAMERA_MODE] = mode.name }
    }

    suspend fun setMapCursorStyle(style: MapCursorStyle) {
        context.dataStore.edit { it[Keys.MAP_CURSOR_STYLE] = style.name }
    }

    suspend fun setShareCardMetrics(metrics: List<ShareCardMetric>) {
        val clean = metrics.distinct().take(ShareCardMetric.MAX_SELECTED)
            .ifEmpty { ShareCardMetric.DEFAULT_SELECTION }
        context.dataStore.edit { it[Keys.SHARE_CARD_METRICS] = ShareCardMetric.serialize(clean) }
    }

    suspend fun saveTripCardConfig(config: TripCardConfig) {
        val cleanMetrics = config.selectedMetrics.distinct().take(ShareCardMetric.MAX_SELECTED)
            .ifEmpty { ShareCardMetric.DEFAULT_SELECTION }
        context.dataStore.edit {
            it[Keys.SHARE_CARD_BG_MODE] = config.backgroundMode.name
            if (config.customPhotoPath.isNullOrBlank()) {
                it.remove(Keys.SHARE_CARD_CUSTOM_PHOTO)
            } else {
                it[Keys.SHARE_CARD_CUSTOM_PHOTO] = config.customPhotoPath
            }
            it[Keys.SHARE_CARD_ASPECT_RATIO] = config.aspectRatio.name
            it[Keys.SHARE_CARD_SURROUNDING_ROADS] = config.showSurroundingRoads
            it[Keys.SHARE_CARD_ROADS_OPACITY_PCT] = config.surroundingRoadsOpacityPct.coerceIn(10, 60)
            it[Keys.SHARE_CARD_ROADS_MAIN_ONLY] = config.surroundingRoadsMainOnly
            it[Keys.SHARE_CARD_SHOW_APP_MARK] = config.showAppMark
            it[Keys.SHARE_CARD_SHOW_VEHICLE_NAME] = config.showVehicleName
            it[Keys.SHARE_CARD_SHOW_VEHICLE_SPECS] = config.showVehicleSpecs
            it[Keys.SHARE_CARD_SHOW_DATE] = config.showDate
            it[Keys.SHARE_CARD_SHOW_TIME] = config.showTime
            it[Keys.SHARE_CARD_SHOW_ROUTE_LINE] = config.showRouteLine
            it[Keys.SHARE_CARD_SHOW_MARKERS] = config.showStartEndMarkers
            it[Keys.SHARE_CARD_SHOW_METRICS_BG] = config.showMetricsPanelBg
            it[Keys.SHARE_CARD_SHOW_METRICS] = config.showMetrics
            it[Keys.SHARE_CARD_METRICS] = ShareCardMetric.serialize(cleanMetrics)
        }
    }

    suspend fun setHighContrastMapRoads(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HIGH_CONTRAST_MAP_ROADS] = enabled }
    }

    suspend fun setGaugeSmoothingMode(mode: GaugeSmoothingMode) {
        context.dataStore.edit { it[Keys.GAUGE_SMOOTHING_MODE] = mode.name }
    }

    suspend fun setLiveMicroMotion(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_MICRO_MOTION] = enabled }
    }

    suspend fun setFuelCoastingMode(mode: FuelCoastingMode) {
        context.dataStore.edit { it[Keys.FUEL_COASTING_MODE] = mode.name }
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
