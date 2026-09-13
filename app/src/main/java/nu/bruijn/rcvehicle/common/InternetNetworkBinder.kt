package nu.bruijn.rcvehicle.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Obtains a handle to whichever network currently has real, validated internet -
 * the counterpart to EspWifiNetworkBinder's "wifi without internet" request. Needed
 * because a plain, unbound OkHttpClient just follows the phone's current default
 * route, which can silently BE the ESP32's own no-internet AP once the phone has
 * joined it (confirmed on real hardware: FirmwareUpdater's GitHub check/download
 * failed while connected to the ESP32's AP, since that network was what got picked
 * as the active route). Explicitly requesting NET_CAPABILITY_INTERNET sidesteps
 * that regardless of which network Android currently treats as default.
 */
class InternetNetworkBinder(context: Context) {
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
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
