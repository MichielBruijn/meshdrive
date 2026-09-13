package nu.bruijn.rcvehicle.controller

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.mode.ModePrefs

/**
 * Pairing/setup screen: pick a previously-paired vehicle from the recent list, scan
 * a new one's QR code (or, in debug mode, paste its address manually), then hand
 * off to ControllerDriveActivity - the actual joystick/video screen, kept separate
 * (and landscape-locked) so this portrait, keyboard-heavy setup UI never has to
 * share space with it. See ControllerDriveActivity's doc for the rest.
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var modePrefs: ModePrefs

    // Scanning connects immediately - no separate "Connect" tap needed, that's only
    // still required for the debug-mode manual-address path below.
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            val address = scanned.trim()
            findViewById<EditText>(R.id.editVehicleAddress).setText(address)
            connect(address)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)
        modePrefs = ModePrefs(applicationContext)

        val editAddress = findViewById<EditText>(R.id.editVehicleAddress)

        val debugSection = findViewById<View>(R.id.debugSection)
        lifecycleScope.launch {
            debugSection.visibility = if (modePrefs.debugEnabled.first()) View.VISIBLE else View.GONE
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
            connect(editAddress.text.toString().trim())
        }
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt every time this screen becomes visible (not just once in onCreate) -
        // coming back here after driving is exactly when the list/online status is
        // most likely to have changed.
        refreshRecentVehicles()
    }

    private fun refreshRecentVehicles() {
        val title = findViewById<TextView>(R.id.textRecentVehiclesTitle)
        val container = findViewById<LinearLayout>(R.id.recentVehiclesList)
        container.removeAllViews()
        lifecycleScope.launch {
            val addresses = modePrefs.recentVehicleAddresses.first()
            title.visibility = if (addresses.isEmpty()) View.GONE else View.VISIBLE
            addresses.forEach { addVehicleRow(it, container) }
        }
    }

    /** One row: an online/offline dot (probed live, see ControlLink.probeOnline()),
     * the address, and a small remove button - tapping the row itself connects. */
    private fun addVehicleRow(address: String, container: LinearLayout) {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

        val dot = TextView(this).apply {
            text = "●"
            setTextColor(Color.parseColor("#9E9E9E")) // unknown yet, until the probe below resolves
            textSize = 16f
            setPadding(0, 0, dp(12), 0)
        }
        val addressText = TextView(this).apply {
            text = address
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val removeButton = TextView(this).apply {
            text = "✕"
            contentDescription = getString(R.string.controller_remove_vehicle)
            setPadding(dp(16), 0, dp(4), 0)
            setOnClickListener {
                lifecycleScope.launch { modePrefs.removeRecentVehicleAddress(address) }
                container.removeView(this@apply.parent as View)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            addView(dot)
            addView(addressText)
            addView(removeButton)
            setOnClickListener { connect(address) }
        }
        container.addView(row)

        lifecycleScope.launch {
            val online = ControlLink.probeOnline(address)
            dot.setTextColor(if (online) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E"))
            dot.contentDescription = getString(
                if (online) R.string.controller_vehicle_online else R.string.controller_vehicle_offline
            )
        }
    }

    private fun connect(address: String) {
        if (address.isEmpty()) return
        Log.d("RCDebug", "connecting to $address")
        lifecycleScope.launch { modePrefs.addRecentVehicleAddress(address) }
        startActivity(Intent(this, ControllerDriveActivity::class.java).apply {
            putExtra(ControllerDriveActivity.EXTRA_VEHICLE_ADDRESS, address)
        })
    }
}
