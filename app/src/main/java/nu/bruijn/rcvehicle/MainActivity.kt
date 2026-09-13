package nu.bruijn.rcvehicle

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nu.bruijn.rcvehicle.controller.ControllerActivity
import nu.bruijn.rcvehicle.mode.AppMode
import nu.bruijn.rcvehicle.mode.ModePrefs
import nu.bruijn.rcvehicle.vehicle.VehicleActivity

/** Mode picker: remembers the last choice so relaunch goes straight to that mode. */
class MainActivity : AppCompatActivity() {

    private lateinit var modePrefs: ModePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        lifecycleScope.launch {
            // Already-on stays visible (and tappable to turn back off) without
            // needing the tap gesture again - only OFF starts out hidden.
            val debugEnabled = modePrefs.debugEnabled.first()
            checkDebugMode.isChecked = debugEnabled
            if (debugEnabled) checkDebugMode.visibility = View.VISIBLE
        }
        checkDebugMode.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { modePrefs.setDebugEnabled(checked) }
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
                    checkDebugMode.visibility = View.VISIBLE
                    Toast.makeText(this, "Debug mode available", Toast.LENGTH_SHORT).show()
                }
                remaining <= 3 -> Toast.makeText(this, "$remaining more taps for debug mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectMode(mode: AppMode) {
        lifecycleScope.launch {
            modePrefs.setMode(mode)
            val target = when (mode) {
                AppMode.CONTROLLER -> ControllerActivity::class.java
                AppMode.VEHICLE -> VehicleActivity::class.java
            }
            startActivity(Intent(this@MainActivity, target))
        }
    }
}
