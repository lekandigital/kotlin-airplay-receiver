package io.carmo.airplay.receiver

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import java.util.concurrent.CopyOnWriteArraySet

class ReceiverRuntime private constructor(private val context: Context) {

    data class State(
        val deviceName: String,
        val isStarted: Boolean,
        val isStreaming: Boolean,
        val discoveryStatus: String,
        val streamStatus: String,
        val airplayPort: Int,
        val raopPort: Int
    )

    interface Listener {
        fun onRuntimeStateChanged(state: State) = Unit
        fun onRuntimeConnectionStarted() = Unit
        fun onRuntimeVideoActivity(hasVideoActivity: Boolean) = Unit
        fun onRuntimeTrafficSample(byteCount: Int) = Unit
        fun onRuntimeLatencySample(latencyMs: Long) = Unit
        fun onRuntimeStreamStopped() = Unit
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var airPlayServer: AirPlayServer? = null
    private var raopServer: RaopServer? = null
    private var dnsNotify: DNSNotify? = null
    @Volatile private var isStarted = false
    @Volatile private var isStreaming = false
    @Volatile private var isActivitySurfaceAttached = false
    @Volatile private var lastUiLaunchAttemptAtMs = 0L
    @Volatile private var discoveryStatus = "Discovery starting"
    @Volatile private var streamStatus = "Stream idle"

    @Synchronized
    fun start() {
        if (isStarted) {
            refreshAnnouncements()
            return
        }

        val videoMode = loadVideoMode()
        val audioVolume = loadAudioVolume()
        val dns = dnsNotify ?: DNSNotify(context, ::handleDiscoveryStatus).also { dnsNotify = it }
        val airplay = airPlayServer ?: AirPlayServer().also { airPlayServer = it }
        val raop = raopServer ?: RaopServer(
            null,
            ::handleConnectionStarted,
            ::handleVideoActivity,
            ::notifyTrafficSample,
            ::notifyLatencySample,
            ::handleStreamStopped,
            ::handleStreamStatus,
            videoMode.width,
            videoMode.height,
            audioVolume
        ).also { raopServer = it }

        acquireMulticastLock()
        airplay.startServer()
        val airplayPort = airplay.port
        if (airplayPort == 0) {
            handleDiscoveryStatus("AirPlay failed: port unavailable")
        } else {
            dns.registerAirplay(airplayPort)
        }

        raop.startServer()
        val raopPort = raop.port
        if (raopPort == 0) {
            handleDiscoveryStatus("RAOP failed: port unavailable")
        } else {
            dns.registerRaop(raopPort)
        }

        isStarted = true
        notifyStateChanged()
        Log.d(TAG, "deviceName = ${dns.deviceName}, airplayPort = $airplayPort, raopPort = $raopPort")
    }

