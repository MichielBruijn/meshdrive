package nu.bruijn.rcvehicle.signaling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket

/**
 * Vehicle side: listens on a fixed port for the controller to connect directly
 * over Yggdrasil (plan section 3). No binding to a specific local address is
 * needed - only Yggdrasil delivers packets addressed to this phone's 200::/7
 * address in the first place, so the wildcard bind is fine.
 */
class SignalingServer(private val port: Int = SignalingMessage.PORT) {
    private var serverSocket: ServerSocket? = null

    /** Blocks (on IO) until a controller connects, then returns that session. */
    suspend fun acceptSession(): SignalingSession = withContext(Dispatchers.IO) {
        val server = ServerSocket(port).also { serverSocket = it }
        val socket = server.accept()
        SignalingSession(socket)
    }

    fun close() {
        runCatching { serverSocket?.close() }
    }
}
