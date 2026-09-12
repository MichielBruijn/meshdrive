package nu.bruijn.rcvehicle.signaling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Controller side: connects to the vehicle's known Yggdrasil address (plan section 3). */
class SignalingClient {
    suspend fun connect(
        vehicleYggdrasilAddress: String,
        port: Int = SignalingMessage.PORT,
        timeoutMs: Int = 5000,
    ): SignalingSession = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.connect(InetSocketAddress(vehicleYggdrasilAddress, port), timeoutMs)
        SignalingSession(socket)
    }
}
