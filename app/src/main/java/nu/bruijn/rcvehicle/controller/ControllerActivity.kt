package nu.bruijn.rcvehicle.controller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.video.VideoPacket
import nu.bruijn.rcvehicle.video.VideoReceiver

/**
 * Fase 4 debug UI: sends steer/throttle to the vehicle over a direct UDP socket to
 * its known Yggdrasil address, and renders the video the vehicle sends back once
 * it has learned this phone's address from that same control traffic (see
 * ControlLink's/VideoSender's docs for why these are plain UDP sockets instead of
 * the originally-planned WebRTC data channel/video track - stream-webrtc-android's
 * ICE never surfaces the Yggdrasil tun interface on a cellular-only phone,
 * confirmed via extensive real-hardware testing 2026-09-13).
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var modePrefs: ModePrefs
    private val controlLink = ControlLink()
    private val videoReceiver = VideoReceiver()
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

        // Fixed ~33Hz tick, same reasoning as the fase-2 debug UI: a steady heartbeat
        // is what the vehicle-side failsafe is designed to key off, not event-driven sends.
        tickRunnable = object : Runnable {
            override fun run() {
                val steer = (seekSteer.progress - 100) / 100f
                val throttle = (seekThrottle.progress - 100) / 100f
                controlLink.send(steer, throttle, seq++)
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
        controlLink.close()
        videoReceiver.stop()
        super.onDestroy()
    }
}
