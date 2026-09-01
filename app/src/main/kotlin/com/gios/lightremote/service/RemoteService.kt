package com.gios.lightremote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gios.lightremote.MainActivity
import com.gios.lightremote.R

/**
 * A foreground service that keeps the app's process — and with it the live Companion socket and
 * the media session — alive while the screen is off.
 *
 * The connection itself lives in [com.gios.lightremote.ui.RemoteViewModel], which survives the
 * activity being stopped (screen-off stops the activity, it does not finish it). What the
 * activity's lifecycle cannot do on its own is stop the OS freezing or reclaiming a backgrounded
 * process — a remote whose process is killed the moment the phone sleeps is a remote that has
 * dropped its television by the time you pick the phone back up. Running as a foreground service
 * is what holds the process open; it does not hold the CPU awake (nor should it — see the Doze
 * note in the memory), and it does not need to, because the moment the user touches the phone the
 * process is scheduled again and the socket is still there.
 *
 * Started on a successful connect, stopped on disconnect, on the user leaving, and on the
 * view model's 30-minute idle timeout. The notification is deliberately the quietest kind
 * Android has — a LOW-importance ongoing line that says which television is connected — because
 * it is a status, not an alert.
 */
class RemoteService : Service() {

    companion object {
        private const val CHANNEL_ID = "remote_connection"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.gios.lightremote.START"
        private const val ACTION_STOP = "com.gios.lightremote.STOP"
        private const val EXTRA_NAME = "name"

        /** Bring the service up (or refresh its notification) for a connection to [name]. */
        fun start(context: Context, name: String) {
            val intent = Intent(context, RemoteService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NAME, name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Tear the service down; the ongoing notification goes with it. */
        fun stop(context: Context) {
            val intent = Intent(context, RemoteService::class.java).apply { action = ACTION_STOP }
            // startService rather than stopService so the STOP arrives in onStartCommand and the
            // service can drop foreground cleanly even if it had not fully started yet.
            runCatching { context.startService(intent) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "Apple TV"
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(name),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        // Not sticky: if the process is killed there is nothing to restart, and a socket that
        // died with the process cannot be resumed by a bare service restart — the view model
        // picks the connection back up on the next foreground instead.
        return START_NOT_STICKY
    }

    private fun buildNotification(name: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Connected to $name")
            .setSmallIcon(R.drawable.ic_home_white)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while connected to an Apple TV"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
