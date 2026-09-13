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
 * vehicles paired with so far - a small MRU list (ControllerActivity), so scanning a
 * QR code is only needed once per vehicle rather than every time. See the plan's
 * "Pairing" section - there is still no discovery protocol, this is the entire
 * mechanism, just remembered across multiple vehicles instead of only the last one.
 */
class ModePrefs(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("app_mode")
        val RECENT_VEHICLES = stringPreferencesKey("recent_vehicle_addresses")
        val DEBUG_ENABLED = booleanPreferencesKey("debug_enabled")
    }

    private val MAX_RECENT_VEHICLES = 10

    val mode: Flow<AppMode?> = context.dataStore.data.map { prefs ->
        prefs[Keys.MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }
    }

    /** Most-recently-used first, so the list doubles as "last used" without a separate key. */
    val recentVehicleAddresses: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.RECENT_VEHICLES]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    }

    /** Off by default - raw addresses, logs, the FPS/latency overlay, and the wifi
     * fallback/debug-slider controls are hidden until explicitly turned on, see
     * MainActivity's toggle. Everything needed for normal day-to-day driving (USB
     * connect, QR pairing, joystick, quality picker) stays visible regardless. */
    val debugEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[Keys.DEBUG_ENABLED] ?: false }

    suspend fun setMode(mode: AppMode) {
        context.dataStore.edit { it[Keys.MODE] = mode.name }
    }

    /** Moves [address] to the front (adding it if new), so scanning/connecting to a
     * vehicle again just bumps it back to the top instead of duplicating it. */
    suspend fun addRecentVehicleAddress(address: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_VEHICLES]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            val updated = (listOf(address) + current.filter { it != address }).take(MAX_RECENT_VEHICLES)
            prefs[Keys.RECENT_VEHICLES] = updated.joinToString("\n")
        }
    }

    suspend fun removeRecentVehicleAddress(address: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_VEHICLES]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            prefs[Keys.RECENT_VEHICLES] = current.filter { it != address }.joinToString("\n")
        }
    }

    suspend fun setDebugEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEBUG_ENABLED] = enabled }
    }
}
