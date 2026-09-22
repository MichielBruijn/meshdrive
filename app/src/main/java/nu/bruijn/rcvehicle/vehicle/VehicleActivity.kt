package nu.bruijn.rcvehicle.vehicle

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nu.bruijn.rcvehicle.R
import nu.bruijn.rcvehicle.common.InternetNetworkBinder
import nu.bruijn.rcvehicle.common.QrCode
import nu.bruijn.rcvehicle.common.YggdrasilAddress
import nu.bruijn.rcvehicle.common.showPermissionDeniedIfAny
import nu.bruijn.rcvehicle.control.PairingSecret
import nu.bruijn.rcvehicle.control.VehiclePairing
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.yggdrasil.YggdrasilController

/**
 * Debug UI: top half connects to the ESP32 (USB-OTG serial, primary - see
 * UsbServoBridge's doc; BLE as an alternative, see BleServoBridge's doc) and
 * drives it with two sliders. Bottom half (UDP control link over Yggdrasil, fase 4) listens
 * for the controller and relays whatever it receives into the SAME
 * `service.activeLink` - so with both sections connected at once this is already
 * a real end-to-end controller-phone -> vehicle-phone -> ESP32 path. Video starts
 * automatically the moment the first control packet arrives, since that's how
 * this side learns the controller's Yggdrasil address (see ControlLink.listen's
 * doc) - no separate video-connect step.
 *
 * See ControlLink's/VideoSender's docs for why these are plain UDP sockets instead
 * of the originally-planned WebRTC data channel/video track.
 */
class VehicleActivity : AppCompatActivity() {

    private var service: VehicleForegroundService? = null
    private var bound = false
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    // Loaded independently of service-binding timing (checkYggdrasil() below runs
    // synchronously in onCreate(), before bindService()'s async callback could ever
    // supply service.pairingSecret) - PairingSecret.load() is idempotent/cheap, so
    // there's no real cost to also loading it here rather than threading the
    // service's copy through with a null-safety dance.
    private val pairingSecret by lazy { PairingSecret.load(applicationContext) }

    // Set once onServiceConnected below has a service to attach it to - see setUsbButtonConnected() in onCreate.
    private var onUsbConnectedChanged: ((Boolean) -> Unit)? = null
    private var onBleConnectedChanged: ((Boolean) -> Unit)? = null
    private var isUsbConnected = false

    // Set in onCreate - re-run from onResume so coming back from actually opening
    // Yggdrasil (buttonOpenYggdrasil) picks up the result automatically.
    private var recheckYggdrasil: (() -> Unit)? = null

