package nu.bruijn.rcvehicle.controller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.video.VideoPacket
import nu.bruijn.rcvehicle.video.VideoReceiver

/**
 * Real controller screen: video fills the background, steering joystick bottom-
 * left (x axis), throttle joystick bottom-right (y axis, up = forward), STOP
 * button always available, Steering Limit checkbox. The pairing/log strip at the
 * top is only needed before/while connecting - it stays visible throughout for
 * now since there's no "connected, hide setup" state tracked yet.
 *
 * Sends steer/throttle to the vehicle over a direct UDP socket to its known
 * Yggdrasil address, and renders the video the vehicle sends back once it has
 * learned this phone's address from that same control traffic (see
 * ControlLink's/VideoSender's docs for why these are plain UDP sockets instead of
 * the originally-planned WebRTC data channel/video track).
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var modePrefs: ModePrefs
    private val controlLink = ControlLink()
    private val videoReceiver = VideoReceiver()
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var seq = 0

    // Latest joystick positions, read by the tick loop - not sent directly from
    // onMove itself, so the send rate stays a steady ~33Hz regardless of touch
    // event frequency (same reasoning as the vehicle-side debug sliders).
    private var steerX = 0f
    private var throttleY = 0f

    // Must be registered unconditionally at construction time (Activity Result API
    // requirement, before onCreate/onStart), not inside onCreate's click listener.
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            findViewById<EditText>(R.id.editVehicleAddress).setText(scanned.trim())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)
        modePrefs = ModePrefs(applicationContext)

        val editAddress = findViewById<EditText>(R.id.editVehicleAddress)
        val logView = findViewById<TextView>(R.id.textRtcLog)
        val surfaceVideo = findViewById<SurfaceView>(R.id.surfaceVideo)

        lifecycleScope.launch {
            modePrefs.pairedYggdrasilAddress.collect { saved ->
                if (saved != null && editAddress.text.isEmpty()) editAddress.setText(saved)
            }
        }

        fun log(line: String) = runOnUiThread {
            Log.d("RCDebug", line)
            logView.append(line + "\n")
        }

        videoReceiver.onError = { msg -> log("video: $msg") }

        // Start listening for video as soon as the surface exists - independent of
        // "Verbinden", since it's the vehicle that learns OUR address (from control
        // traffic) and initiates the video stream, not the other way around.
        surfaceVideo.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    videoReceiver.start(VideoPacket.PORT, holder.surface)
                    log("listening for video on port ${VideoPacket.PORT}")
                } catch (e: Exception) {
                    log("video listen failed: ${e::class.simpleName}: ${e.message}")
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                videoReceiver.stop()
            }
        })

        findViewById<Button>(R.id.buttonScanQr).setOnClickListener {
            qrScanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan de QR-code op het scherm van de voertuig-telefoon")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }

        findViewById<Button>(R.id.buttonConnectRtc).setOnClickListener {
            val address = editAddress.text.toString().trim()
            if (address.isEmpty()) return@setOnClickListener

            lifecycleScope.launch { modePrefs.setPairedYggdrasilAddress(address) }
            logView.text = ""
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    controlLink.connect(address, ControlLink.PORT)
                    log("UDP control link connected to $address:${ControlLink.PORT}")
                } catch (e: Exception) {
                    log("connect failed: ${e::class.simpleName}: ${e.message}")
                }
            }
        }

        findViewById<CheckBox>(R.id.checkSteerLimit).setOnCheckedChangeListener { _, checked ->
            controlLink.sendSteerLimit(checked)
        }

        findViewById<Button>(R.id.buttonStop).setOnClickListener {
            steerX = 0f
            throttleY = 0f
            controlLink.send(0f, 0f, seq++)
        }

        findViewById<JoystickView>(R.id.joystickSteer).onMove = { x, _ -> steerX = x }
        // Touch y increases downward; up (forward throttle) must be positive, so negate.
        findViewById<JoystickView>(R.id.joystickThrottle).onMove = { _, y -> throttleY = -y }

        // Fixed ~33Hz tick, same reasoning as the fase-2 debug UI: a steady heartbeat
        // is what the vehicle-side failsafe is designed to key off, not event-driven sends.
        tickRunnable = object : Runnable {
            override fun run() {
                controlLink.send(steerX, throttleY, seq++)
                tickHandler.postDelayed(this, 30)
            }
        }
        tickHandler.post(tickRunnable!!)
    }

    override fun onDestroy() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        controlLink.close()
        videoReceiver.stop()
        super.onDestroy()
    }
}
