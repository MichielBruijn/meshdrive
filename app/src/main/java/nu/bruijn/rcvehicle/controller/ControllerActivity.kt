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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.control.ControlLink
import nu.bruijn.rcvehicle.control.VehiclePairing
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

    // One probe loop per visible row, cancelled and replaced on every
    // refreshRecentVehicles() (onResume) - otherwise stale loops from a
    // previous resume would keep running against detached views forever.
    private val probeJobs = mutableListOf<Job>()

    // Scanning connects immediately - no separate "Connect" tap needed, that's only
    // still required for the debug-mode manual-address path below.
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            val pair = VehiclePairing.decode(scanned)
            if (pair == null) {
                Toast.makeText(this, "Not a valid MeshDrive vehicle QR code", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val (address, secret) = pair
            findViewById<EditText>(R.id.editVehicleAddress).setText(address)
            lifecycleScope.launch {
                modePrefs.setVehicleSecret(address, secret)
                connect(address)
            }
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
                    .setPrompt(getString(R.string.controller_scan_qr_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }

        findViewById<Button>(R.id.buttonConnectRtc).setOnClickListener {
            // Debug-only manual path - expects the same "address|secret" pairing
            // string VehicleActivity's debug section now shows (see its doc),
            // not a bare address, since ControlLink needs the secret too.
            val pair = VehiclePairing.decode(editAddress.text.toString())
            if (pair == null) {
                Toast.makeText(this, "Paste the full pairing string (address|secret), not just the address", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val (address, secret) = pair
            lifecycleScope.launch {
                modePrefs.setVehicleSecret(address, secret)
                connect(address)
            }
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
        probeJobs.forEach { it.cancel() }
        probeJobs.clear()
        container.removeAllViews()
        lifecycleScope.launch {
            val addresses = modePrefs.recentVehicleAddresses.first()
            title.visibility = if (addresses.isEmpty()) View.GONE else View.VISIBLE
            addresses.forEach { addVehicleRow(it, container) }
        }
    }

    /** One row: an online/offline dot (probed live, see ControlLink.probeOnline()),
     * a friendly assigned name in place of the raw address (ModePrefs.nameForVehicle()
     * - per direct request, a known vehicle's Yggdrasil address shouldn't need to be
     * shown at all), and a small remove button - tapping the row itself connects. */
    private fun addVehicleRow(address: String, container: LinearLayout) {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

        val dot = TextView(this).apply {
            text = "●"
            setTextColor(Color.parseColor("#9E9E9E")) // unknown yet, until the probe below resolves
            textSize = 16f
            setPadding(0, 0, dp(12), 0)
        }
        val addressText = TextView(this).apply {
            text = address // placeholder until the name below resolves
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Also used by the remove-confirmation dialog below, so it can name the
        // vehicle instead of showing a raw address - defaults to the address itself
        // until the name lookup resolves (should be near-instant in practice).
        var resolvedName = address
        lifecycleScope.launch {
            val name = modePrefs.nameForVehicle(address)
            resolvedName = name
            // The raw address is still useful for troubleshooting a specific link,
            // just not front-and-center - only tacked on in debug mode.
            val debugEnabled = modePrefs.debugEnabled.first()
            addressText.text = if (debugEnabled) "$name ($address)" else name
        }
        val removeButton = TextView(this).apply {
            text = "✕"
            contentDescription = getString(R.string.controller_remove_vehicle)
            setPadding(dp(16), 0, dp(4), 0)
            setOnClickListener {
                // One accidental tap used to delete a paired vehicle outright, no
                // way back - a confirmation costs nothing and this is exactly the
                // kind of destructive-by-a-single-tap action worth guarding.
                AlertDialog.Builder(this@ControllerActivity)
                    .setMessage(getString(R.string.controller_remove_vehicle_confirm, resolvedName))
                    .setPositiveButton(R.string.controller_remove_vehicle) { _, _ ->
                        lifecycleScope.launch { modePrefs.removeRecentVehicleAddress(address) }
                        container.removeView(this@apply.parent as View)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
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

        // A single one-shot probe right as this screen opens can lose the race
        // against Yggdrasil still settling on a path to the vehicle (freshly
        // started mesh, cold DHT lookup, etc.) - confirmed on real hardware as
        // a false "offline" reading that a moment later, once actually
        // connecting, turns out to be fine. Keep re-probing on a steady cadence
        // instead of trusting one snapshot, so the dot self-corrects.
        probeJobs += lifecycleScope.launch {
            // No stored secret means this address was never actually paired via
            // QR (or predates authentication) - can't probe it at all now, same
            // as it being offline, since an unsigned Ping is simply dropped by
            // the vehicle rather than answered.
            val secret = modePrefs.secretForVehicle(address)
            if (secret == null) {
                dot.setTextColor(Color.parseColor("#9E9E9E"))
                dot.contentDescription = getString(R.string.controller_vehicle_offline)
                return@launch
            }
            while (isActive) {
                val online = ControlLink.probeOnline(address, secret)
                dot.setTextColor(if (online) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E"))
                dot.contentDescription = getString(
                    if (online) R.string.controller_vehicle_online else R.string.controller_vehicle_offline
                )
                delay(3_000L)
            }
        }
    }

    /** Also handles re-connecting to an already-known vehicle (tapping a
     * recent-vehicles row), where the secret isn't in scope at the call site -
     * always looked up from ModePrefs rather than threaded through every caller. */
    private fun connect(address: String) {
        if (address.isEmpty()) return
        lifecycleScope.launch {
            val secret = modePrefs.secretForVehicle(address)
            if (secret == null) {
                // A stale entry from before authentication existed, or the QR/
                // manual-paste flow failed to store it - either way, there's no
                // secret to sign with, so this can't actually control anything.
                // Re-pairing (scan the QR again) is the only way forward.
                Toast.makeText(this@ControllerActivity, "No pairing secret for this vehicle - scan its QR code again", Toast.LENGTH_LONG).show()
                return@launch
            }
            Log.d("RCDebug", "connecting to $address")
            modePrefs.addRecentVehicleAddress(address)
            startActivity(Intent(this@ControllerActivity, ControllerDriveActivity::class.java).apply {
                putExtra(ControllerDriveActivity.EXTRA_VEHICLE_ADDRESS, address)
                putExtra(ControllerDriveActivity.EXTRA_VEHICLE_SECRET, secret)
            })
        }
    }
}
