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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            val vehicleId = VehiclePairing.vehicleId(secret)
            lifecycleScope.launch {
                // Merges into the existing entry (same vehicleId) if this vehicle
                // was already known at a different address - see ModePrefs' doc.
                modePrefs.recordVehicleSighting(vehicleId, address, secret)
                connect(vehicleId)
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
            val vehicleId = VehiclePairing.vehicleId(secret)
            lifecycleScope.launch {
                modePrefs.recordVehicleSighting(vehicleId, address, secret)
                connect(vehicleId)
            }
        }
    }

    // Guards the recent-vehicles rows' click listeners for a brief moment after
    // this screen (re)appears - confirmed live as a real bug without it: coming
    // back from the driving screen (which sits directly above a full-width row
    // list here) via the nav bar's back control, a touch that's really meant
    // for that back action can land on a row a fraction of a second after it
    // re-renders, immediately reconnecting to whatever vehicle happens to be
    // first in the list. Any genuine tap meant for a row will always be well
    // past this window.
    private var resumedAtMs = 0L
    private val ROW_TAP_GUARD_MS = 400L

    override fun onResume() {
        super.onResume()
        resumedAtMs = android.os.SystemClock.elapsedRealtime()
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
            val vehicleIds = modePrefs.recentVehicleIds.first()
            title.visibility = if (vehicleIds.isEmpty()) View.GONE else View.VISIBLE
            vehicleIds.forEach { addVehicleRow(it, container) }
        }
    }

    /** Probes every address [vehicleId] has been seen at (see ModePrefs' doc -
     * usually one, occasionally two: built-in Yggdrasil vs. the standalone app
     * each give it a different address) in parallel, and bumps whichever one
     * answers to the front of its list via markAddressReachable, so a working
     * address keeps being tried first and a stale one sinks to the back rather
     * than being retried every time. Returns the answering address, or the
     * first (most-recently-working) one if none answered within the probe. */
    private suspend fun probeAndPickAddress(
        vehicleId: String,
        addresses: List<String>,
        secret: ByteArray,
        attempts: Int = 3,
        timeoutMs: Int = 1200,
    ): Pair<String, Boolean> = coroutineScope {
        val results = addresses.map { address ->
            async { address to ControlLink.probeOnline(address, secret, attempts = attempts, timeoutMs = timeoutMs) }
        }.awaitAll()
        val reachable = results.firstOrNull { it.second }
        if (reachable != null) {
            modePrefs.markAddressReachable(vehicleId, reachable.first)
            reachable.first to true
        } else {
            addresses.first() to false
        }
    }

    /** One row: an online/offline dot (probed live across all known addresses,
     * see [probeAndPickAddress]), a friendly assigned name in place of the raw
     * address (ModePrefs.nameForVehicle() - per direct request, a known
     * vehicle's Yggdrasil address shouldn't need to be shown at all), and a
     * small remove button - tapping the row itself connects. */
    private fun addVehicleRow(vehicleId: String, container: LinearLayout) {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

        val dot = TextView(this).apply {
            text = "●"
            setTextColor(Color.parseColor("#9E9E9E")) // unknown yet, until the probe below resolves
            textSize = 16f
            setPadding(0, 0, dp(12), 0)
        }
        val addressText = TextView(this).apply {
            text = vehicleId // placeholder until the name below resolves
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Also used by the remove-confirmation dialog below, so it can name the
        // vehicle instead of showing a raw address - defaults to the vehicleId
        // itself until the name lookup resolves (should be near-instant in
        // practice).
        var resolvedName = vehicleId
        lifecycleScope.launch {
            val name = modePrefs.nameForVehicle(vehicleId)
            resolvedName = name
            // The raw address(es) are still useful for troubleshooting a
            // specific link, just not front-and-center - only tacked on in
            // debug mode.
            val debugEnabled = modePrefs.debugEnabled.first()
            val addresses = modePrefs.addressesForVehicle(vehicleId)
            addressText.text = if (debugEnabled) "$name (${addresses.joinToString(", ")})" else name
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
                        lifecycleScope.launch { modePrefs.removeVehicle(vehicleId) }
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
            setOnClickListener {
                if (android.os.SystemClock.elapsedRealtime() - resumedAtMs >= ROW_TAP_GUARD_MS) connect(vehicleId)
            }
        }
        container.addView(row)

        // A single one-shot probe right as this screen opens can lose the race
        // against Yggdrasil still settling on a path to the vehicle (freshly
        // started mesh, cold DHT lookup, etc.) - confirmed on real hardware as
        // a false "offline" reading that a moment later, once actually
        // connecting, turns out to be fine. Keep re-probing on a steady cadence
        // instead of trusting one snapshot, so the dot self-corrects.
        probeJobs += lifecycleScope.launch {
            // No stored secret means this vehicle was never actually paired via
            // QR (or predates authentication) - can't probe it at all now, same
            // as it being offline, since an unsigned Ping is simply dropped by
            // the vehicle rather than answered.
            val secret = modePrefs.secretForVehicle(vehicleId)
            if (secret == null) {
                dot.setTextColor(Color.parseColor("#9E9E9E"))
                dot.contentDescription = getString(R.string.controller_vehicle_offline)
                return@launch
            }
            while (isActive) {
                val addresses = modePrefs.addressesForVehicle(vehicleId)
                val (_, online) = if (addresses.isEmpty()) "" to false else probeAndPickAddress(vehicleId, addresses, secret)
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
     * always looked up from ModePrefs rather than threaded through every caller.
     * Tries every address this vehicleId has been seen at (see ModePrefs' doc)
     * and connects via whichever one actually answers, falling back to the
     * most-recently-working one if none do so the drive screen's own retry/
     * timeout UI can still take over rather than this dead-ending here. */
    private fun connect(vehicleId: String) {
        if (vehicleId.isEmpty()) return
        lifecycleScope.launch {
            val secret = modePrefs.secretForVehicle(vehicleId)
            if (secret == null) {
                // A stale entry from before authentication existed, or the QR/
                // manual-paste flow failed to store it - either way, there's no
                // secret to sign with, so this can't actually control anything.
                // Re-pairing (scan the QR again) is the only way forward.
                Toast.makeText(this@ControllerActivity, "No pairing secret for this vehicle - scan its QR code again", Toast.LENGTH_LONG).show()
                return@launch
            }
            val addresses = modePrefs.addressesForVehicle(vehicleId)
            if (addresses.isEmpty()) {
                Toast.makeText(this@ControllerActivity, "No known address for this vehicle - scan its QR code again", Toast.LENGTH_LONG).show()
                return@launch
            }
            val (address, _) = probeAndPickAddress(vehicleId, addresses, secret)
            Log.d("RCDebug", "connecting to $address")
            modePrefs.touchRecentVehicle(vehicleId)
            startActivity(Intent(this@ControllerActivity, ControllerDriveActivity::class.java).apply {
                putExtra(ControllerDriveActivity.EXTRA_VEHICLE_ADDRESS, address)
                putExtra(ControllerDriveActivity.EXTRA_VEHICLE_SECRET, secret)
            })
        }
    }
}
