package nu.bruijn.rcvehicle.controller

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
    /** Probes every one of [addresses] in parallel, individually - each address
     * this vehicleId has been seen at (embedded Yggdrasil vs. the standalone app,
     * see ModePrefs.addressesForVehicle's doc) can be independently up or down,
     * e.g. only one of the two Yggdrasil instances actually running right now -
     * callers that only need "is ANY of them reachable" can just check
     * `.any { it.second }` on the result rather than this collapsing to one
     * boolean itself. */
    private suspend fun probeAllAddresses(
        addresses: List<String>,
        secret: ByteArray,
        attempts: Int = 3,
        timeoutMs: Int = 1200,
    ): List<Pair<String, Boolean>> = coroutineScope {
        addresses.map { address ->
            async { address to ControlLink.probeOnline(address, secret, attempts = attempts, timeoutMs = timeoutMs) }
        }.awaitAll()
    }

    private suspend fun probeAndPickAddress(
        vehicleId: String,
        addresses: List<String>,
        secret: ByteArray,
        attempts: Int = 3,
        timeoutMs: Int = 1200,
    ): Pair<String, Boolean> {
        val results = probeAllAddresses(addresses, secret, attempts, timeoutMs)
        val reachable = results.firstOrNull { it.second }
        return if (reachable != null) {
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

        // Shared by the dot and the name/address text below - per direct request,
        // "online" should color both, not just the dot.
        val onlineColor = Color.parseColor("#4CAF50")
        val offlineColor = Color.parseColor("#9E9E9E")

        val dot = TextView(this).apply {
            text = "●"
            setTextColor(offlineColor) // unknown yet, until the probe below resolves
            textSize = 16f
            setPadding(0, 0, dp(12), 0)
        }
        val addressText = TextView(this).apply {
            text = vehicleId // placeholder until the name below resolves
            setTextColor(offlineColor) // unknown yet, until the probe below resolves
            // No ellipsize/maxLines set here on purpose - TextView.setEllipsize(MIDDLE
            // or START) forces setHorizontallyScrolling(true) internally, and setting
            // ellipsize back to null later does NOT undo that (a known TextView quirk) -
            // confirmed on real hardware as a real bug: the debug-mode multi-line
            // address list below stayed stuck on a single scrolling line because this
            // block used to set MIDDLE ellipsize unconditionally right here. Each
            // branch below sets its own mode from scratch instead.
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
            if (debugEnabled) {
                // One address per line under the name (even for a single address,
                // per direct request - a consistent layout beats a shorter line
                // that only sometimes wraps) - reachable via either the embedded
                // Yggdrasil or the standalone app, see ModePrefs.addressesForVehicle's
                // doc for why there can be more than one. No ellipsize (default
                // null) - see addressText's own construction doc for why that's
                // required for multi-line to actually take effect here. No maxLines
                // cap either - confirmed on real hardware as a real bug: a fixed
                // "1 + addresses.size" assumes one VISUAL line per logical line,
                // but a full IPv6 address doesn't fit on one line in portrait's
                // narrower width, so it wraps and eats into that budget before the
                // later addresses ever get a line to render on.
                addressText.text = (listOf(name) + addresses).joinToString("\n")
            } else {
                addressText.ellipsize = TextUtils.TruncateAt.MIDDLE
                addressText.maxLines = 1
                addressText.text = name
            }
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
                dot.setTextColor(offlineColor)
                addressText.setTextColor(offlineColor)
                dot.contentDescription = getString(R.string.controller_vehicle_offline)
                return@launch
            }
            while (isActive) {
                val addresses = modePrefs.addressesForVehicle(vehicleId)
                val results = if (addresses.isEmpty()) emptyList() else probeAllAddresses(addresses, secret)
                results.firstOrNull { it.second }?.let { (address, _) -> modePrefs.markAddressReachable(vehicleId, address) }
                val online = results.any { it.second }
                dot.setTextColor(if (online) onlineColor else offlineColor)
                dot.contentDescription = getString(
                    if (online) R.string.controller_vehicle_online else R.string.controller_vehicle_offline
                )
                // Per direct request - each address line reflects ITS OWN
                // reachability (only one of embedded-Yggdrasil-vs-standalone-app
                // is typically actually up at a time, see probeAllAddresses' doc),
                // not just whether the vehicle is reachable at all via some
                // address. The name line (debug mode's first line, or the whole
                // text outside debug mode) follows the overall online status.
                if (modePrefs.debugEnabled.first() && addresses.isNotEmpty()) {
                    // Reset in case a previous cycle (before debug mode got toggled
                    // on, mid-screen) left the MIDDLE-ellipsize quirk active - see
                    // addressText's construction doc.
                    addressText.ellipsize = null
                    val lines = listOf(resolvedName) + addresses
                    val text = lines.joinToString("\n")
                    val spannable = SpannableString(text)
                    var start = 0
                    lines.forEachIndexed { i, line ->
                        val end = start + line.length
                        val lineColor = if (i == 0) (if (online) onlineColor else offlineColor) else (if (results.getOrNull(i - 1)?.second == true) onlineColor else offlineColor)
                        spannable.setSpan(ForegroundColorSpan(lineColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        start = end + 1 // +1 for the '\n'
                    }
                    addressText.text = spannable
                } else {
                    addressText.setTextColor(if (online) onlineColor else offlineColor)
                }
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
