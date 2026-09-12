package nu.bruijn.rcvehicle.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.common.YggdrasilAddress
import nu.bruijn.rcvehicle.rtc.RtcClient
import nu.bruijn.rcvehicle.signaling.SignalingMessage
import nu.bruijn.rcvehicle.signaling.SignalingServer
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Debug UI: fase 2 (wifi-to-ESP32, top half) drives ServoBridge with two sliders,
 * fase 4 (WebRTC over Yggdrasil, bottom half) listens for the controller and
 * relays whatever it receives into the SAME ServoBridge - so with both sections
 * connected at once this is already a real end-to-end controller-phone ->
 * vehicle-phone -> ESP32 path, just without video yet.
 */
class VehicleActivity : AppCompatActivity() {

    private var service: VehicleForegroundService? = null
    private var bound = false
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    private var signalingServer: SignalingServer? = null
    private var rtcClient: RtcClient? = null
    private var listenJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VehicleForegroundService.LocalBinder
            service = localBinder.service
            bound = true
            service?.onStatus = { status ->
                runOnUiThread { findViewById<TextView>(R.id.textStatus).text = status }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle)

        val editIp = findViewById<EditText>(R.id.editEspIp)
        val seekSteer = findViewById<SeekBar>(R.id.seekSteer)
        val seekThrottle = findViewById<SeekBar>(R.id.seekThrottle)
        val textOwnAddress = findViewById<TextView>(R.id.textOwnYggdrasilAddress)
        val logView = findViewById<TextView>(R.id.textRtcLog)

        fun log(line: String) = runOnUiThread {
            Log.d("RCDebug", line)
            logView.append(line + "\n")
        }

        findViewById<Button>(R.id.buttonConnect).setOnClickListener {
            service?.connectToEsp(editIp.text.toString().trim())
        }

        lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) { YggdrasilAddress.detect() }
            textOwnAddress.text = if (address != null) {
                "Eigen Yggdrasil-adres: $address"
            } else {
                "Eigen Yggdrasil-adres: niet gevonden (Yggdrasil-app actief?)"
            }
        }

        findViewById<Button>(R.id.buttonListen).setOnClickListener {
            listenJob?.cancel()
            signalingServer?.close()
            rtcClient?.close()
            logView.text = ""

            listenJob = lifecycleScope.launch {
                log("listening on port ${SignalingMessage.PORT}…")
                val server = SignalingServer().also { signalingServer = it }
                val session = try {
                    server.acceptSession()
                } catch (e: Exception) {
                    log("accept failed: ${e.message}")
                    return@launch
                }
                log("controller connected")

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
                rtc.onControlReceived = { steer, throttle, seq ->
                    log("control #$seq: steer=$steer throttle=$throttle")
                    service?.servoBridge?.sendControl(steer, throttle)
                }
                rtc.createPeerConnection()

                session.messages().collect { msg ->
                    when (msg) {
                        is SignalingMessage.Offer -> {
                            rtc.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, msg.sdp))
                            val answer = rtc.createAnswer()
                            session.send(SignalingMessage.Answer(answer.description))
                            log("answer sent")
                        }
                        is SignalingMessage.Ice -> {
                            rtc.addIceCandidate(IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate))
                        }
                        else -> {}
                    }
                }
            }
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Snap back to center on release, like a real self-centering joystick.
                seekBar?.progress = 100
            }
        }
        seekSteer.setOnSeekBarChangeListener(listener)
        seekThrottle.setOnSeekBarChangeListener(listener)

        // Send on a fixed 20Hz tick regardless of whether the value changed - matches
        // the plan's eventual WebRTC data-channel design (section 4). Sending only on
        // SeekBar's own onProgressChanged events was the earlier bug: touch-move events
        // don't fire at a guaranteed steady rate, so gaps over 300ms kept tripping
        // ServoBridge's own failsafe mid-drag, snapping to center and back - that's the
        // jitter. A steady heartbeat is what the failsafe is designed to key off.
        tickRunnable = object : Runnable {
            override fun run() {
                val steer = (seekSteer.progress - 100) / 100f
                val throttle = (seekThrottle.progress - 100) / 100f
                service?.servoBridge?.sendControl(steer, throttle)
                tickHandler.postDelayed(this, 30) // ~33Hz, matching the browser UI's own throttle rate
            }
        }
        tickHandler.post(tickRunnable!!)

        val intent = Intent(this, VehicleForegroundService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        listenJob?.cancel()
        signalingServer?.close()
        rtcClient?.close()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
