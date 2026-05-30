package io.carmo.airplay.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder

class ReceiverForegroundService : Service() {

    inner class ReceiverBinder : Binder() {
        val runtime: ReceiverRuntime get() = this@ReceiverForegroundService.runtime
    }

    private val binder = ReceiverBinder()
    private lateinit var networkMonitor: NetworkMonitor
    lateinit var runtime: ReceiverRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = ReceiverRuntime(this)
        networkMonitor = NetworkMonitor(
            context = this,
            onNetworkAvailable = {
                if (runtime.state != ReceiverState.STOPPED) {
                    runtime.refreshDiscovery()
                }
            },
            onNetworkLost = {
                // DNS-SD will become unreachable; refresh happens when the network returns.
            }
        )
        networkMonitor.start()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (runtime.state == ReceiverState.STOPPED) {
            val prefs = getSharedPreferences("receiver", Context.MODE_PRIVATE)
            val videoWidth = if (prefs.getString("video_mode_v2", "720p") == "1080p") 1920 else 1280
            val videoHeight = if (prefs.getString("video_mode_v2", "720p") == "1080p") 1080 else 720
            runtime.start(videoWidth, videoHeight, loadAudioVolume())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (::networkMonitor.isInitialized) {
            networkMonitor.stop()
        }
        if (::runtime.isInitialized) {
            runtime.stop()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun startAsForeground() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_receiver_active))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_receiver),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun loadAudioVolume(): Float {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return 1.0f
        }
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
            .coerceIn(0.0f, 1.0f)
    }

    companion object {
        private const val CHANNEL_ID = "receiver_active"
        private const val NOTIFICATION_ID = 1001
    }
}
