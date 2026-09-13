package nu.bruijn.rcvehicle.controller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.video.VideoPacket
import nu.bruijn.rcvehicle.video.VideoReceiver

/**
 * The actual driving screen, landscape-locked (manifest) - split out from
 * ControllerActivity (now pairing/setup only) so the joystick/video UI never has
 * to share space with the portrait setup form, per direct request. Connects its
 * own ControlLink using the vehicle address passed in from setup, and renders
 * the video the vehicle sends back once it has learned this phone's address from
 * that same control traffic (see ControlLink's/VideoSender's docs for why these
 * are plain UDP sockets instead of the originally-planned WebRTC data channel/
 * video track).
 */
class ControllerDriveActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VEHICLE_ADDRESS = "vehicle_address"
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_controller_drive)

        val address = intent.getStringExtra(EXTRA_VEHICLE_ADDRESS)
        val logView = findViewById<TextView>(R.id.textRtcLog)
        val surfaceVideo = findViewById<SurfaceView>(R.id.surfaceVideo)

        fun log(line: String) = runOnUiThread {
            Log.d("RCDebug", line)
            logView.append(line + "\n")
        }

        videoReceiver.onError = { msg -> log("video: $msg") }

        // Start listening for video as soon as the surface exists - independent of
        // the control-link connect below, since it's the vehicle that learns OUR
        // address (from control traffic) and initiates the video stream.
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

        if (address.isNullOrEmpty()) {
            log("no vehicle address given")
        } else {
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

        // Fixed ~33Hz tick, same reasoning as the vehicle-side debug sliders: a
        // steady heartbeat is what the vehicle-side failsafe is designed to key off.
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
