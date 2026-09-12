package nu.bruijn.rcvehicle.vehicle

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import nu.bruijn.rcvehicle.R

/**
 * Placeholder for vehicle-mode UI: shows this device's own Yggdrasil address (plan
 * section 2's pairing step) and starts/stops VehicleForegroundService. The actual
 * servo-bridge/signaling/camera work lands in a later pass.
 */
class VehicleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle)
    }
}
