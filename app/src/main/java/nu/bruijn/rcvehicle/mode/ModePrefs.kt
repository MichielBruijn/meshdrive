package nu.bruijn.rcvehicle.mode

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "mode_prefs")

enum class AppMode { CONTROLLER, VEHICLE }

/**
 * Persists which mode this phone was last launched in, and (for controller mode) the
 * paired vehicle's Yggdrasil address entered once during pairing. See the plan's
 * "Pairing" section - there is no discovery protocol, this is the entire mechanism.
 */
class ModePrefs(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("app_mode")
        val PAIRED_YGGDRASIL_ADDRESS = stringPreferencesKey("paired_yggdrasil_address")
        val DEBUG_ENABLED = booleanPreferencesKey("debug_enabled")
    }

    val mode: Flow<AppMode?> = context.dataStore.data.map { prefs ->
        prefs[Keys.MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }
    }

    val pairedYggdrasilAddress: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.PAIRED_YGGDRASIL_ADDRESS]
    }

    /** Off by default - raw addresses, logs, the FPS/latency overlay, and the wifi
     * fallback/debug-slider controls are hidden until explicitly turned on, see
     * MainActivity's toggle. Everything needed for normal day-to-day driving (USB
     * connect, QR pairing, joystick, quality picker) stays visible regardless. */
    val debugEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[Keys.DEBUG_ENABLED] ?: false }

    suspend fun setMode(mode: AppMode) {
        context.dataStore.edit { it[Keys.MODE] = mode.name }
    }

    suspend fun setPairedYggdrasilAddress(address: String) {
        context.dataStore.edit { it[Keys.PAIRED_YGGDRASIL_ADDRESS] = address }
    }

    suspend fun setDebugEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEBUG_ENABLED] = enabled }
    }
}
