package nu.bruijn.rcvehicle.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.common.YggdrasilAddress
import nu.bruijn.rcvehicle.control.ControlLink

/**
 * Debug UI: fase 2 (wifi-to-ESP32, top half) drives ServoBridge with two sliders,
 * fase 4 (UDP control link over Yggdrasil, bottom half) listens for the controller
 * and relays whatever it receives into the SAME ServoBridge - so with both
 * sections connected at once this is already a real end-to-end controller-phone ->
 * vehicle-phone -> ESP32 path, just without video yet.
 *
 * See ControlLink's doc for why this is a plain UDP socket instead of the
 * originally-planned WebRTC data channel.
 */
class VehicleActivity : AppCompatActivity() {

    private var service: VehicleForegroundService? = null
    private var bound = false
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private val controlLink = ControlLink()

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
            controlLink.close()
            logView.text = ""
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    controlLink.listen(ControlLink.PORT) { decoded ->
                        log("control #${decoded.seq}: steer=${decoded.steer} throttle=${decoded.throttle}")
                        service?.servoBridge?.sendControl(decoded.steer, decoded.throttle)
                    }
                    log("listening for UDP control on port ${ControlLink.PORT}")
                } catch (e: Exception) {
                    log("listen failed: ${e::class.simpleName}: ${e.message}")
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
        // the plan's eventual data-channel design (section 4). Sending only on
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
        controlLink.close()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
