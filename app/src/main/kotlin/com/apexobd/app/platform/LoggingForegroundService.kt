package com.apexobd.app.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Persistent foreground service keeping the OBD-II adapter listener (and optional GPS trip logger)
 * active while navigating menus or running in the background.
 * Tapping the notification immediately terminates the background service and disconnects the adapter.
 */
class LoggingForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TERMINATE -> {
                isRunning = false
                onTerminateRequested?.invoke()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: lastTitle
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: lastText
        lastTitle = title
        lastText = text

        val notification = buildNotification(title, text)
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                resolveForegroundServiceType()
            )
            true
        }.getOrDefault(false)

        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resolveForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val hasLocationPerm =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocationPerm) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return type
    }

    private fun buildNotification(title: String, text: String): Notification {
        val terminateIntent = Intent(this, LoggingForegroundService::class.java).apply {
            action = ACTION_TERMINATE
        }
        val terminatePendingIntent = PendingIntent.getService(
            this,
            0,
            terminateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(terminatePendingIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OBD Background Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the OBD-II adapter connected in the background. Tap the notification to stop."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "apex_obd_logging"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START_OR_UPDATE = "com.apexobd.app.START_OBD_LISTENER"
        private const val ACTION_STOP = "com.apexobd.app.STOP_LOGGING"
        private const val ACTION_TERMINATE = "com.apexobd.app.TERMINATE_OBD_LISTENER"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var lastTitle: String = "ApexOBD Background Listener"

        @Volatile
        private var lastText: String = "OBD listener active in background · Tap to terminate"

        @Volatile
        private var onTerminateRequested: (() -> Unit)? = null

        fun setTerminateListener(listener: (() -> Unit)?) {
            onTerminateRequested = listener
        }

        fun start(
            context: Context,
            title: String = lastTitle,
            text: String = lastText
        ) {
            val appCtx = context.applicationContext
            lastTitle = title
            lastText = text
            val intent = Intent(appCtx, LoggingForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }
        }

        fun updateIfRunning(context: Context, title: String, text: String) {
            lastTitle = title
            lastText = text
            if (!isRunning) return
            val appCtx = context.applicationContext
            val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
            val terminateIntent = Intent(appCtx, LoggingForegroundService::class.java).apply {
                action = ACTION_TERMINATE
            }
            val terminatePendingIntent = PendingIntent.getService(
                appCtx,
                0,
                terminateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(terminatePendingIntent)
                .build()
            runCatching { nm.notify(NOTIFICATION_ID, notification) }
        }

        fun stop(context: Context) {
            isRunning = false
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, LoggingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { appCtx.stopService(intent) }
        }
    }
}
