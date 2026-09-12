package nu.bruijn.rcvehicle.controller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.rtc.RtcClient
import nu.bruijn.rcvehicle.signaling.SignalingClient
import nu.bruijn.rcvehicle.signaling.SignalingMessage
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * Fase 4 debug UI (plan section 3-4): connects to the vehicle over a direct
 * Yggdrasil TCP socket, exchanges SDP/ICE, then drives a WebRTC data channel with
 * two sliders. No video track yet - that's the plan's next pass, once this proves
 * host-candidate ICE actually works over the Yggdrasil tun interface.
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var modePrefs: ModePrefs
    private var rtcClient: RtcClient? = null
    private var signalingJob: Job? = null
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var seq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)
        modePrefs = ModePrefs(applicationContext)

        val editAddress = findViewById<EditText>(R.id.editVehicleAddress)
        val logView = findViewById<TextView>(R.id.textRtcLog)
        val seekSteer = findViewById<SeekBar>(R.id.seekSteer)
        val seekThrottle = findViewById<SeekBar>(R.id.seekThrottle)

        lifecycleScope.launch {
            modePrefs.pairedYggdrasilAddress.collect { saved ->
                if (saved != null && editAddress.text.isEmpty()) editAddress.setText(saved)
            }
        }

        fun log(line: String) = runOnUiThread {
            Log.d("RCDebug", line)
            logView.append(line + "\n")
        }

        findViewById<Button>(R.id.buttonConnectRtc).setOnClickListener {
            val address = editAddress.text.toString().trim()
            if (address.isEmpty()) return@setOnClickListener

            signalingJob?.cancel()
            rtcClient?.close()
            logView.text = ""

            signalingJob = lifecycleScope.launch {
                lifecycleScope.launch { modePrefs.setPairedYggdrasilAddress(address) }
                log("connecting signaling socket to $address:${SignalingMessage.PORT}…")
                val session = try {
                    SignalingClient().connect(address)
                } catch (e: Exception) {
                    log("signaling connect failed: ${e.message}")
                    return@launch
                }
                log("signaling connected")

                val rtc = RtcClient(applicationContext).also { rtcClient = it }
                rtc.onIceCandidate = { candidate ->
                    log("local ICE candidate: ${candidate.sdp}")
                    lifecycleScope.launch {
                        session.send(SignalingMessage.Ice(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
                    }
                }
                rtc.onIceConnectionChange = { state -> log("ICE state: $state") }
                rtc.onIceGatheringChange = { state -> log("ICE gathering: $state") }
                rtc.onDataChannelOpen = { log("data channel OPEN") }
                rtc.createPeerConnection()
                rtc.createControlDataChannel()

                lifecycleScope.launch {
                    session.messages().collect { msg ->
                        when (msg) {
                            is SignalingMessage.Answer -> {
                                rtc.setRemoteDescription(
                                    SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
                                )
                                log("remote answer set")
                            }
                            is SignalingMessage.Ice -> {
                                rtc.addIceCandidate(IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate))
                            }
                            else -> {}
                        }
                    }
                }

                val offer = rtc.createOffer()
                session.send(SignalingMessage.Offer(offer.description))
                log("offer sent")
            }
        }

        // Fixed ~33Hz tick, same reasoning as the fase-2 debug UI: a steady heartbeat
        // is what the vehicle-side failsafe is designed to key off, not event-driven sends.
        tickRunnable = object : Runnable {
            override fun run() {
                val steer = (seekSteer.progress - 100) / 100f
                val throttle = (seekThrottle.progress - 100) / 100f
                rtcClient?.sendControl(steer, throttle, seq++)
                tickHandler.postDelayed(this, 30)
            }
        }
        tickHandler.post(tickRunnable!!)

        val centerOnRelease = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress = 100
            }
        }
        seekSteer.setOnSeekBarChangeListener(centerOnRelease)
        seekThrottle.setOnSeekBarChangeListener(centerOnRelease)
    }

    override fun onDestroy() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        rtcClient?.close()
        super.onDestroy()
    }
}
