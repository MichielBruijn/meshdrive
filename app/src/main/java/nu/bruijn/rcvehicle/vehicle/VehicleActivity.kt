package nu.bruijn.rcvehicle.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import nu.bruijn.rcvehicle.R

/**
 * Debug UI for plan "fase 2": drives ServoBridge with two sliders instead of a
 * WebRTC data channel, to verify the wifi-to-ESP32 plumbing (ConnectivityManager
 * network binding, websocket, failsafe) before any RTC code exists.
 */
class VehicleActivity : AppCompatActivity() {

    private var service: VehicleForegroundService? = null
    private var bound = false

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

        findViewById<Button>(R.id.buttonConnect).setOnClickListener {
            service?.connectToEsp(editIp.text.toString().trim())
        }

        val sendControl = {
            // SeekBar is 0..200 -> normalize to -1.0..1.0
            val steer = (seekSteer.progress - 100) / 100f
            val throttle = (seekThrottle.progress - 100) / 100f
            service?.servoBridge?.sendControl(steer, throttle)
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) sendControl()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Snap back to center on release, like a real self-centering joystick,
                // and exercise the failsafe path by simply going quiet afterwards.
                seekBar?.progress = 100
                sendControl()
            }
        }
        seekSteer.setOnSeekBarChangeListener(listener)
        seekThrottle.setOnSeekBarChangeListener(listener)

        val intent = Intent(this, VehicleForegroundService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
