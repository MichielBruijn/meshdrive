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
 *
 * Keyed by vehicleId (VehiclePairing.vehicleId - derived from the vehicle's
 * PairingSecret), not by address: a vehicle's Yggdrasil address now depends on
 * which Yggdrasil it happens to be running (its own built-in one, or the
 * standalone Android app - see YggdrasilVpnService's VPN-slot-detection doc),
 * so the same vehicle can legitimately show up at more than one address across
 * sessions. [addressesForVehicle] tracks the small set of addresses a given
 * vehicleId has actually been seen at, so re-scanning its QR after an address
 * change just adds to that set instead of creating a second "known vehicle"
 * entry for what is really the same one.
 */
class ModePrefs(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("app_mode")
        val RECENT_VEHICLES = stringPreferencesKey("recent_vehicle_addresses")
        val VEHICLE_NAMES = stringPreferencesKey("vehicle_names")
        val DEBUG_ENABLED = booleanPreferencesKey("debug_enabled")
        val DEBUG_FILE_LOGGING_ENABLED = booleanPreferencesKey("debug_file_logging_enabled")
        val AUTO_QUALITY = booleanPreferencesKey("auto_quality_enabled")
        val LAST_VIDEO_QUALITY = intPreferencesKey("last_video_quality")
        val SAFE_STEERING_ENABLED = booleanPreferencesKey("safe_steering_enabled")
        val SAFE_STEERING_MAX_SPEED_KMH = intPreferencesKey("safe_steering_max_speed_kmh")
        val SAFE_STEERING_REDUCTION_PERCENT = intPreferencesKey("safe_steering_reduction_percent")
        val TELEMETRY_ENABLED = booleanPreferencesKey("telemetry_enabled")
        val BOARD_STEER_CHANNEL = intPreferencesKey("board_steer_channel")
        val BOARD_THROTTLE_CHANNEL = intPreferencesKey("board_throttle_channel")
        val BOARD_STEER_MIN_US = intPreferencesKey("board_steer_min_us")
        val BOARD_STEER_CENTER_US = intPreferencesKey("board_steer_center_us")
        val BOARD_STEER_MAX_US = intPreferencesKey("board_steer_max_us")
        val BOARD_THROTTLE_MIN_US = intPreferencesKey("board_throttle_min_us")
        val BOARD_THROTTLE_CENTER_US = intPreferencesKey("board_throttle_center_us")
        val BOARD_THROTTLE_MAX_US = intPreferencesKey("board_throttle_max_us")
        val YGGDRASIL_PEERS = stringPreferencesKey("yggdrasil_peers")
        val VEHICLE_SECRETS = stringPreferencesKey("vehicle_secrets")
        val VEHICLE_ADDRESSES = stringPreferencesKey("vehicle_addresses")
        val MIGRATED_TO_VEHICLE_ID = booleanPreferencesKey("migrated_to_vehicle_id")
    }

    private val MAX_RECENT_VEHICLES = 10

    val mode: Flow<AppMode?> = context.dataStore.data.map { prefs ->
        prefs[Keys.MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }
    }

    /**
     * One-time, lossless upgrade from the pre-vehicleId app versions, where
     * RECENT_VEHICLES/VEHICLE_NAMES/VEHICLE_SECRETS were all keyed by address
     * instead (same preference keys - only what's stored under them changed).
     * Left un-migrated, every known vehicle from before the upgrade would just
     * silently stop working: secretForVehicle(vehicleId) would look up an
     * address string as if it were a vehicleId and never find it, same for the
     * name. Confirmed live as a real risk during that refactor: nothing here
     * *needs* a rescan to fix - the vehicleId is deterministically derivable
     * from the very secret already on hand (VehiclePairing.vehicleId(secret)),
     * so this just re-keys everything to match instead.
     *
     * Old (address) vs. new (vehicleId) keys are told apart by whether they
     * contain ':' - every Yggdrasil address does (it's IPv6), no vehicleId
     * ever can (it's URL-safe base64: only [A-Za-z0-9_-]). Safe to call on
     * every launch - a no-op past the first one (MIGRATED_TO_VEHICLE_ID), and
     * only ever touches address-keyed entries even if some already-migrated
     * ones exist alongside them (e.g. a vehicle paired for the first time on
     * a build in between).
     */
    suspend fun migrateToVehicleIdKeysIfNeeded() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.MIGRATED_TO_VEHICLE_ID] == true) return@edit
            val oldSecrets = decodeVehicleSecrets(prefs[Keys.VEHICLE_SECRETS])
            val addressKeyed = oldSecrets.filterKeys { it.contains(":") }
            if (addressKeyed.isNotEmpty()) {
                val oldNames = decodeVehicleNames(prefs[Keys.VEHICLE_NAMES])
                val oldRecent = prefs[Keys.RECENT_VEHICLES]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
                val existingSecrets = (oldSecrets - addressKeyed.keys).toMutableMap()
                val existingNames = (oldNames.filterKeys { !it.contains(":") }).toMutableMap()
                val existingAddresses = decodeAddressSets(prefs[Keys.VEHICLE_ADDRESSES]).toMutableMap()
                val newRecentOrder = mutableListOf<String>()
                for (address in oldRecent) {
                    val secret = addressKeyed[address] ?: continue
                    val vehicleId = nu.bruijn.rcvehicle.control.VehiclePairing.vehicleId(secret)
                    existingSecrets[vehicleId] = secret
                    oldNames[address]?.let { existingNames[vehicleId] = it }
                    existingAddresses[vehicleId] = (existingAddresses[vehicleId] ?: emptyList()) + address
                    if (vehicleId !in newRecentOrder) newRecentOrder += vehicleId
                }
                prefs[Keys.VEHICLE_SECRETS] = encodeVehicleSecrets(existingSecrets)
                prefs[Keys.VEHICLE_NAMES] = encodeVehicleNames(existingNames)
                prefs[Keys.VEHICLE_ADDRESSES] = encodeAddressSets(existingAddresses)
                prefs[Keys.RECENT_VEHICLES] = newRecentOrder.joinToString("\n")
            }
            prefs[Keys.MIGRATED_TO_VEHICLE_ID] = true
        }
    }

    /** Most-recently-used first, so the list doubles as "last used" without a
     * separate key. Values are vehicleIds (VehiclePairing.vehicleId), not
     * addresses - see class doc. */
    val recentVehicleIds: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.RECENT_VEHICLES]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    }

    private fun decodeAddressSets(raw: String?): Map<String, List<String>> =
        raw?.split("\n")?.filter { it.isNotBlank() }
            ?.mapNotNull { line ->
                line.split("=", limit = 2).let {
                    if (it.size != 2) return@let null
                    it[0] to it[1].split(",").filter { addr -> addr.isNotBlank() }
                }
            }
            ?.toMap() ?: emptyMap()

    private fun encodeAddressSets(sets: Map<String, List<String>>): String =
        sets.entries.joinToString("\n") { "${it.key}=${it.value.joinToString(",")}" }

    /** The addresses [vehicleId] has actually been seen at, ordered by which one
     * most recently actually worked (see [markAddressReachable]) - typically one
     * entry, occasionally two (built-in Yggdrasil vs. the standalone app each
     * give it a different address). ControllerActivity probes/tries all of them,
     * in this order, rather than picking just one. */
    suspend fun addressesForVehicle(vehicleId: String): List<String> =
        decodeAddressSets(context.dataStore.data.first()[Keys.VEHICLE_ADDRESSES])[vehicleId] ?: emptyList()

    /** Bumps [address] to the front of [vehicleId]'s address list - called
     * whenever it's confirmed reachable (a successful probe or connect), so a
     * flaky/stale second address (e.g. from an Yggdrasil variant no longer
     * running) sinks to the back instead of continuing to be tried first. */
    suspend fun markAddressReachable(vehicleId: String, address: String) = addAddressForVehicle(vehicleId, address)

    private suspend fun addAddressForVehicle(vehicleId: String, address: String) {
        context.dataStore.edit { prefs ->
            val current = decodeAddressSets(prefs[Keys.VEHICLE_ADDRESSES])
            val existing = current[vehicleId] ?: emptyList()
            val updated = listOf(address) + existing.filter { it != address }
            prefs[Keys.VEHICLE_ADDRESSES] = encodeAddressSets(current + (vehicleId to updated))
        }
    }

    private suspend fun removeVehicleAddresses(vehicleId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VEHICLE_ADDRESSES] = encodeAddressSets(decodeAddressSets(prefs[Keys.VEHICLE_ADDRESSES]) - vehicleId)
        }
    }

    /** Records a sighting of [vehicleId] at [address] with pairing secret
     * [secret] - from a fresh QR scan, or a repeat one after the vehicle's
     * address changed. Bumps it to the front of the recent list either way, and
     * merges [address] into its known-addresses set rather than replacing it,
     * so a vehicle seen via both the built-in and standalone Yggdrasil keeps
     * both addresses on hand. */
    suspend fun recordVehicleSighting(vehicleId: String, address: String, secret: ByteArray) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_VEHICLES]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            val updated = (listOf(vehicleId) + current.filter { it != vehicleId }).take(MAX_RECENT_VEHICLES)
            prefs[Keys.RECENT_VEHICLES] = updated.joinToString("\n")
        }
        addAddressForVehicle(vehicleId, address)
        setVehicleSecret(vehicleId, secret)
    }

    /** Off by default - raw addresses, logs, the FPS/latency overlay, and the wifi
     * fallback/debug-slider controls are hidden until explicitly turned on, see
     * MainActivity's toggle. Everything needed for normal day-to-day driving (USB
     * connect, QR pairing, joystick, quality picker) stays visible regardless. */
    val debugEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[Keys.DEBUG_ENABLED] ?: false }

    /** Off by default - a plain-text log file in app-external storage
     * (DebugFileLogger), separate from debugEnabled above (that's about what's
     * shown on screen, this is about what's saved to disk). Built after a whole
     * night of debugging VPN/network-churn issues needed exactly this, done by
     * hand over adb each time. */
    val debugFileLoggingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[Keys.DEBUG_FILE_LOGGING_ENABLED] ?: false }

    suspend fun setDebugFileLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEBUG_FILE_LOGGING_ENABLED] = enabled }
    }

    suspend fun setMode(mode: AppMode) {
        context.dataStore.edit { it[Keys.MODE] = mode.name }
    }

    /** Moves [vehicleId] to the front (adding it if new) without touching its
     * known addresses/secret - used when reconnecting to an already-known
     * vehicle. A fresh sighting (new address, or a first-ever pairing) goes
     * through [recordVehicleSighting] instead. */
    suspend fun touchRecentVehicle(vehicleId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_VEHICLES]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            val updated = (listOf(vehicleId) + current.filter { it != vehicleId }).take(MAX_RECENT_VEHICLES)
            prefs[Keys.RECENT_VEHICLES] = updated.joinToString("\n")
        }
    }

    suspend fun removeVehicle(vehicleId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_VEHICLES]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            prefs[Keys.RECENT_VEHICLES] = current.filter { it != vehicleId }.joinToString("\n")
        }
        removeVehicleName(vehicleId)
        removeVehicleSecret(vehicleId)
        removeVehicleAddresses(vehicleId)
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
     * VehiclePairing) - null for a vehicleId that was never actually paired this
     * way (e.g. a stale/manual entry from before authentication existed). */
    suspend fun secretForVehicle(vehicleId: String): ByteArray? {
        val secrets = decodeVehicleSecrets(context.dataStore.data.first()[Keys.VEHICLE_SECRETS])
        return secrets[vehicleId]
    }

    private suspend fun setVehicleSecret(vehicleId: String, secret: ByteArray) {
        context.dataStore.edit { prefs ->
            val current = decodeVehicleSecrets(prefs[Keys.VEHICLE_SECRETS])
            prefs[Keys.VEHICLE_SECRETS] = encodeVehicleSecrets(current + (vehicleId to secret))
        }
    }

    private suspend fun removeVehicleSecret(vehicleId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VEHICLE_SECRETS] = encodeVehicleSecrets(decodeVehicleSecrets(prefs[Keys.VEHICLE_SECRETS]) - vehicleId)
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
     * Assigns a fun, memorable name (VehicleNames.POOL) the first time a
     * vehicleId is seen, so the recent-vehicles list can show "KITT" instead of
     * a raw Yggdrasil IPv6 address - per direct request, an address shouldn't
     * need to be shown at all for a vehicle the app already knows. Stays stable
     * across app restarts (and across that vehicle's address changing) once
     * assigned; a repeat call for a vehicleId that already has a name just
     * returns it unchanged.
     */
    suspend fun nameForVehicle(vehicleId: String): String {
        vehicleNames.first()[vehicleId]?.let { return it }
        var assigned = ""
        context.dataStore.edit { prefs ->
            val current = decodeVehicleNames(prefs[Keys.VEHICLE_NAMES])
            val existing = current[vehicleId]
            if (existing != null) {
                assigned = existing
            } else {
                val used = current.values.toSet()
                assigned = VehicleNames.POOL.firstOrNull { it !in used }
                    // Pool exhausted (11th+ distinct vehicle) - reuse rather than
                    // crash or fall back to the address; a shared name is a much
                    // smaller problem than what this feature exists to avoid.
                    ?: VehicleNames.POOL[current.size % VehicleNames.POOL.size]
                prefs[Keys.VEHICLE_NAMES] = encodeVehicleNames(current + (vehicleId to assigned))
            }
        }
        return assigned
    }

    private suspend fun removeVehicleName(vehicleId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VEHICLE_NAMES] = encodeVehicleNames(decodeVehicleNames(prefs[Keys.VEHICLE_NAMES]) - vehicleId)
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

    /**
     * The last manually-picked (or auto-quality-settled-on) VideoQuality ordinal -
     * per direct request ("als het naar LOW is gegaan, volgende keer ook LOW"), so
     * a fresh connection starts at whatever quality was actually in use last time
     * instead of always defaulting back to Low. Default 1 is VideoQuality.LOW's own
     * ordinal (kept as a raw Int here rather than importing the video package, same
     * reasoning as this file's other plain-value prefs) - matches the pre-existing
     * "Low, not Auto, is the real default" choice documented on autoQualityEnabled
     * above for a fresh install/never-saved vehicle.
     */
    val lastVideoQuality: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_VIDEO_QUALITY] ?: 1 }
    suspend fun setLastVideoQuality(ordinal: Int) {
        context.dataStore.edit { it[Keys.LAST_VIDEO_QUALITY] = ordinal }
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
     * The ESP32's own configured Joystick X/Y channels and steer/throttle µs
     * calibration, as last discovered over USB (UsbServoBridge.checkJoystickChannels()/
     * checkCalibration()) - persisted (not just held in the live instance) and
     * shared with ServoBridge (the wifi fallback), which has no way to query
     * this itself: the firmware only answers GETJOYCHANNELS/GETCALIBRATION on
     * the serial line, nothing equivalent exists over HTTP/websocket. Per direct
     * request, confirmed on real hardware as a real bug - without this, wifi
     * fallback silently drove ServoLink's generic constructor defaults (steer=3/
     * throttle=4, 1000/1500/2000µs), which on this specific board are simply
     * wrong (real steer=0, throttle=3) - only one slider visibly did anything,
     * and on the wrong physical connector. Defaults here match those same
     * generic constructor values, so a board that's never been resolved over
     * USB yet behaves exactly as before this existed.
     */
    data class BoardChannels(val steer: Int, val throttle: Int)
    val boardChannels: Flow<BoardChannels> = context.dataStore.data.map {
        BoardChannels(it[Keys.BOARD_STEER_CHANNEL] ?: 3, it[Keys.BOARD_THROTTLE_CHANNEL] ?: 4)
    }
    suspend fun setBoardChannels(steer: Int, throttle: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BOARD_STEER_CHANNEL] = steer
            prefs[Keys.BOARD_THROTTLE_CHANNEL] = throttle
        }
    }

    data class BoardCalibration(
        val steerMin: Int,
        val steerCenter: Int,
        val steerMax: Int,
        val throttleMin: Int,
        val throttleCenter: Int,
        val throttleMax: Int,
    )
    val boardCalibration: Flow<BoardCalibration> = context.dataStore.data.map {
        BoardCalibration(
            it[Keys.BOARD_STEER_MIN_US] ?: 1000,
            it[Keys.BOARD_STEER_CENTER_US] ?: 1500,
            it[Keys.BOARD_STEER_MAX_US] ?: 2000,
            it[Keys.BOARD_THROTTLE_MIN_US] ?: 1000,
            it[Keys.BOARD_THROTTLE_CENTER_US] ?: 1500,
            it[Keys.BOARD_THROTTLE_MAX_US] ?: 2000,
        )
    }
    suspend fun setBoardCalibration(
        steerMin: Int,
        steerCenter: Int,
        steerMax: Int,
        throttleMin: Int,
        throttleCenter: Int,
        throttleMax: Int,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BOARD_STEER_MIN_US] = steerMin
            prefs[Keys.BOARD_STEER_CENTER_US] = steerCenter
            prefs[Keys.BOARD_STEER_MAX_US] = steerMax
            prefs[Keys.BOARD_THROTTLE_MIN_US] = throttleMin
            prefs[Keys.BOARD_THROTTLE_CENTER_US] = throttleCenter
            prefs[Keys.BOARD_THROTTLE_MAX_US] = throttleMax
        }
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
