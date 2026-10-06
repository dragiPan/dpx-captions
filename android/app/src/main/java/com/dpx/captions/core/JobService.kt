package com.dpx.captions.core

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dpx.captions.DpxApplication
import com.dpx.captions.MainActivity
import com.dpx.captions.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while transcription or export runs. Without it Android freezes or kills a
 * backgrounded app within seconds, which would lose a multi-minute transcription whenever the screen
 * turns off. The work itself runs in [JobRunner]; this only holds the process up and shows progress.
 */
class JobService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Captions processing", NotificationManager.IMPORTANCE_LOW),
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dpxcaptions:job")
            .apply { acquire(2 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as DpxApplication).container

        if (intent?.action == ACTION_CANCEL) {
            container.cancelAllWork()
            return START_NOT_STICKY
        }

        enterForeground(buildNotification("Starting", null))

        scope.launch {
            var lastText = ""
            var lastAt = 0L
            container.work.status.collect { status ->
                if (status == null) return@collect
                val progress = status.progress
                val text = if (progress != null) "${status.text} ${(progress * 100).toInt()}%" else status.text
                // Progress arrives many times a second; the system sheds notification updates beyond ~5/s,
                // and nobody can read a percentage that changes faster than about once a second anyway.
                val now = android.os.SystemClock.elapsedRealtime()
                if (text == lastText || now - lastAt < 1000) return@collect
                lastText = text
                lastAt = now
                getSystemService(NotificationManager::class.java).notify(ID, buildNotification(text, progress))
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(ID, notification, type)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun buildNotification(text: String, progress: Float?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, JobService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DPX Captions")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .apply {
                if (progress != null) setProgress(100, (progress * 100).toInt(), false) else setProgress(0, 0, true)
            }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jobs"
        private const val ID = 1
        private const val ACTION_CANCEL = "com.dpx.captions.CANCEL_JOB"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JobService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JobService::class.java))
        }
    }
}