    // Set in onCreate, same indirection reasoning as onUsbConnectedChanged above.
    private var onServiceLog: ((String) -> Unit)? = null
    private var onServiceRemoteControl: ((Float, Float) -> Unit)? = null
    private var onServiceListeningStateChanged: ((Boolean) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VehicleForegroundService.LocalBinder
            service = localBinder.service
            bound = true
            service?.onStatus = { status ->
                runOnUiThread { findViewById<TextView>(R.id.textStatus).text = status }
            }
            service?.usbServoBridge?.onConnected = { connected ->
                runOnUiThread { onUsbConnectedChanged?.invoke(connected) }
            }
            service?.bleServoBridge?.onConnected = { connected ->
                runOnUiThread { onBleConnectedChanged?.invoke(connected) }
            }
            // Same indirection as onUsbConnectedChanged above, same reason -
            // this ServiceConnection is a class-level field, constructed
            // before onCreate()'s locals (log(), seekSteer, setListenButtonUp)
            // exist, so it can't capture them directly. The controller-facing
            // session itself (ControlLink/VideoSender/AudioSender, and the
            // network-churn watch loop that keeps them working) now lives on
            // the service, not this Activity - see VehicleForegroundService's
            // class doc for why: an Activity isn't guaranteed to survive
            // backgrounding the way a foreground service is, and confirmed
            // live as a real bug, it wasn't. These three just re-attach this
            // screen's UI to whatever's already running underneath.
            service?.onLog = { line -> onServiceLog?.invoke(line) }
            service?.onRemoteControl = { steer, throttle -> onServiceRemoteControl?.invoke(steer, throttle) }
            service?.onListeningStateChanged = { up -> onServiceListeningStateChanged?.invoke(up) }
            // No reason to make the driver tap "Connect via USB cable" themselves - if
            // the ESP32 is already plugged in when this screen opens (the common case:
            // the manifest's USB_DEVICE_ATTACHED filter already launched the mode picker
            // for exactly this reason), just connect immediately. usbServoBridge.start()
            // already handles "no device found" gracefully, so this is harmless if
            // nothing's plugged in yet.
            service?.connectUsb()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately NOT forcing the screen to stay on (FLAG_KEEP_SCREEN_ON) here
        // anymore: letting this phone's own timeout lock it is fine, and per direct
        // request even desirable - mounted on the vehicle, unattended, a locked
        // screen means nobody can just pick it up and stop the app without
        // unlocking it first. This used to be needed because the display timeout
        // locking the phone made Android revoke camera access from the now-
        // backgrounded app (confirmed on real hardware: onError fired "camera
        // error: 3" - ERROR_CAMERA_DISABLED - right on schedule with the screen
        // timeout) - fixed properly since by declaring VehicleForegroundService's
        // foregroundServiceType as camera+microphone (see its manifest entry and
        // doc), which is what actually keeps a locked/backgrounded app's camera and
        // mic access alive, plus a wake lock so the CPU doesn't doze in the
        // meantime.
        setContentView(R.layout.activity_vehicle)

        // Debug mode (MainActivity's checkbox) hides raw addresses/logs/the local debug
        // sliders - none of that is needed for normal day-to-day driving, it's here for
        // diagnosing problems. A one-shot read is enough: changing the toggle
        // requires going back to the mode picker anyway, there's no live-update need.
        val debugSection = findViewById<View>(R.id.debugSection)
        lifecycleScope.launch {
            debugSection.visibility = if (ModePrefs(applicationContext).debugEnabled.first()) View.VISIBLE else View.GONE
        }

        val seekSteer = findViewById<SeekBar>(R.id.seekSteer)
        val seekThrottle = findViewById<SeekBar>(R.id.seekThrottle)
        val textOwnAddress = findViewById<TextView>(R.id.textOwnYggdrasilAddress)
        val logView = findViewById<TextView>(R.id.textRtcLog)

        // Capped to the last N lines - an unbounded log view (wrap_content, no
        // scroll) pushed the rest of this screen off-screen within seconds at
        // the sliders/control-frame log's own rate, confirmed on real hardware.
        val maxLogLines = 20
        fun log(line: String) = runOnUiThread {
            Log.d("RCDebug", line)
            val lines = (logView.text.toString() + line + "\n").lines()
            logView.text = lines.takeLast(maxLogLines + 1).joinToString("\n")
        }

        val basePermissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            basePermissions += Manifest.permission.BLUETOOTH_SCAN
            basePermissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        val neededPermissions = basePermissions
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 1)
        }

        val buttonConnectUsb = findViewById<Button>(R.id.buttonConnectUsb)
        val defaultUsbButtonTint = buttonConnectUsb.backgroundTintList
        val defaultUsbButtonText = buttonConnectUsb.text
        val usbConnectedTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2E7D32"))

        fun setUsbButtonConnected(connected: Boolean) {
            isUsbConnected = connected
            buttonConnectUsb.backgroundTintList = if (connected) usbConnectedTint else defaultUsbButtonTint
            buttonConnectUsb.text = if (connected) getString(R.string.vehicle_usb_connected) else defaultUsbButtonText
        }
        onUsbConnectedChanged = ::setUsbButtonConnected

        buttonConnectUsb.setOnClickListener {
            service?.connectUsb()
        }

        val buttonConnectBle = findViewById<Button>(R.id.buttonConnectBle)
        val defaultBleButtonText = buttonConnectBle.text
        fun setBleButtonConnected(connected: Boolean) {
            buttonConnectBle.backgroundTintList = if (connected) usbConnectedTint else defaultUsbButtonTint
            buttonConnectBle.text = if (connected) getString(R.string.vehicle_usb_connected) else defaultBleButtonText
        }
        onBleConnectedChanged = ::setBleButtonConnected

        buttonConnectBle.setOnClickListener {
            service?.connectBle()
        }
        // Servo settings live on the board's own web interface now, not a native screen here -
        // per direct request, after VehicleSettingsActivity's toggle-based channel picker (mirroring
        // the firmware's own SETJOYX/SETJOYY semantics too literally) left steer and throttle
        // both pointing at the same channel on real hardware, among other confusion. Opens the
        // browser straight to the board's AP IP; works once the phone has joined "MespDrive" -
        // same wifi this app's own firmware-install flow already asks for.
        findViewById<Button>(R.id.buttonSettings).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://192.168.4.1")))
        }

        setUpFirmwareUpdateUi()

        val imageQr = findViewById<ImageView>(R.id.imageYggdrasilQr)
        val textYggdrasilStatus = findViewById<TextView>(R.id.textYggdrasilStatus)
        val buttonOpenYggdrasil = findViewById<Button>(R.id.buttonOpenYggdrasil)

        // MainActivity already started the embedded YggdrasilVpnService (with VPN
        // consent granted) before launching this screen - this just polls for the
        // address it ends up with, since startForegroundService() returning doesn't
        // mean the mesh node has actually come up yet (config load, TUN establish,
        // first startJSON() all happen asynchronously inside the service). Re-run
        // from onResume (recheckYggdrasil below) so coming back to this screen (e.g.
        // after granting VPN consent) picks the address up without a relaunch.
        fun checkYggdrasil() {
            lifecycleScope.launch {
                val address = withTimeoutOrNull(5000) {
                    var found = YggdrasilController.address()
                    while (found == null) {
                        delay(200)
                        found = YggdrasilController.address()
                    }
                    found
                }
                if (address != null) {
                    // The full pairing string (not just the address) - debug mode's
                    // manual-paste path (ControllerActivity) needs the secret too now,
                    // same as the QR below.
                    textOwnAddress.text = "Own pairing string: ${VehiclePairing.encode(address, pairingSecret)}"
                    textYggdrasilStatus.text = ""
                    buttonOpenYggdrasil.visibility = View.GONE
                    imageQr.setImageBitmap(QrCode.encode(VehiclePairing.encode(address, pairingSecret)))
                } else {
                    textYggdrasilStatus.text = "Starting Yggdrasil mesh…"
                    buttonOpenYggdrasil.text = getString(R.string.vehicle_open_yggdrasil)
                    buttonOpenYggdrasil.visibility = View.VISIBLE
                    buttonOpenYggdrasil.setOnClickListener {
                        // Confirmed on real hardware as a real bug: this used to
                        // just re-poll for an address that will never appear if
                        // YggdrasilController.start() never actually succeeded in
                        // the first place - no way to recover short of fully
                        // closing and reopening the app. Actually retries
                        // starting the mesh now (consent is already granted by
                        // this point - MainActivity's own flow already went
                        // through VpnService.prepare() before ever launching
                        // this screen), then re-polls as before.
                        //
                        // Same VpnConflictDetector check as MainActivity.selectMode() - this is
                        // a SEPARATE call to YggdrasilController.start() that bypassed it before.
                        // Confirmed on real hardware as a real bug: silently preempted a native
                        // Yggdrasil app's own VPN with no warning (Android only allows one
                        // VpnService active at a time, and doesn't restore the old one when ours
                        // stops - the native app has to notice and reconnect itself, which
                        // showed up as adb-over-Yggdrasil needing a manual reset+killall to
                        // recover, nothing to do with adb itself).
                        fun startYggdrasil() {
                            lifecycleScope.launch {
                                val candidates = ModePrefs(applicationContext).yggdrasilPeerCandidates.first()
                                YggdrasilController.start(this@VehicleActivity, candidates)
                                checkYggdrasil()
                            }
                        }
                        if (nu.bruijn.rcvehicle.yggdrasil.VpnConflictDetector.isForeignVpnActive(this@VehicleActivity)) {
                            android.app.AlertDialog.Builder(this@VehicleActivity)
                                .setTitle(R.string.main_vpn_conflict_title)
                                .setMessage(R.string.main_vpn_conflict_message)
                                .setPositiveButton(R.string.main_vpn_conflict_continue) { _, _ -> startYggdrasil() }
                                .setNegativeButton(R.string.main_vpn_conflict_cancel, null)
                                .show()
                        } else {
                            startYggdrasil()
                        }
                    }
                }
            }
        }
        recheckYggdrasil = ::checkYggdrasil
        checkYggdrasil()

        // Debug-only: view/edit the persisted Yggdrasil identity directly - see
        // YggdrasilController.currentPrivateKeyHex()/setPrivateKeyHex()'s doc. Pre-filled with
        // whatever identity already exists (same "show real state, not a blank field"
        // reasoning as the removed native servo-settings screen's channel pickers), blank/
        // hint-only on a fresh install that's never started Yggdrasil yet.
        val editPrivateKey = findViewById<EditText>(R.id.editYggdrasilPrivateKey)
        val textPrivateKeyResult = findViewById<TextView>(R.id.textPrivateKeyResult)
        editPrivateKey.setText(YggdrasilController.currentPrivateKeyHex(this))
        findViewById<Button>(R.id.buttonApplyPrivateKey).setOnClickListener {
            val hex = editPrivateKey.text.toString()
            if (!YggdrasilController.setPrivateKeyHex(this, hex)) {
                textPrivateKeyResult.text = "Invalid key - must be 128 hex characters"
                return@setOnClickListener
            }
            textPrivateKeyResult.text = "Applied - restarting Yggdrasil…"
            YggdrasilController.stop(this)
            lifecycleScope.launch {
                val candidates = ModePrefs(applicationContext).yggdrasilPeerCandidates.first()
                YggdrasilController.start(this@VehicleActivity, candidates)
                checkYggdrasil()
                textPrivateKeyResult.text = "Applied"
            }
        }

        // Purely a display refresh (which peer, its latency) - harmless if it
        // stops when this Activity is destroyed, unlike the network-churn
        // watch loop that used to live here too. That loop (and the
        // ControlLink/VideoSender/AudioSender it refreshes) now lives on
        // VehicleForegroundService instead - see its class doc for why:
        // confirmed live as a real bug, this Activity is not guaranteed to
        // survive backgrounding, so anything that must keep running (the
        // whole controller-facing session) can't live here.
        val textYggdrasilPeers = findViewById<TextView>(R.id.textYggdrasilPeers)
        lifecycleScope.launch {
            while (isActive) {
                val active = YggdrasilController.activePeer()
                val statuses = YggdrasilController.peerStatuses()
                textYggdrasilPeers.text = if (statuses.isEmpty()) {
                    ""
                } else {
                    statuses.joinToString("\n") { s ->
                        val marker = if (s.uri == active) "*" else " "
                        val latency = s.latencyMs?.let { "${it}ms" } ?: "unreachable"
                        "$marker ${s.uri} - $latency"
                    }
                }
                delay(5000)
            }
        }

        onServiceLog = ::log

        val buttonListen = findViewById<Button>(R.id.buttonListen)
        val defaultListenButtonTint = buttonListen.backgroundTintList
        val defaultListenButtonText = buttonListen.text

        fun setListenButtonUp(up: Boolean) {
            buttonListen.backgroundTintList = if (up) usbConnectedTint else defaultListenButtonTint
            buttonListen.text = if (up) getString(R.string.vehicle_listening) else defaultListenButtonText
        }
        // The actual control-frame handling (servo forwarding, video/audio
        // start, quality/camera/telemetry/steer-limit switches) lives on
        // VehicleForegroundService now - see its startListening()/class doc.
        // This just mirrors its up/down state onto the button, and re-syncs
        // the sliders to whatever a remote controller is actually sending.
        onServiceListeningStateChanged = { up -> runOnUiThread { setListenButtonUp(up) } }
        onServiceRemoteControl = { steer, throttle ->
            runOnUiThread {
                seekSteer.progress = ((steer + 1f) * 100).toInt()
                seekThrottle.progress = ((throttle + 1f) * 100).toInt()
            }
        }

        // Manual restart/clear-log, e.g. after fiddling with the ESP32
        // connection - the service already started listening on its own in
        // its onCreate(), no need to (re)start it from here too.
        buttonListen.setOnClickListener {
            logView.text = ""
            service?.startListening()
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Local driving's own "connect" moment, for calibration
                // refresh purposes - see VehicleForegroundService.refreshCalibration()'s
                // doc. Fires once per drag, not continuously, same reasoning
                // as maybeStartVideo() only refreshing once per new controller
                // rather than every control frame.
                service?.refreshCalibration(::log)
            }
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
        // ServoLink's own failsafe mid-drag, snapping to center and back - that's the
        // jitter. A steady heartbeat is what the failsafe is designed to key off.
        //
        // Skipped entirely while a remote controller has sent something in the last
        // second: these local sliders sit at rest (0,0) whenever nobody is touching
        // them, and kept firing regardless - racing with the fase-4 UDP relay below
        // and stomping on its actual values every ~30ms. That was a second, separate
        // cause of jittery servo movement (this one visible even while the remote
        // controller held perfectly still), on top of the USB write-ordering one.
        val textLinkStatus = findViewById<TextView>(R.id.textLinkStatus)
        var lastLinkStatusText: String? = null

        tickRunnable = object : Runnable {
            override fun run() {
                val lastRemoteControlAtMs = service?.lastRemoteControlAtMs ?: 0L
                if (System.currentTimeMillis() - lastRemoteControlAtMs > 1000) {
                    val steer = (seekSteer.progress - 100) / 100f
                    val throttle = (seekThrottle.progress - 100) / 100f
                    service?.activeLink?.sendControl(steer, throttle)
                }
                // Liveness indicator (Yggdrasil's own app dropping its VPN when wifi turns
                // off, with no automatic fallback to mobile data on some phones, is exactly
                // the kind of silent failure this needs to surface) - a controller that was
                // never seen reads differently from one that WAS driving and then went quiet,
                // since the latter usually means the link just died, not "not started yet".
                val sinceLastControl = System.currentTimeMillis() - lastRemoteControlAtMs
                val text = when {
                    lastRemoteControlAtMs == 0L -> "Waiting for controller…"
                    sinceLastControl > 2000 -> "⚠ Controller signal lost (${sinceLastControl / 1000}s)"
                    else -> "Controller connected"
                }
                if (text != lastLinkStatusText) {
                    lastLinkStatusText = text
                    textLinkStatus.text = text
                }
                tickHandler.postDelayed(this, 30) // ~33Hz, matching the browser UI's own throttle rate
            }
        }
        tickHandler.post(tickRunnable!!)

        val intent = Intent(this, VehicleForegroundService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * "Check for update" reads the running version over whichever link is active (USB
     * or BLE - ServoLinkCommands.sendCommand("GETVERSION"), same generic mechanism the
     * settings screen uses). If a newer release exists, its firmware.bin is downloaded
     * right away (over real internet, InternetNetworkBinder) and kept in memory - by
     * the time "Install update" joins the board's own wifi AP ("MespDrive", which has
     * no internet of its own), nothing left to fetch can block on that.
     *
     * USB-serial install was tried twice this session (this app's own OTAUPDATE
     * chunk/ACK protocol, then a full esp-serial-flasher/esptool ROM-bootloader
     * reimplementation over USB-OTG) and neither proved reliable on this board's
     * native USB-CDC from Android - confirmed across multiple phones and reset
     * methods, while the exact same bytes/protocol worked instantly from a laptop.
     * Wifi (WifiFirmwareUpdater, over the board's own AP) is the actual install path.
     */
    private fun setUpFirmwareUpdateUi() {
        val buttonCheckUpdate = findViewById<Button>(R.id.buttonCheckUpdate)
        val buttonInstallUpdate = findViewById<Button>(R.id.buttonInstallUpdate)
        val textUpdateStatus = findViewById<TextView>(R.id.textUpdateStatus)
        var pendingRelease: FirmwareUpdater.Release? = null
        var pendingFirmware: ByteArray? = null

        buttonCheckUpdate.setOnClickListener {
            buttonCheckUpdate.isEnabled = false
            buttonInstallUpdate.visibility = View.GONE
            pendingRelease = null
            pendingFirmware = null
            textUpdateStatus.text = "Checking board version…"
            lifecycleScope.launch {
                val link = service?.activeLink as? ServoLinkCommands
                // Up to 3 attempts, not 1 - confirmed on real hardware as a real gap: a single
                // shot right after connecting could race UsbServoBridge.openPort()'s own
                // background checkJoystickChannels()/checkCalibration() round-trips for the
                // same port's read stream (same underlying reasoning as their own retry loop's
                // doc), showing "could not read board version" on the very first tap even
                // though the board was genuinely connected.
                var currentVersion: String? = null
                if (link != null) {
                    for (attempt in 1..3) {
                        val result = withContext(Dispatchers.IO) { link.sendCommand("GETVERSION") }
                        currentVersion = (result as? CommandResult.Ok)?.response?.removePrefix("VERSION=")
                        if (currentVersion != null) break
                    }
                }
                if (currentVersion == null) {
                    textUpdateStatus.text = "Could not read board version - connect via USB or Bluetooth first"
                    buttonCheckUpdate.isEnabled = true
                    return@launch
                }
                textUpdateStatus.text = "Board is v$currentVersion, checking GitHub…"
                val internet = withContext(Dispatchers.IO) { getInternetNetwork() }
                val release = withContext(Dispatchers.IO) { FirmwareUpdater.fetchLatestRelease(internet) }
                when {
                    release == null -> {
                        textUpdateStatus.text = "Board v$currentVersion - could not reach GitHub"
                        buttonCheckUpdate.isEnabled = true
                    }
                    FirmwareUpdater.isNewer(release.version, currentVersion) -> {
                        textUpdateStatus.text = "Downloading v${release.version}…"
                        val bytes = withContext(Dispatchers.IO) { FirmwareUpdater.download(release.downloadUrl, internet) }
                        buttonCheckUpdate.isEnabled = true
                        if (bytes == null) {
                            textUpdateStatus.text = "Update v${release.version} found, but download failed"
                        } else {
                            pendingRelease = release
                            pendingFirmware = bytes
                            textUpdateStatus.text = "v${release.version} downloaded. Connect your phone to the " +
                                "board's \"MespDrive\" wifi (password 123456789), then tap Install."
                            buttonInstallUpdate.visibility = View.VISIBLE
                        }
                    }
                    else -> {
                        textUpdateStatus.text = "Board v$currentVersion - up to date"
                        buttonCheckUpdate.isEnabled = true
                    }
                }
            }
        }

        // Installs over the board's own wifi AP (WifiFirmwareUpdater) - see this
        // function's doc and WifiFirmwareUpdater's doc for why not USB. The firmware is
        // already fully downloaded by the time this runs (buttonCheckUpdate's handler
        // above), so this step needs no internet of its own - only
        // EspWifiNetworkBinder's no-internet AP connection.
        buttonInstallUpdate.setOnClickListener {
            val release = pendingRelease ?: return@setOnClickListener
            val bytes = pendingFirmware ?: return@setOnClickListener
            buttonInstallUpdate.isEnabled = false
            buttonCheckUpdate.isEnabled = false
            textUpdateStatus.text = "Waiting for \"MespDrive\" wifi…"
            lifecycleScope.launch {
                val binder = EspWifiNetworkBinder(this@VehicleActivity)
                val network = withTimeoutOrNull(20_000) {
                    binder.observe().filterIsInstance<EspWifiNetworkBinder.State.Available>().first().network
                }
                if (network == null) {
                    textUpdateStatus.text = "Not connected to \"MespDrive\" - join it in wifi settings and try again"
                    buttonInstallUpdate.isEnabled = true
                    buttonCheckUpdate.isEnabled = true
                    return@launch
                }
                textUpdateStatus.text = "Uploading 0%…"
                val (success, message) = withContext(Dispatchers.IO) {
                    WifiFirmwareUpdater().upload(
                        network,
                        "192.168.4.1",
                        bytes,
                        onProgress = { written, total ->
                            runOnUiThread { textUpdateStatus.text = "Uploading ${written * 100 / total}%…" }
                        },
                    )
                }
                textUpdateStatus.text = if (success) {
                    "v${release.version} installed - disconnect from \"MespDrive\" wifi to restore internet"
                } else {
                    "Update failed: $message"
                }
                buttonCheckUpdate.isEnabled = true
                if (success) {
                    pendingRelease = null
                    pendingFirmware = null
                } else {
                    buttonInstallUpdate.isEnabled = true
                }
            }
        }
    }

    /**
     * Null on timeout (best-effort - callers just fall back to whatever the phone's
     * current default route is, matching the old unbound-client behavior). See
     * InternetNetworkBinder's doc for why this exists at all: joining the ESP32's
     * own AP for the wifi update path below can silently become the phone's default
     * route, breaking anything that needs *real* internet (the GitHub check/download)
     * with no obvious reason why from the error alone.
     */
    private suspend fun getInternetNetwork() = withTimeoutOrNull(10_000) {
        InternetNetworkBinder(applicationContext).observe()
            .filterIsInstance<InternetNetworkBinder.State.Available>()
            .first().network
    }

    override fun onResume() {
        super.onResume()
        recheckYggdrasil?.invoke()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showPermissionDeniedIfAny(grantResults)
    }

    override fun onDestroy() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        // Deliberately NOT touching controlLink/videoSender/audioSender/
        // controllerVoiceReceiver here - they're VehicleForegroundService's now,
        // and must keep running exactly because this Activity is being torn
        // down (backgrounded, not the whole app closing) - see its class doc.
        // Only unhook this screen's own UI callbacks so a stale runOnUiThread
        // never fires into a destroyed Activity.
        service?.onLog = null
        service?.onRemoteControl = null
        service?.onListeningStateChanged = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
