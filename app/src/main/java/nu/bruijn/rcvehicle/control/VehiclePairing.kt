package nu.bruijn.rcvehicle.control

import android.util.Base64

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
}
