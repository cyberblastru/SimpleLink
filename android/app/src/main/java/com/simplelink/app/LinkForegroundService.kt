package com.simplelink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class LinkForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            stopServiceSafely()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_STOP -> {
                LinkSession.shutdownConnection()
                releaseWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_CONNECT -> {
                promoteToForeground("Connecting…", connected = false)
                acquireWakeLock()
                val json = intent.getStringExtra(EXTRA_PAIRING)
                if (json == null) {
                    applyStatus("Missing pairing data", connected = false)
                    stopServiceSafely()
                    return START_NOT_STICKY
                }
                val pairing = PairingPayload.parse(json)
                if (pairing == null) {
                    applyStatus("Invalid QR code", connected = false)
                    stopServiceSafely()
                    return START_NOT_STICKY
                }
                LinkSession.clientConnect(pairing)
            }

            ACTION_RECONNECT -> {
                promoteToForeground("Reconnecting…", connected = false)
                acquireWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        releaseWakeLock()
        super.onDestroy()
    }

    internal fun applyStatus(status: String, connected: Boolean) {
        promoteToForeground(status, connected)
        if (connected) acquireWakeLock() else releaseWakeLock()
    }

    private fun promoteToForeground(status: String, connected: Boolean) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(status, connected),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun stopServiceSafely() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpleLink:Connection").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SimpleLink connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps SimpleLink connected to your Mac in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String, connected: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LinkForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (connected) "SimpleLink — connected" else "SimpleLink")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Disconnect", disconnectIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "LinkForegroundService"
        const val ACTION_CONNECT = "connect"
        const val ACTION_RECONNECT = "reconnect"
        const val ACTION_STOP = "stop"
        const val EXTRA_PAIRING = "pairing"

        private const val CHANNEL_ID = "simplelink_connection"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L

        @Volatile
        private var instance: LinkForegroundService? = null

        fun connect(context: Context, pairingJson: String) {
            val intent = Intent(context, LinkForegroundService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PAIRING, pairingJson)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start foreground service", e)
                throw e
            }
        }

        fun ensureReconnecting(context: Context) {
            if (instance != null) {
                instance?.applyStatus("Reconnecting…", connected = false)
                return
            }
            val intent = Intent(context, LinkForegroundService::class.java).apply {
                action = ACTION_RECONNECT
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start reconnect service", e)
            }
        }

        fun notifyStatus(status: String, connected: Boolean) {
            instance?.applyStatus(status, connected)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LinkForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
