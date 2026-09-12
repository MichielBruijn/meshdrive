package nu.bruijn.rcvehicle.vehicle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Hosts the signaling server, RTC session, camera capture and servo bridge (plan
 * sections 3-6) so they keep running with the screen off. Empty shell for now -
 * wired up once SignalingServer/RtcClient/ServoBridge exist.
 */
class VehicleForegroundService : Service() {

    private val channelId = "vehicle_service"

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Vehicle link", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("RC Vehicle")
            .setContentText("Wachten op besturing…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
