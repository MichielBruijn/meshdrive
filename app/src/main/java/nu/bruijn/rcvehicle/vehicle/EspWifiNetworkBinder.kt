package nu.bruijn.rcvehicle.vehicle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Obtains a handle to the wifi network the phone joined to reach the ESP32 ("MespDrive") -
 * which has no internet uplink when it's the ESP32's own AP - without touching the phone's
 * process-default network (where Yggdrasil-over-cellular keeps running unaffected).
 * ESP32-facing sockets get explicitly bound to this Network (OkHttp's socketFactory in
 * WifiFirmwareUpdater); everything else on the phone is untouched.
 *
 * Requires the phone to have already joined that wifi manually once (Android will prompt
 * "no internet, stay connected?" the first time for a no-internet AP) - requestNetwork()
 * only re-attaches to it, it doesn't join new networks itself.
 *
 * Filters by SSID, not just "any wifi without internet" - confirmed on real hardware as a
 * real gap: removeCapability(INTERNET)/removeCapability(VALIDATED) only drop those from the
 * REQUIRED set, they don't exclude networks that happen to have them anyway - so a phone
 * also connected to a normal home wifi got matched instead of the ESP32's AP, and its own
 * socket connect then timed out trying to reach 192.168.4.1 from a completely different
 * subnet. Reading the real SSID via WifiInfo needs location permission actually granted at
 * runtime (declared in the manifest already, ACCESS_FINE_LOCATION) - without it Android
 * returns "<unknown ssid>" and this never matches.
 */
class EspWifiNetworkBinder(context: Context, private val ssid: String = "MespDrive") {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    sealed class State {
        data object Requesting : State()
        data class Available(val network: Network) : State()
        data object Lost : State()
    }

    // Returns null (can't tell) rather than false specifically for the redacted-SSID case -
    // reading the real SSID off WifiInfo needs the app to hold ACCESS_FINE_LOCATION *and* the
    // phone's system-wide Location toggle to be on; with either missing, Android hands back
    // the literal string "<unknown ssid>" instead of throwing or refusing the callback. That's
    // indistinguishable at this layer from "this genuinely isn't MespDrive" unless treated as
    // its own case - confirmed on real hardware as a real gap: with Location off, no network
    // could ever satisfy an exact-match check here, so the wifi step just hung forever.
    private fun matchesSsid(caps: NetworkCapabilities): Boolean? {
        val wifiInfo = caps.transportInfo as? WifiInfo ?: return false
        // WifiInfo.getSSID() returns the SSID double-quoted (e.g. "\"MespDrive\"") unless
        // hidden/unreadable, in which case it's the literal string "<unknown ssid>".
        val reported = wifiInfo.ssid.trim('"')
        if (reported == "<unknown ssid>") return null
        return reported == ssid
    }

    fun observe(): Flow<State> = callbackFlow {
        trySend(State.Requesting)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        // Matched via onCapabilitiesChanged, not onAvailable - WifiInfo (and thus the SSID)
        // isn't reliably populated yet at onAvailable time on every Android version, but
        // onCapabilitiesChanged fires at least once right after with the real capabilities.
        var sentAvailable = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // null (SSID unreadable, see matchesSsid's doc) is treated as "accept" - the
                // NetworkRequest below already narrows this to a wifi network Android isn't
                // requiring internet/validation from, which is as close to "the ESP32's AP" as
                // this layer can tell without a readable SSID.
                if (matchesSsid(caps) != false) {
                    if (!sentAvailable) {
                        sentAvailable = true
                        trySend(State.Available(network))
                    }
                } else if (sentAvailable) {
                    sentAvailable = false
                    trySend(State.Lost)
                }
            }

            override fun onLost(network: Network) {
                sentAvailable = false
                trySend(State.Lost)
            }
        }

        connectivityManager.requestNetwork(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
