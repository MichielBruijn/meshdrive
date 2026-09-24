package nu.bruijn.rcvehicle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nu.bruijn.rcvehicle.controller.ControllerActivity
import nu.bruijn.rcvehicle.mode.AppMode
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.vehicle.VehicleActivity
import nu.bruijn.rcvehicle.yggdrasil.YggdrasilController
import java.io.File

/** Mode picker: remembers the last choice so relaunch goes straight to that mode. */
class MainActivity : AppCompatActivity() {

    private lateinit var modePrefs: ModePrefs

    // Result of the (rare, one-time-per-install) system "Connection request" dialog
    // that VpnService.prepare() returns an Intent for. selectMode() only reaches
    // this launcher when consent was actually needed; startEmbeddedYggdrasil() runs
    // in the RESULT_OK branch either way (called directly when prepareIntent() was
    // already null) so the two paths converge on one place.
    private var pendingMode: AppMode? = null
    private val vpnConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val mode = pendingMode
        pendingMode = null
        if (mode == null) return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            startEmbeddedYggdrasilThenLaunch(mode)
        } else {
            Toast.makeText(this, "VPN permission needed for Yggdrasil mesh", Toast.LENGTH_LONG).show()
            launchModeActivity(mode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // "demo" flavor only (BuildConfig.EXPIRES_AT_MS is 0L, i.e. never, on the
        // "standard" flavor every other build - including Michiel's own - uses) -
        // a build handed to an outside party for evaluation stops working 6
        // months after it was built, full stop, before anything else in this
        // screen (or the USB-attach relaunch check below) runs.
        if (BuildConfig.EXPIRES_AT_MS != 0L && System.currentTimeMillis() > BuildConfig.EXPIRES_AT_MS) {
            setContentView(android.widget.TextView(this).apply {
                text = getString(R.string.main_trial_expired)
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setPadding(64, 64, 64, 64)
            })
            return
        }

        // The manifest's USB_DEVICE_ATTACHED intent-filter launches a fresh instance
        // of this picker on EVERY USB attach, unconditionally - including while
        // VehicleActivity is already open and driving, which has its own runtime
        // receiver for exactly this (reconnecting the ESP32 without leaving that
        // screen). Confirmed on real hardware as a real bug: reconnecting the cable
        // mid-drive yanked the app back to this picker, landing on top of a
        // VehicleActivity that was still alive underneath and already reconnecting
        // itself. !isTaskRoot means this instance landed on top of something
        // already running (true whenever the app was already open) - just get out
        // of the way immediately and let that resume, rather than showing the
        // picker over it. A genuine cold start (isTaskRoot true - the app wasn't
        // running yet) still falls through to the normal picker below.
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED && !isTaskRoot) {
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        modePrefs = ModePrefs(applicationContext)
        // One-time upgrade from pre-vehicleId app versions - see its own doc.
        // Run here, unconditionally on every launch (cheap/no-op past the
        // first), so it's always done before ControllerActivity (the only
        // reader of this data) could possibly run.
        lifecycleScope.launch { modePrefs.migrateToVehicleIdKeysIfNeeded() }

        // "demo" flavor only - see the expiry check above for why this can't
        // be a subtle footnote: whoever's evaluating this build needs to know
        // up front, every launch, that it's not the real thing and exactly
        // when it stops working.
        if (BuildConfig.EXPIRES_AT_MS != 0L) {
            // Locale.US, not getDefault(): the banner text itself is English
            // (like the rest of this app's strings) - a Dutch month name here
            // on a Dutch-locale device read as a mismatched half-translation.
            val dateStr = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US)
                .format(java.util.Date(BuildConfig.EXPIRES_AT_MS))
            findViewById<android.widget.TextView>(R.id.textDemoBanner).apply {
                text = getString(R.string.main_demo_banner, dateStr)
                visibility = View.VISIBLE
            }

            // Per direct request: the license text lives in the app itself
            // (a private GitHub repo's LICENSE file isn't visible to whoever's
            // evaluating a demo build) and must be re-accepted on every single
            // launch, not just once - no persisted "already agreed" state.
            // Non-cancelable (no dismiss via back/outside-tap): Decline is the
            // only way out of it besides Agree, and closes the app outright.
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.main_license_title)
                .setMessage(R.string.main_license_text)
                .setCancelable(false)
                .setPositiveButton(R.string.main_license_agree, null)
                .setNegativeButton(R.string.main_license_decline) { _, _ -> finish() }
                .show()
        }

        findViewById<android.widget.Button>(R.id.buttonController).setOnClickListener {
            selectMode(AppMode.CONTROLLER)
        }
        findViewById<android.widget.Button>(R.id.buttonVehicle).setOnClickListener {
            selectMode(AppMode.VEHICLE)
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        val textAppVersion = findViewById<android.widget.TextView>(R.id.textAppVersion)
        textAppVersion.text = "v$versionName"
        setUpAppUpdateCheck(versionName ?: "0")

        val checkDebugMode = findViewById<CheckBox>(R.id.checkDebugMode)
        val checkDebugFileLogging = findViewById<CheckBox>(R.id.checkDebugFileLogging)
        val editYggdrasilPeers = findViewById<EditText>(R.id.editYggdrasilPeers)
        val buttonSaveYggdrasilPeers = findViewById<Button>(R.id.buttonSaveYggdrasilPeers)

        fun setDebugSectionVisible(visible: Boolean) {
            val v = if (visible) View.VISIBLE else View.GONE
            checkDebugMode.visibility = v
            checkDebugFileLogging.visibility = v
            editYggdrasilPeers.visibility = v
            buttonSaveYggdrasilPeers.visibility = v
        }

        lifecycleScope.launch {
            // Already-on stays visible (and tappable to turn back off) without
            // needing the tap gesture again - only OFF starts out hidden.
            val debugEnabled = modePrefs.debugEnabled.first()
            checkDebugMode.isChecked = debugEnabled
            if (debugEnabled) setDebugSectionVisible(true)
            editYggdrasilPeers.setText(modePrefs.yggdrasilPeers.first().joinToString(", "))
            val fileLoggingEnabled = modePrefs.debugFileLoggingEnabled.first()
            checkDebugFileLogging.isChecked = fileLoggingEnabled
            // One of DebugFileLogger's several start() call sites - see its own
            // doc for why more than one exists.
            if (fileLoggingEnabled) nu.bruijn.rcvehicle.common.DebugFileLogger.start(applicationContext)
        }
        checkDebugMode.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { modePrefs.setDebugEnabled(checked) }
        }
        checkDebugFileLogging.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { modePrefs.setDebugFileLoggingEnabled(checked) }
            if (checked) {
                nu.bruijn.rcvehicle.common.DebugFileLogger.start(applicationContext)
            } else {
                nu.bruijn.rcvehicle.common.DebugFileLogger.stop()
            }
        }
        buttonSaveYggdrasilPeers.setOnClickListener {
            val peers = editYggdrasilPeers.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            lifecycleScope.launch {
                modePrefs.setYggdrasilPeers(peers)
                Toast.makeText(this@MainActivity, "Saved ${peers.size} peer(s) - applies next time a mode is picked", Toast.LENGTH_SHORT).show()
            }
        }

        // Same convention as Android's own "tap build number 7 times" for developer
        // options - debug mode is meant for diagnosing problems, not a normal setting,
        // so it stays out of the way until asked for.
        val tapsToReveal = 7
        var versionTapCount = 0
        textAppVersion.setOnClickListener {
            if (checkDebugMode.visibility == View.VISIBLE) return@setOnClickListener
            versionTapCount++
            val remaining = tapsToReveal - versionTapCount
            when {
                remaining <= 0 -> {
                    setDebugSectionVisible(true)
                    Toast.makeText(this, "Debug mode available", Toast.LENGTH_SHORT).show()
                }
                remaining <= 3 -> Toast.makeText(this, "$remaining more taps for debug mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectMode(mode: AppMode) {
        if (nu.bruijn.rcvehicle.yggdrasil.VpnConflictDetector.isForeignVpnActive(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.main_vpn_conflict_title)
                .setMessage(R.string.main_vpn_conflict_message)
                .setPositiveButton(R.string.main_vpn_conflict_continue) { _, _ -> proceedWithMode(mode) }
                .setNegativeButton(R.string.main_vpn_conflict_cancel, null)
                .show()
            return
        }
        proceedWithMode(mode)
    }

    private fun proceedWithMode(mode: AppMode) {
        lifecycleScope.launch {
            modePrefs.setMode(mode)
            // Some other app (the standalone Yggdrasil app, most likely) may
            // already have the OS VPN slot and be routing 200::/7. Checking
            // this only has to happen here, before ever calling prepareIntent()
            // - confirmed live as a real bug: merely completing
            // VpnService.prepare()'s consent handshake for OUR package evicts
            // whoever currently holds the VPN slot as an OS-level side effect,
            // even if start() is never subsequently called. A check placed
            // after prepare()/consent (where it used to live) was already too
            // late - the other app's session was gone by then regardless.
            // Our own running service also owns a 200::/7 address - without this check it was
            // taken for an external app, so start() (and thus a changed peer list) was skipped
            // on every mode pick after the first.
            val externalAddress = if (YggdrasilController.isOwnServiceRunning()) null
                else nu.bruijn.rcvehicle.yggdrasil.ExternalYggdrasilDetector.currentAddress()
            android.util.Log.d("RCDebug", "external Yggdrasil check before mode start: $externalAddress")
            if (externalAddress != null) {
                launchModeActivity(mode)
                return@launch
            }
            val consent = YggdrasilController.prepareIntent(this@MainActivity)
            if (consent != null) {
                pendingMode = mode
                vpnConsentLauncher.launch(consent)
            } else {
                startEmbeddedYggdrasilThenLaunch(mode)
            }
        }
    }

    private fun startEmbeddedYggdrasilThenLaunch(mode: AppMode) {
        lifecycleScope.launch {
            val candidates = modePrefs.yggdrasilPeerCandidates.first()
            YggdrasilController.start(this@MainActivity, candidates)
            launchModeActivity(mode)
        }
    }

    private fun launchModeActivity(mode: AppMode) {
        val target = when (mode) {
            AppMode.CONTROLLER -> ControllerActivity::class.java
            AppMode.VEHICLE -> VehicleActivity::class.java
        }
        startActivity(Intent(this@MainActivity, target))
    }

    /**
     * Checks GitHub Releases for a newer build of this app itself, silently, on
     * every launch of this screen - same idea as VehicleActivity's ESP32-firmware
     * update check (FirmwareUpdater), applied to MeshDrive's own APK (AppUpdater).
     * Per direct request: a button that "checkt bij opstarten en kan updaten."
     * Stays invisible unless a genuinely newer release is actually found - this
     * screen shouldn't nag on every single launch once already up to date.
     */
    private fun setUpAppUpdateCheck(currentVersion: String) {
        val buttonUpdate = findViewById<Button>(R.id.buttonUpdateApp)
        val textUpdateStatus = findViewById<TextView>(R.id.textUpdateStatus)
        var pendingRelease: AppUpdater.Release? = null

        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { AppUpdater.fetchLatestRelease() }
            if (release != null && AppUpdater.isNewer(release.version, currentVersion)) {
                pendingRelease = release
                buttonUpdate.text = getString(R.string.main_update_available, release.version)
                buttonUpdate.visibility = View.VISIBLE
            }
        }

        buttonUpdate.setOnClickListener {
            val release = pendingRelease ?: return@setOnClickListener

            // Android blocks a plain download-and-install even with
            // REQUEST_INSTALL_PACKAGES in the manifest until the user has
            // separately allowed this specific app as an install source - send
            // them to that one-time toggle instead of just silently failing.
            if (!packageManager.canRequestPackageInstalls()) {
                textUpdateStatus.text = getString(R.string.main_update_install_permission)
                textUpdateStatus.visibility = View.VISIBLE
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }

            buttonUpdate.isEnabled = false
            textUpdateStatus.visibility = View.VISIBLE
            textUpdateStatus.text = getString(R.string.main_update_downloading, release.version)
            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) { AppUpdater.download(release.downloadUrl) }
                if (bytes == null) {
                    textUpdateStatus.text = getString(R.string.main_update_download_failed)
                    buttonUpdate.isEnabled = true
                    return@launch
                }
                val apkFile = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "updates").apply { mkdirs() }
                    File(dir, "update.apk").apply { writeBytes(bytes) }
                }
                // content:// via FileProvider, not a plain file:// Uri - the system
                // installer is a different app, which would otherwise reject it
                // with FileUriExposedException (see the manifest's <provider>).
                val apkUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", apkFile)
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                buttonUpdate.isEnabled = true
            }
        }
    }
}
