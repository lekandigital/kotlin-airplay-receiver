package io.carmo.airplay.receiver

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the AirPlay receiver runtime.
 *
 * Lifecycle: created by ReceiverForegroundService. Lives as long as the service.
 * MainActivity binds to the service and calls attachSurface / detachSurface when
 * it starts and stops.
 */
class ReceiverRuntime(private val context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var airPlayServer: AirPlayServer? = null
    private var raopServer: RaopServer? = null
    private var dnsNotify: DNSNotify? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var isSurfaceAttached = false
    @Volatile private var lastUiLaunchAttemptAtMs = 0L

    @Volatile var state: ReceiverState = ReceiverState.STOPPED
        private set

    @Volatile var discoveryStatus: String = "Discovery starting"
        private set

    @Volatile var streamStatus: String = "Waiting"
        private set

    private val stateListeners = CopyOnWriteArrayList<(ReceiverState) -> Unit>()
    private val discoveryStatusListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val streamStatusListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val videoActivityListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val videoSizeListeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()
    private val trafficListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val latencyListeners = CopyOnWriteArrayList<(Long) -> Unit>()

    val identity: ReceiverIdentity get() = ReceiverIdentity

    /** The display name used for AirPlay advertisement, e.g. "Living Room TV". */
    val deviceDisplayName: String get() = dnsNotify?.deviceName ?: "Receiver"

    fun addStateListener(listener: (ReceiverState) -> Unit) {
        stateListeners.add(listener)
        listener(state)
    }

    fun removeStateListener(listener: (ReceiverState) -> Unit) = stateListeners.remove(listener)

    fun addDiscoveryStatusListener(listener: (String) -> Unit) {
        discoveryStatusListeners.add(listener)
        listener(discoveryStatus)
    }

    fun removeDiscoveryStatusListener(listener: (String) -> Unit) = discoveryStatusListeners.remove(listener)

    fun addStreamStatusListener(listener: (String) -> Unit) {
        streamStatusListeners.add(listener)
        listener(streamStatus)
    }

    fun removeStreamStatusListener(listener: (String) -> Unit) = streamStatusListeners.remove(listener)

    fun addVideoActivityListener(l: (Boolean) -> Unit) = videoActivityListeners.add(l)
    fun removeVideoActivityListener(l: (Boolean) -> Unit) = videoActivityListeners.remove(l)
    fun addVideoSizeListener(l: (Int, Int) -> Unit) = videoSizeListeners.add(l)
    fun removeVideoSizeListener(l: (Int, Int) -> Unit) = videoSizeListeners.remove(l)
    fun addTrafficListener(l: (Int) -> Unit) = trafficListeners.add(l)
    fun removeTrafficListener(l: (Int) -> Unit) = trafficListeners.remove(l)
    fun addLatencyListener(l: (Long) -> Unit) = latencyListeners.add(l)
    fun removeLatencyListener(l: (Long) -> Unit) = latencyListeners.remove(l)

    @Synchronized
    fun start(videoWidth: Int, videoHeight: Int, audioVolume: Float) {
        if (state != ReceiverState.STOPPED) {
            Log.d(TAG, "start() called in state $state; refreshing discovery")
            refreshDiscovery()
            return
        }
        transitionTo(ReceiverState.STARTING, "runtime start")

        acquireMulticastLock()

        val dns = DNSNotify(appContext) { status ->
            discoveryStatus = status
            discoveryStatusListeners.forEach { it(status) }
        }
        dnsNotify = dns

        val raop = RaopServer(
            context = appContext,
            onConnectionStarted = ::onConnectionStarted,
            onVideoActivity = ::onVideoActivity,
            onTrafficSample = ::onTrafficSample,
            onLatencySample = ::onLatencySample,
            onVideoSizeChanged = ::onVideoSizeChanged,
            onStreamStatusChanged = ::onStreamStatusChanged,
            onStateChanged = ::onRaopStateChanged,
            initialVideoWidth = videoWidth,
            initialVideoHeight = videoHeight,
            initialAudioVolume = audioVolume
        )
        raopServer = raop
        raop.startServer()

        val airplay = AirPlayServer()
        airPlayServer = airplay
        airplay.startServer()

        val airplayPort = airplay.port
        val raopPort = raop.port

        if (airplayPort != 0) {
            dns.registerAirplay(airplayPort)
        } else {
            Log.e(TAG, "AirPlay server failed to bind")
            setDiscoveryStatus("AirPlay failed: port unavailable")
        }
        if (raopPort != 0) {
            dns.registerRaop(raopPort)
        } else {
            Log.e(TAG, "RAOP server failed to bind")
            setDiscoveryStatus("RAOP failed: port unavailable")
        }

        if (state == ReceiverState.STARTING) {
            transitionTo(ReceiverState.IDLE_ADVERTISING, "servers started")
        }
        Log.i(TAG, "runtime started; device=${dns.deviceName}, airplayPort=$airplayPort, raopPort=$raopPort")
    }

    @Synchronized
    fun stop() {
        if (state == ReceiverState.STOPPED) return
        transitionTo(ReceiverState.STOPPED, "runtime stop")
        dnsNotify?.stop()
        dnsNotify = null
        airPlayServer?.stopServer()
        airPlayServer = null
        raopServer?.stopServer()
        raopServer = null
        isSurfaceAttached = false
        releaseMulticastLock()
        setStreamStatus("Waiting")
        setDiscoveryStatus("Discovery stopped")
        Log.i(TAG, "runtime stopped")
    }

    /**
     * Attaches a rendering surface. Called by MainActivity when surfaceCreated fires.
     * Safe to call multiple times; idempotent if the same surface is passed.
     */
    fun attachSurface(surface: Surface) {
        isSurfaceAttached = true
        raopServer?.attachSurface(surface)
    }

    /**
     * Detaches the rendering surface. Called by MainActivity when surfaceDestroyed fires
     * or when the activity is being destroyed.
     */
    fun detachSurface() {
        isSurfaceAttached = false
        raopServer?.detachSurface()
    }

    fun setVideoMode(width: Int, height: Int) {
        raopServer?.setVideoMode(width, height)
    }

    fun setAudioVolume(volume: Float) {
        raopServer?.setAudioVolume(volume)
    }

    fun refreshDiscovery() {
        val airplay = airPlayServer ?: return
        val raop = raopServer ?: return
        val dns = dnsNotify ?: return
        acquireMulticastLock()
        if (airplay.port != 0) dns.registerAirplay(airplay.port)
        if (raop.port != 0) dns.registerRaop(raop.port)
    }

    private fun onConnectionStarted() {
        mainHandler.post {
            setStreamStatus("Streaming")
        }
    }

    private fun onVideoActivity(hasActivity: Boolean) {
        if (hasActivity && !isSurfaceAttached) {
            bringReceiverToFront()
        }
        videoActivityListeners.forEach { it(hasActivity) }
    }

    private fun onTrafficSample(bytes: Int) {
        trafficListeners.forEach { it(bytes) }
    }

    private fun onVideoSizeChanged(width: Int, height: Int) {
        videoSizeListeners.forEach { it(width, height) }
    }

    private fun onLatencySample(latencyMs: Long) {
        latencyListeners.forEach { it(latencyMs) }
    }

    private fun onStreamStatusChanged(status: String) {
        mainHandler.post {
            setStreamStatus(status)
        }
    }

    private fun onRaopStateChanged(newState: ReceiverState) {
        mainHandler.post {
            if (newState == ReceiverState.IDLE_ADVERTISING) {
                setStreamStatus("Waiting")
            }
            transitionTo(newState, "raop state change")
        }
    }

    private fun setDiscoveryStatus(status: String) {
        discoveryStatus = status
        discoveryStatusListeners.forEach { it(status) }
    }

    private fun setStreamStatus(status: String) {
        streamStatus = status
        streamStatusListeners.forEach { it(status) }
    }

    private fun bringReceiverToFront() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiLaunchAttemptAtMs < UI_LAUNCH_THROTTLE_MS) {
            return
        }
        lastUiLaunchAttemptAtMs = now
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        try {
            PendingIntent.getActivity(
                appContext,
                VIDEO_ACTIVITY_REQUEST_CODE,
                intent,
                pendingIntentFlags()
            ).send()
        } catch (e: Throwable) {
            Log.w(TAG, "could not bring receiver UI to front for video stream", e)
        }
    }

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: run {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.createMulticastLock("${appContext.packageName}:receiver")
                .apply { setReferenceCounted(false) }
                .also { multicastLock = it }
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
    }

    private fun transitionTo(newState: ReceiverState, reason: String) {
        val old = state
        if (old == newState) return
        if (!isValidTransition(old, newState)) {
            Log.w(TAG, "invalid state transition ignored: $old -> $newState ($reason)")
            return
        }
        state = newState
        Log.d(TAG, "state: $old -> $newState ($reason)")
        stateListeners.forEach { it(newState) }
    }

    private fun isValidTransition(old: ReceiverState, new: ReceiverState): Boolean {
        if (new == ReceiverState.STOPPED || new == ReceiverState.ERROR_RECOVERABLE) return true
        return when (old) {
            ReceiverState.STOPPED -> new == ReceiverState.STARTING
            ReceiverState.STARTING -> new in setOf(
                ReceiverState.IDLE_ADVERTISING,
                ReceiverState.AUDIO_ACTIVE,
                ReceiverState.VIDEO_REQUESTED,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.VIDEO_STARTING
            )
            ReceiverState.IDLE_ADVERTISING -> new in setOf(
                ReceiverState.AUDIO_ACTIVE,
                ReceiverState.VIDEO_REQUESTED,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.VIDEO_STARTING,
                ReceiverState.STOPPING_SESSION
            )
            ReceiverState.AUDIO_ACTIVE -> new in setOf(
                ReceiverState.IDLE_ADVERTISING,
                ReceiverState.VIDEO_REQUESTED,
                ReceiverState.STOPPING_SESSION
            )
            ReceiverState.VIDEO_REQUESTED -> new in setOf(
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.VIDEO_STARTING,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.WAITING_FOR_SURFACE -> new in setOf(
                ReceiverState.VIDEO_STARTING,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.VIDEO_STARTING -> new in setOf(
                ReceiverState.VIDEO_ACTIVE,
                ReceiverState.VIDEO_STALLED,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.VIDEO_ACTIVE -> new in setOf(
                ReceiverState.VIDEO_STALLED,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.VIDEO_STALLED -> new in setOf(
                ReceiverState.VIDEO_STARTING,
                ReceiverState.VIDEO_ACTIVE,
                ReceiverState.WAITING_FOR_SURFACE,
                ReceiverState.STOPPING_SESSION,
                ReceiverState.IDLE_ADVERTISING
            )
            ReceiverState.STOPPING_SESSION -> new == ReceiverState.IDLE_ADVERTISING
            ReceiverState.ERROR_RECOVERABLE -> new in setOf(
                ReceiverState.STARTING,
                ReceiverState.IDLE_ADVERTISING
            )
        }
    }

    companion object {
        private const val TAG = "Receiver-Runtime"
        private const val UI_LAUNCH_THROTTLE_MS = 5_000L
        private const val VIDEO_ACTIVITY_REQUEST_CODE = 2001
    }
}