    @Synchronized
    fun stop() {
        dnsNotify?.stop()
        airPlayServer?.stopServer()
        raopServer?.stopServer()
        releaseMulticastLock()
        isStarted = false
        isStreaming = false
        isActivitySurfaceAttached = false
        discoveryStatus = "Discovery stopped"
        streamStatus = "Stream idle"
        notifyStateChanged()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onRuntimeStateChanged(snapshot())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun attachSurface(surfaceView: SurfaceView, width: Int, height: Int, audioVolume: Float) {
        start()
        isActivitySurfaceAttached = true
        raopServer?.setVideoMode(width, height)
        raopServer?.setAudioVolume(audioVolume)
        raopServer?.attachSurface(surfaceView)
        refreshAnnouncements()
    }

    fun detachSurface(surfaceView: SurfaceView) {
        isActivitySurfaceAttached = false
        raopServer?.detachSurface(surfaceView)
    }

    fun setVideoMode(width: Int, height: Int) {
        raopServer?.setVideoMode(width, height)
    }

    fun setAudioVolume(volume: Float) {
        raopServer?.setAudioVolume(volume)
    }

    fun refreshAnnouncements() {
        if (!isStarted) {
            return
        }
        acquireMulticastLock()
        val dns = dnsNotify ?: return
        val airplayPort = airPlayServer?.port ?: 0
        if (airplayPort != 0) {
            dns.registerAirplay(airplayPort)
        }
        val raopPort = raopServer?.port ?: 0
        if (raopPort != 0) {
            dns.registerRaop(raopPort)
        }
        notifyStateChanged()
    }

    fun snapshot(): State {
        val dns = dnsNotify
        return State(
            deviceName = dns?.deviceName ?: resolveDeviceName(),
            isStarted = isStarted,
            isStreaming = isStreaming,
            discoveryStatus = discoveryStatus,
            streamStatus = streamStatus,
            airplayPort = airPlayServer?.port ?: 0,
            raopPort = raopServer?.port ?: 0
        )
    }

    private fun handleConnectionStarted() {
        isStreaming = true
        streamStatus = "Streaming"
        notifyStateChanged()
        listeners.forEach { it.onRuntimeConnectionStarted() }
    }

    private fun handleVideoActivity(hasVideoActivity: Boolean) {
        if (hasVideoActivity && !hasAttachedSurface()) {
            bringReceiverToFront()
        }
        listeners.forEach { it.onRuntimeVideoActivity(hasVideoActivity) }
    }

    private fun handleStreamStopped() {
        isStreaming = false
        streamStatus = "Stream idle"
        refreshAnnouncements()
        notifyStateChanged()
        listeners.forEach { it.onRuntimeStreamStopped() }
    }

    private fun handleDiscoveryStatus(status: String) {
        discoveryStatus = status
        notifyStateChanged()
    }

    private fun handleStreamStatus(status: String) {
        streamStatus = status
        notifyStateChanged()
    }

    private fun notifyTrafficSample(byteCount: Int) {
        listeners.forEach { it.onRuntimeTrafficSample(byteCount) }
    }

    private fun notifyLatencySample(latencyMs: Long) {
        listeners.forEach { it.onRuntimeLatencySample(latencyMs) }
    }

    private fun notifyStateChanged() {
        val state = snapshot()
        listeners.forEach { it.onRuntimeStateChanged(state) }
    }

    private fun bringReceiverToFront() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiLaunchAttemptAtMs < UI_LAUNCH_THROTTLE_MS) {
            return
        }
        lastUiLaunchAttemptAtMs = now
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        try {
            PendingIntent.getActivity(
                context,
                VIDEO_ACTIVITY_REQUEST_CODE,
                intent,
                pendingIntentFlags()
            ).send()
        } catch (e: Throwable) {
            Log.w(TAG, "could not bring receiver UI to front for video stream", e)
        }
    }

    private fun hasAttachedSurface(): Boolean = isActivitySurfaceAttached

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: run {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.createMulticastLock("${context.packageName}:$MULTICAST_LOCK_TAG").apply {
                setReferenceCounted(false)
                multicastLock = this
            }
        }
        if (!lock.isHeld) {
            lock.acquire()
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock
        if (lock?.isHeld == true) {
            lock.release()
        }
    }

    private fun resolveDeviceName(): String {
        return Build.MODEL ?: context.getString(R.string.app_name)
    }

    private fun loadAudioVolume(): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return DEFAULT_AUDIO_VOLUME
        }
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
            .coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    }

    private fun loadVideoMode(): RuntimeVideoMode {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return when (preferences.getString(PREFERENCE_VIDEO_MODE_V2, null)) {
            FULL_HD.preferenceValue -> FULL_HD
            else -> HD
        }
    }

    companion object {
        private const val TAG = "Receiver-Runtime"
        private const val MULTICAST_LOCK_TAG = "ReceiverDiscovery"
        private const val PREFERENCES_NAME = "receiver"
        private const val PREFERENCE_VIDEO_MODE_V2 = "video_mode_v2"
        private const val MIN_AUDIO_VOLUME = 0.0f
        private const val MAX_AUDIO_VOLUME = 1.0f
        private const val DEFAULT_AUDIO_VOLUME = 1.0f
        private const val UI_LAUNCH_THROTTLE_MS = 5_000L
        private const val VIDEO_ACTIVITY_REQUEST_CODE = 2001
        private val HD = RuntimeVideoMode("720p", 1280, 720)
        private val FULL_HD = RuntimeVideoMode("1080p", 1920, 1080)

        @Volatile private var instance: ReceiverRuntime? = null

        fun get(context: Context): ReceiverRuntime {
            return instance ?: synchronized(this) {
                instance ?: ReceiverRuntime(context.applicationContext).also { instance = it }
            }
        }
    }

    private data class RuntimeVideoMode(
        val preferenceValue: String,
        val width: Int,
        val height: Int
    )
}
