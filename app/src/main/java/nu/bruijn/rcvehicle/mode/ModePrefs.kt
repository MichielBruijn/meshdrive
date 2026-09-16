package nu.bruijn.rcvehicle.mode

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nu.bruijn.rcvehicle.yggdrasil.YggdrasilPeerListCache

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
        val VEHICLE_NAMES = stringPreferencesKey("vehicle_names")
        val DEBUG_ENABLED = booleanPreferencesKey("debug_enabled")
        val AUTO_QUALITY = booleanPreferencesKey("auto_quality_enabled")
        val SAFE_STEERING_ENABLED = booleanPreferencesKey("safe_steering_enabled")
        val SAFE_STEERING_MAX_SPEED_KMH = intPreferencesKey("safe_steering_max_speed_kmh")
        val SAFE_STEERING_REDUCTION_PERCENT = intPreferencesKey("safe_steering_reduction_percent")
        val TELEMETRY_ENABLED = booleanPreferencesKey("telemetry_enabled")
        val YGGDRASIL_PEERS = stringPreferencesKey("yggdrasil_peers")
        val VEHICLE_SECRETS = stringPreferencesKey("vehicle_secrets")
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
        removeVehicleName(address)
        removeVehicleSecret(address)
    }

    private fun decodeVehicleSecrets(raw: String?): Map<String, ByteArray> =
        raw?.split("\n")?.filter { it.isNotBlank() }
            ?.mapNotNull { line ->
                line.split("=", limit = 2).let {
                    if (it.size != 2) return@let null
                    val secret = runCatching { Base64.decode(it[1], Base64.NO_WRAP) }.getOrNull() ?: return@let null
                    it[0] to secret
                }
            }
            ?.toMap() ?: emptyMap()

    private fun encodeVehicleSecrets(secrets: Map<String, ByteArray>): String =
        secrets.entries.joinToString("\n") { "${it.key}=${Base64.encodeToString(it.value, Base64.NO_WRAP)}" }

    /** The PairingSecret learned when this vehicle's QR was scanned (see
     * VehiclePairing) - null for an address that was never actually paired this
     * way (e.g. a stale/manual entry from before authentication existed). */
    suspend fun secretForVehicle(address: String): ByteArray? {
        val secrets = decodeVehicleSecrets(context.dataStore.data.first()[Keys.VEHICLE_SECRETS])
        return secrets[address]
    }

    suspend fun setVehicleSecret(address: String, secret: ByteArray) {
        context.dataStore.edit { prefs ->
            val current = decodeVehicleSecrets(prefs[Keys.VEHICLE_SECRETS])
            prefs[Keys.VEHICLE_SECRETS] = encodeVehicleSecrets(current + (address to secret))
        }
    }

    private suspend fun removeVehicleSecret(address: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VEHICLE_SECRETS] = encodeVehicleSecrets(decodeVehicleSecrets(prefs[Keys.VEHICLE_SECRETS]) - address)
        }
    }

    private fun decodeVehicleNames(raw: String?): Map<String, String> =
        raw?.split("\n")?.filter { it.isNotBlank() }
            ?.mapNotNull { line -> line.split("=", limit = 2).let { if (it.size == 2) it[0] to it[1] else null } }
            ?.toMap() ?: emptyMap()

    private fun encodeVehicleNames(names: Map<String, String>): String =
        names.entries.joinToString("\n") { "${it.key}=${it.value}" }

    val vehicleNames: Flow<Map<String, String>> = context.dataStore.data.map { decodeVehicleNames(it[Keys.VEHICLE_NAMES]) }

    /**
     * Assigns a fun, memorable name (VehicleNames.POOL) the first time an address
     * is seen, so the recent-vehicles list can show "KITT" instead of a raw
     * Yggdrasil IPv6 address - per direct request, an address shouldn't need to be
     * shown at all for a vehicle the app already knows. Stays stable across app
     * restarts once assigned; a repeat call for an address that already has a name
     * just returns it unchanged.
     */
    suspend fun nameForVehicle(address: String): String {
        vehicleNames.first()[address]?.let { return it }
        var assigned = ""
        context.dataStore.edit { prefs ->
            val current = decodeVehicleNames(prefs[Keys.VEHICLE_NAMES])
            val existing = current[address]
            if (existing != null) {
                assigned = existing
            } else {
                val used = current.values.toSet()
                assigned = VehicleNames.POOL.firstOrNull { it !in used }
                    // Pool exhausted (11th+ distinct vehicle) - reuse rather than
                    // crash or fall back to the address; a shared name is a much
                    // smaller problem than what this feature exists to avoid.
                    ?: VehicleNames.POOL[current.size % VehicleNames.POOL.size]
                prefs[Keys.VEHICLE_NAMES] = encodeVehicleNames(current + (address to assigned))
            }
        }
        return assigned
    }

    private suspend fun removeVehicleName(address: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VEHICLE_NAMES] = encodeVehicleNames(decodeVehicleNames(prefs[Keys.VEHICLE_NAMES]) - address)
        }
    }

    suspend fun setDebugEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEBUG_ENABLED] = enabled }
    }

    /**
     * Driving-screen settings that should survive a restart and carry over to
     * whichever vehicle gets connected next, not just stay checked cosmetically -
     * ControllerDriveActivity re-sends the underlying ControlFrame messages once
     * connected, since a fresh vehicle doesn't otherwise know these were on.
     */
    // Per direct request: LOW, not Auto, is the actual default for a fresh
    // install or a vehicle with no saved preference yet - a deliberate,
    // predictable starting bandwidth rather than "whatever Auto happens to
    // pick" the first time a video link is established.
    val autoQualityEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_QUALITY] ?: false }
    suspend fun setAutoQualityEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_QUALITY] = enabled }
    }

    val safeSteeringEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.SAFE_STEERING_ENABLED] ?: false }
    suspend fun setSafeSteeringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SAFE_STEERING_ENABLED] = enabled }
    }

    val safeSteeringMaxSpeedKmh: Flow<Int> = context.dataStore.data.map { it[Keys.SAFE_STEERING_MAX_SPEED_KMH] ?: 10 }
    val safeSteeringReductionPercent: Flow<Int> = context.dataStore.data.map { it[Keys.SAFE_STEERING_REDUCTION_PERCENT] ?: 50 }
    suspend fun setSafeSteeringConfig(maxSpeedKmh: Int, reductionPercent: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SAFE_STEERING_MAX_SPEED_KMH] = maxSpeedKmh
            prefs[Keys.SAFE_STEERING_REDUCTION_PERCENT] = reductionPercent
        }
    }

    /** Debug GPS/speed overlay checkbox - see ControlFrame.TelemetryEnable's doc. */
    val telemetryEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.TELEMETRY_ENABLED] ?: true }
    suspend fun setTelemetryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TELEMETRY_ENABLED] = enabled }
    }

    /**
     * User-entered Yggdrasil peer URIs (e.g. "tls://host:port"), from the debug
     * field on the mode picker - see [yggdrasilPeerCandidates] for what actually
     * gets passed to YggdrasilController.start(). Empty by default and never
     * pre-filled: this stays purely "what the user typed," so a saved custom or
     * test peer is never silently mixed with YggdrasilBootstrapPeers' list.
     */
    val yggdrasilPeers: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.YGGDRASIL_PEERS]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    }
    suspend fun setYggdrasilPeers(peers: List<String>) {
        context.dataStore.edit { it[Keys.YGGDRASIL_PEERS] = peers.joinToString("\n") }
    }

    /**
     * The actual candidate pool for YggdrasilController.start(): the user's own
     * [yggdrasilPeers] if they've set any, otherwise Michiel's own node plus
     * YggdrasilPeerListCache's community-peer pool (live-fetched monthly,
     * falling back to YggdrasilBootstrapPeers' baked-in snapshot) - so a fresh
     * install can still reach the mesh without the user needing to find/paste
     * a peer first, while any custom (or a fleet's future private-relay)
     * entry always fully overrides this whole pool rather than being merged
     * with it - a deliberate, explicit choice shouldn't get diluted by peers
     * the user didn't pick.
     */
    val yggdrasilPeerCandidates: Flow<List<String>> = yggdrasilPeers.map { custom ->
        custom.ifEmpty {
            (listOf(OWN_NODE) + YggdrasilPeerListCache.get(context)).distinct()
        }
    }

    private companion object {
        // Always in the auto-selected pool per direct request - a hostname
        // (dual-stack A+AAAA), not an IP literal, so DNS/PeerLatencyProbe picks
        // whichever family actually works rather than being pinned to one.
        const val OWN_NODE = "tcp://ygg.bruijn.nu:65535"
    }
}
