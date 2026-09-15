package nu.bruijn.rcvehicle

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.controller.ControllerActivity
import nu.bruijn.rcvehicle.mode.AppMode
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.vehicle.VehicleActivity
import nu.bruijn.rcvehicle.yggdrasil.YggdrasilController

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

        findViewById<android.widget.Button>(R.id.buttonController).setOnClickListener {
            selectMode(AppMode.CONTROLLER)
        }
        findViewById<android.widget.Button>(R.id.buttonVehicle).setOnClickListener {
            selectMode(AppMode.VEHICLE)
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        val textAppVersion = findViewById<android.widget.TextView>(R.id.textAppVersion)
        textAppVersion.text = "v$versionName"

        val checkDebugMode = findViewById<CheckBox>(R.id.checkDebugMode)
        val editYggdrasilPeers = findViewById<EditText>(R.id.editYggdrasilPeers)
        val buttonSaveYggdrasilPeers = findViewById<Button>(R.id.buttonSaveYggdrasilPeers)

        fun setDebugSectionVisible(visible: Boolean) {
            val v = if (visible) View.VISIBLE else View.GONE
            checkDebugMode.visibility = v
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
        }
        checkDebugMode.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { modePrefs.setDebugEnabled(checked) }
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
        lifecycleScope.launch {
            modePrefs.setMode(mode)
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
}
