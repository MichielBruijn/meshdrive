package nu.bruijn.rcvehicle.control

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * A per-vehicle 32-byte secret, generated once and persisted, that authenticates
 * ControlLink traffic (see SignedFrame) - shared with a controller only by
 * physically scanning the vehicle's QR code (VehicleActivity), never transmitted
 * over the network on its own. Per direct request: the vehicle's Yggdrasil
 * address stopped being enough of a secret once the node joined the public mesh
 * (see YggdrasilVpnService's doc) - this is what actually gates who can drive.
 * Losing this file (app data wipe) means every paired controller needs to
 * re-scan - deliberately never regenerated once it exists, same reasoning as
 * YggdrasilVpnService's own identity key.
 */
object PairingSecret {
    private const val SIZE_BYTES = 32
    private const val FILE_NAME = "pairing-secret.bin"

    fun load(context: Context): ByteArray {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists() && file.length() == SIZE_BYTES.toLong()) return file.readBytes()
        val secret = ByteArray(SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        file.writeBytes(secret)
        return secret
    }
}
