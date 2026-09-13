package nu.bruijn.rcvehicle.controller

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.mode.ModePrefs

/**
 * Pairing/setup screen: paste or scan the vehicle's Yggdrasil address, then hand
 * off to ControllerDriveActivity - the actual joystick/video screen, kept
 * separate (and landscape-locked) so this portrait, keyboard-heavy setup UI never
 * has to share space with it. See ControllerDriveActivity's doc for the rest.
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var modePrefs: ModePrefs

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

        lifecycleScope.launch {
            modePrefs.pairedYggdrasilAddress.collect { saved ->
                if (saved != null && editAddress.text.isEmpty()) editAddress.setText(saved)
            }
        }

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

            Log.d("RCDebug", "connecting to $address")
            lifecycleScope.launch { modePrefs.setPairedYggdrasilAddress(address) }
            startActivity(Intent(this, ControllerDriveActivity::class.java).apply {
                putExtra(ControllerDriveActivity.EXTRA_VEHICLE_ADDRESS, address)
            })
        }
    }
}
