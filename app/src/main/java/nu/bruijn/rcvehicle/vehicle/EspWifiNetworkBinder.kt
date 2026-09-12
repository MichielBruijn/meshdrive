package nu.bruijn.rcvehicle.vehicle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Obtains a handle to the wifi network the phone joined to reach the ESP32 - which
 * has no internet uplink when it's the ESP32's own AP - without touching the phone's
 * process-default network (where Yggdrasil-over-cellular keeps running unaffected).
 * Plan section 6: ESP32-facing sockets get explicitly bound to this Network
 * (OkHttp's socketFactory here); everything else on the phone is untouched.
 *
 * Requires the phone to have already joined that wifi manually once (Android will
 * prompt "no internet, stay connected?" the first time for a no-internet AP) -
 * requestNetwork() only re-attaches to it, it doesn't join new networks itself.
 */
class EspWifiNetworkBinder(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    sealed class State {
        data object Requesting : State()
        data class Available(val network: Network) : State()
        data object Lost : State()
    }

    fun observe(): Flow<State> = callbackFlow {
        trySend(State.Requesting)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(State.Available(network))
            }

            override fun onLost(network: Network) {
                trySend(State.Lost)
            }
        }

        connectivityManager.requestNetwork(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
