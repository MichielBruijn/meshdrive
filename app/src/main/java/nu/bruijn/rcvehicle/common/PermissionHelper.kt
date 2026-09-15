package nu.bruijn.rcvehicle.common

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import com.google.android.material.snackbar.Snackbar
import nu.bruijn.rcvehicle.R

/**
 * Shows a Snackbar with an "Open Settings" shortcut whenever a permission request
 * comes back with at least one denial. Previously, a denied permission just
 * silently degraded a feature (video/audio/GPS skipped, logged only) with no way
 * back in-app short of the user remembering which system Settings screen to dig
 * into themselves - call from onRequestPermissionsResult() wherever this app asks
 * for a runtime permission (VehicleActivity, ControllerDriveActivity).
 */
fun Activity.showPermissionDeniedIfAny(grantResults: IntArray) {
    if (grantResults.isEmpty() || grantResults.all { it == PackageManager.PERMISSION_GRANTED }) return
    val root = findViewById<View>(android.R.id.content)
    Snackbar.make(root, getString(R.string.permission_denied_open_settings), Snackbar.LENGTH_LONG)
        .setAction(R.string.permission_open_settings_action) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
        .show()
}
