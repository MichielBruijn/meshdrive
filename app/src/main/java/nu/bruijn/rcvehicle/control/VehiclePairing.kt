package nu.bruijn.rcvehicle.control

import android.util.Base64
import java.security.MessageDigest

/**
 * Wire format for the QR code (and the debug-mode manual-paste field): the
 * vehicle's Yggdrasil address and its PairingSecret, joined so a controller
 * learns both from one scan - see PairingSecret/SignedFrame's docs for why the
 * address alone isn't enough to control a vehicle anymore.
 */
object VehiclePairing {
    private const val SEPARATOR = "|"

    fun encode(address: String, secret: ByteArray): String =
        address + SEPARATOR + Base64.encodeToString(secret, Base64.NO_WRAP or Base64.URL_SAFE)

    /** Null if [text] isn't a valid "address|secret" pairing string - e.g. a bare
     * address pasted without its secret (an old QR/pairing predating this, or a
     * typo). Callers should treat that as "not a pairable vehicle," never fall
     * back to connecting unauthenticated. */
    fun decode(text: String): Pair<String, ByteArray>? {
        val parts = text.trim().split(SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        val secret = runCatching { Base64.decode(parts[1], Base64.NO_WRAP or Base64.URL_SAFE) }.getOrNull()
        if (secret == null || secret.size != 32) return null
        return parts[0] to secret
    }

    /**
     * A stable per-vehicle identifier derived from its PairingSecret, safe to use
     * as a lookup key (and to log) without exposing the secret itself. Since the
     * secret never changes for a given vehicle install (PairingSecret.load's
     * doc), this stays the same across a vehicle's whole lifetime even though its
     * Yggdrasil address can now differ from one session to the next - built-in
     * Yggdrasil vs. the standalone Android app each hand out a different address
     * (see ModePrefs' vehicle-addresses doc). ModePrefs keys everything about a
     * known vehicle (name, secret, the set of addresses it's been seen at) on
     * this rather than on a single address, so re-scanning the same vehicle's QR
     * after it switched address just adds to that set instead of creating a
     * second, duplicate "known vehicle."
     */
    fun vehicleId(secret: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(secret)
        // NO_PADDING: ModePrefs stores this as the key in its own "key=value"
        // text format - a padding '=' in the key would collide with that
        // separator and truncate it on decode (confirmed live: broke every
        // secret/name/address lookup for the vehicle with a "=" in its id).
        return Base64.encodeToString(hash.copyOf(16), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
}
