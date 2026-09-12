package nu.bruijn.rcvehicle

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.controller.ControllerActivity
import nu.bruijn.rcvehicle.mode.AppMode
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.vehicle.VehicleActivity

/** Mode picker: remembers the last choice so relaunch goes straight to that mode. */
class MainActivity : AppCompatActivity() {

    private lateinit var modePrefs: ModePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modePrefs = ModePrefs(applicationContext)

        findViewById<android.widget.Button>(R.id.buttonController).setOnClickListener {
            selectMode(AppMode.CONTROLLER)
        }
        findViewById<android.widget.Button>(R.id.buttonVehicle).setOnClickListener {
            selectMode(AppMode.VEHICLE)
        }
    }

    private fun selectMode(mode: AppMode) {
        lifecycleScope.launch {
            modePrefs.setMode(mode)
            val target = when (mode) {
                AppMode.CONTROLLER -> ControllerActivity::class.java
                AppMode.VEHICLE -> VehicleActivity::class.java
            }
            startActivity(Intent(this@MainActivity, target))
        }
    }
}
