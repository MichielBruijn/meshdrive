package nu.bruijn.rcvehicle.common

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Spawns `logcat`, filtered to this process's own PID plus the tags this app
 * actually cares about, piping straight to a plain-text file in app-external
 * storage - toggled from MainActivity's debug section
 * (ModePrefs.debugFileLoggingEnabled).
 *
 * Built after a whole night of debugging VPN/network-churn issues needed
 * exactly this, done by hand over adb every time: an `adb shell logcat -f ...`
 * session, itself fragile (tied to the adb TCP connection and its own shell
 * process), which more than once died silently right around the exact network
 * churn being investigated - the one moment it mattered most. Spawned as this
 * app's own child process instead, it only ever stops when this app's process
 * does, which is the boundary that actually matters here. `--pid` self-
 * filtering needs no READ_LOGS permission - Android has always let a process
 * read back its own emitted log lines without it.
 *
 * Safe to call [start] from more than one entry point (MainActivity,
 * VehicleForegroundService, ControllerDriveActivity all do, each covering a
 * process-(re)start path the others might miss - e.g. Android restarting just
 * a START_STICKY service without MainActivity ever running again) - later
 * calls are a no-op once a session is already running in this process.
 */
object DebugFileLogger {
    private const val TAG = "DebugFileLogger"
    private const val FILE_NAME = "meshdrive_debug.log"

    @Volatile private var process: Process? = null

    fun file(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    fun start(context: Context) {
        if (process != null) return
        try {
            val file = file(context) ?: return
            val pid = android.os.Process.myPid()
            process = ProcessBuilder(
                "logcat", "-v", "time",
                "--pid=$pid",
                "RCDebug:D", "YggdrasilVpnService:V", "GoLog:V", "AndroidRuntime:E", "*:S",
                "-f", file.absolutePath,
            ).start()
        } catch (e: Exception) {
            Log.w(TAG, "failed to start: ${e.message}")
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
