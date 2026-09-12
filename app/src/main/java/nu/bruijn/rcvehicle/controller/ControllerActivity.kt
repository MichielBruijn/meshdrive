package nu.bruijn.rcvehicle.controller

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import nu.bruijn.rcvehicle.R

/**
 * Placeholder for the controller UI (plan section 7): joystick view, video renderer,
 * status overlay. Signaling/RTC/joystick input land in a later pass.
 */
class ControllerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)
    }
}
