package com.apexobd.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "apex_obd_prefs")

data class UserSettings(
    val wifiHost: String = "192.168.0.10",
    val wifiPort: Int = 35000,
    val useSimulator: Boolean = true,
    val hudMirror: Boolean = false,
    val imperialUnits: Boolean = false,
    val keepScreenOn: Boolean = true
)

class AppPreferences(private val context: Context) {

    private object Keys {
        val WIFI_HOST = stringPreferencesKey("wifi_host")
        val WIFI_PORT = intPreferencesKey("wifi_port")
        val USE_SIMULATOR = booleanPreferencesKey("use_simulator")
        val HUD_MIRROR = booleanPreferencesKey("hud_mirror")
        val IMPERIAL_UNITS = booleanPreferencesKey("imperial_units")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            wifiHost = prefs[Keys.WIFI_HOST] ?: "192.168.0.10",
            wifiPort = prefs[Keys.WIFI_PORT] ?: 35000,
            useSimulator = prefs[Keys.USE_SIMULATOR] ?: true,
            hudMirror = prefs[Keys.HUD_MIRROR] ?: false,
            imperialUnits = prefs[Keys.IMPERIAL_UNITS] ?: false,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true
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

    suspend fun setImperialUnits(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IMPERIAL_UNITS] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }
}
