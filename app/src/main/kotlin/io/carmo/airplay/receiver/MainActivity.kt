package io.carmo.airplay.receiver

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private var runtime: ReceiverRuntime? = null
    private var isBound = false
    private var isSurfaceAvailable = false

    private lateinit var playbackSurface: SurfaceView
    private lateinit var waitingOverlay: View
    private lateinit var deviceNameLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var discoveryStatusView: TextView
    private lateinit var versionLabel: TextView
    private lateinit var audioOnlyOverlay: View
    private lateinit var audioVolumeLabel: TextView
    private lateinit var audioVolumeOverlay: View
    private lateinit var audioVolumeOverlayLabel: TextView
    private lateinit var audioVolumeOverlayBar: ProgressBar
    private lateinit var permissionExplanation: View
    private lateinit var permissionButton: Button
    private lateinit var streamInfoOverlay: View
    private lateinit var streamInfoOverlayText: TextView
    private lateinit var streamInfoDiagnosticsButton: Button
    private lateinit var trafficMonitor: TrafficMonitorView
    private lateinit var audioManager: AudioManager

    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wakeNudgeLock: PowerManager.WakeLock? = null
    private var videoSize: ReceiverPreferences.VideoSize? = null
    private var wakeMode = ReceiverPreferences.WAKE_MODE_ACTIVITY
    private var audioVolume = DEFAULT_AUDIO_VOLUME
    @Volatile private var lastWakeNudgeAtMs = 0L
    @Volatile private var wakeNudgePending = false
    private var volumeGestureStartY = 0f
    private var volumeGestureStartLevel = DEFAULT_AUDIO_VOLUME
    private var isVolumeGestureCandidate = false
    private var isAdjustingVolume = false
    private var trafficGestureStartX = 0f
    private var trafficGestureStartY = 0f
    private var isTrafficGestureCandidate = false
    private var isStreaming = false
    private var streamVideoWidth = 0
    private var streamVideoHeight = 0
    private var receiverDeviceName = "Receiver"
    private var discoveryStatus = "Discovery starting"
    private var streamStatus = "Waiting"

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ReceiverForegroundService.ReceiverBinder
            val boundRuntime = binder.runtime
            runtime = boundRuntime
            isBound = true
            registerRuntimeListeners(boundRuntime)
            receiverDeviceName = boundRuntime.deviceDisplayName
            discoveryStatus = boundRuntime.discoveryStatus
            streamStatus = boundRuntime.streamStatus
            boundRuntime.dismissVideoStartedNotification()
            syncRuntimeSettings(boundRuntime)
            attachSurfaceIfReady(boundRuntime)
            handleReceiverState(boundRuntime.state)
            checkOverlayPermission()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            runtime?.let(::unregisterRuntimeListeners)
            runtime = null
            isBound = false
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            isSurfaceAvailable = true
            runtime?.attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (holder.surface.isValid) {
                isSurfaceAvailable = true
                runtime?.attachSurface(holder.surface)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            isSurfaceAvailable = false
            runtime?.detachSurface()
        }
    }

    private val stateListener: (ReceiverState) -> Unit = { state ->
        runOnUiThread { handleReceiverState(state) }
    }

    private val discoveryStatusListener: (String) -> Unit = { status ->
        runOnUiThread {
            discoveryStatus = status
            if (!isStreaming && ::discoveryStatusView.isInitialized) {
                updateWaitingStatus()
            }
        }
    }

    private val streamStatusListener: (String) -> Unit = { status ->
        runOnUiThread { showStreamStatus(status) }
    }

    private val videoActivityListener: (Boolean) -> Unit = { hasVideoActivity ->
        handleVideoActivity(hasVideoActivity)
    }

    private val videoSizeListener: (Int, Int) -> Unit = { width, height ->
        runOnUiThread {
            streamVideoWidth = width
            streamVideoHeight = height
            updateSurfaceLayout(playbackSurface)
        }
    }

    private val trafficListener: (Int) -> Unit = { byteCount ->
        runOnUiThread { trafficMonitor.recordTraffic(byteCount) }
    }

    private val latencyListener: (Long) -> Unit = { latencyMs ->
        runOnUiThread { trafficMonitor.recordLatency(latencyMs) }
    }

    private val afterDisconnectListener: () -> Unit = {
        runOnUiThread { moveTaskToBack(true) }
    }

    private val hideAudioVolumeOverlay = Runnable {
        audioVolumeOverlay.visibility = View.GONE
    }

    private val hideStreamInfoOverlay = Runnable {
        streamInfoOverlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configurePlaybackWindow()

        playbackSurface = findViewById(R.id.surface)
        waitingOverlay = findViewById(R.id.waiting_overlay)
        deviceNameLabel = findViewById(R.id.device_name_label)
        statusLabel = findViewById(R.id.status_label)
        discoveryStatusView = findViewById(R.id.discovery_status)
        versionLabel = findViewById(R.id.version_label)
        audioOnlyOverlay = findViewById(R.id.audio_only_overlay)
        audioVolumeLabel = findViewById(R.id.audio_volume_label)
        audioVolumeOverlay = findViewById(R.id.audio_volume_overlay)
        audioVolumeOverlayLabel = findViewById(R.id.audio_volume_overlay_label)
        audioVolumeOverlayBar = findViewById(R.id.audio_volume_overlay_bar)
        permissionExplanation = findViewById(R.id.permission_explanation)
        permissionButton = findViewById(R.id.permission_button)
        streamInfoOverlay = findViewById(R.id.stream_info_overlay)
        streamInfoOverlayText = findViewById(R.id.stream_info_text)
        streamInfoDiagnosticsButton = findViewById(R.id.stream_info_diagnostics_button)
        trafficMonitor = findViewById(R.id.traffic_monitor)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        configurePlaybackSurface(playbackSurface)
        configureControlLayer()
        configureRemoteActions()

        videoSize = ReceiverPreferences.selectedVideoSize(this)
        audioVolume = loadAudioVolume()
        wakeMode = ReceiverPreferences.wakeMode(this)
        keepSurfaceProportional(playbackSurface)
        updateAudioVolumeUi()
        checkOverlayPermission()
        updateWaitingStatus()

        startReceiverForegroundService()
        bindReceiverForegroundService()

        if (DEBUG_CODECS) {
            logSupportedCodecs()
        }
    }

    override fun onResume() {
        super.onResume()
        videoSize = ReceiverPreferences.selectedVideoSize(this)
        wakeMode = ReceiverPreferences.wakeMode(this)
        audioVolume = loadAudioVolume()
        updateAudioVolumeUi()
        runtime?.let {
            receiverDeviceName = it.deviceDisplayName
            it.dismissVideoStartedNotification()
            syncRuntimeSettings(it)
            if (it.state != ReceiverState.STOPPED) {
                applyWakeMode()
                it.refreshDiscovery()
            }
        }
        checkOverlayPermission()
        if (::waitingOverlay.isInitialized && !isStreaming) {
            updateWaitingStatus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::waitingOverlay.isInitialized) {
            ensureImmersiveFlags()
            if (runtime?.state != ReceiverState.STOPPED) {
                applyWakeMode()
            }
        }
    }

    override fun onDestroy() {
        runtime?.let { boundRuntime ->
            boundRuntime.detachSurface()
            unregisterRuntimeListeners(boundRuntime)
        }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        allowScreenSaver()
        releaseWakeNudgeLock()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        handleTrafficMonitorGesture(event)
        if (handleVolumeGesture(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                adjustVolume(+1)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                adjustVolume(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (!isStreaming) {
                    openSettings()
                } else {
                    showStreamInfoOverlay()
                }
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                toggleTrafficMonitor()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                moveTaskToBack(true)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun registerRuntimeListeners(boundRuntime: ReceiverRuntime) {
        boundRuntime.addStateListener(stateListener)
        boundRuntime.addDiscoveryStatusListener(discoveryStatusListener)
        boundRuntime.addStreamStatusListener(streamStatusListener)
        boundRuntime.addVideoActivityListener(videoActivityListener)
        boundRuntime.addVideoSizeListener(videoSizeListener)
        boundRuntime.addTrafficListener(trafficListener)
        boundRuntime.addLatencyListener(latencyListener)
        boundRuntime.addAfterDisconnectListener(afterDisconnectListener)
    }

    private fun unregisterRuntimeListeners(boundRuntime: ReceiverRuntime) {
        boundRuntime.removeStateListener(stateListener)
        boundRuntime.removeDiscoveryStatusListener(discoveryStatusListener)
        boundRuntime.removeStreamStatusListener(streamStatusListener)
        boundRuntime.removeVideoActivityListener(videoActivityListener)
        boundRuntime.removeVideoSizeListener(videoSizeListener)
        boundRuntime.removeTrafficListener(trafficListener)
        boundRuntime.removeLatencyListener(latencyListener)
        boundRuntime.removeAfterDisconnectListener(afterDisconnectListener)
    }

    private fun syncRuntimeSettings(boundRuntime: ReceiverRuntime) {
        val selectedSize = ReceiverPreferences.selectedVideoSize(this)
        videoSize = selectedSize
        boundRuntime.setVideoMode(selectedSize.width, selectedSize.height)
        boundRuntime.setAudioVolume(audioVolume)
    }

    private fun attachSurfaceIfReady(boundRuntime: ReceiverRuntime) {
        val surface = playbackSurface.holder.surface
        if (surface != null && surface.isValid) {
            isSurfaceAvailable = true
            boundRuntime.attachSurface(surface)
        }
    }

    private fun configurePlaybackWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        ensureImmersiveFlags()
    }

    @Suppress("DEPRECATION")
    private fun ensureImmersiveFlags() {
        val decor = window.decorView
        if (decor.systemUiVisibility != IMMERSIVE_FLAGS) {
            decor.systemUiVisibility = IMMERSIVE_FLAGS
        }
    }

    private fun configurePlaybackSurface(surfaceView: SurfaceView) {
        surfaceView.setZOrderOnTop(false)
        surfaceView.setZOrderMediaOverlay(false)
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)
        surfaceView.holder.addCallback(surfaceCallback)
    }

    private fun configureControlLayer() {
        val elevation = CONTROL_OVERLAY_ELEVATION_DP * resources.displayMetrics.density
        waitingOverlay.elevation = elevation
        versionLabel.elevation = elevation
        audioOnlyOverlay.elevation = elevation
        audioVolumeOverlay.elevation = elevation
        streamInfoOverlay.elevation = elevation
        permissionExplanation.elevation = elevation
        trafficMonitor.elevation = elevation
    }

    private fun configureRemoteActions() {
        waitingOverlay.setOnClickListener { openSettings() }
        waitingOverlay.post { waitingOverlay.requestFocus() }
        permissionButton.setOnClickListener { openOverlayPermissionSettings() }
        streamInfoDiagnosticsButton.setOnClickListener {
            toggleTrafficMonitor()
            streamInfoOverlay.removeCallbacks(hideStreamInfoOverlay)
        }
        trafficMonitor.setOnClickListener { trafficMonitor.visibility = View.GONE }
    }

    private fun applyWakeMode() {
        when (wakeMode) {
            ReceiverPreferences.WAKE_MODE_DEFAULT -> {
                allowScreenSaver()
                releaseWakeNudgeLock()
            }
            ReceiverPreferences.WAKE_MODE_ALWAYS -> {
                releaseWakeNudgeLock()
                keepReceiverAwake()
            }
            else -> {
                setKeepScreenOn(false)
                releaseWakeLock()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun keepReceiverAwake() {
        setKeepScreenOn(true)
        val wakeLock = screenWakeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "$packageName:$WAKE_LOCK_TAG"
            ).apply {
                setReferenceCounted(false)
                screenWakeLock = this
            }
        }

        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
    }

    private fun allowScreenSaver() {
        setKeepScreenOn(false)
        releaseWakeLock()
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.decorView.keepScreenOn = enabled
        if (::playbackSurface.isInitialized) {
            playbackSurface.keepScreenOn = enabled
        }
    }

    private fun releaseWakeLock() {
        val wakeLock = screenWakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
    }

    private fun releaseWakeNudgeLock() {
        val wakeLock = wakeNudgeLock
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun nudgeDisplayAwake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeNudgeAtMs < WAKE_NUDGE_THROTTLE_MS) {
            return
        }
        lastWakeNudgeAtMs = now
        if (!hasWindowFocus()) {
            bringReceiverToFront()
        }

        val wakeLock = wakeNudgeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$packageName:$WAKE_NUDGE_LOCK_TAG"
            ).apply {
                setReferenceCounted(false)
                wakeNudgeLock = this
            }
        }
        wakeLock.acquire(WAKE_NUDGE_DURATION_MS)
    }

    private fun bringReceiverToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    private fun keepSurfaceProportional(surfaceView: SurfaceView) {
        fun updateSurfaceLayout() {
            updateSurfaceLayout(surfaceView)
        }

        surfaceView.post {
            val parent = surfaceView.parent as? View ?: return@post
            parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceLayout() }
            updateSurfaceLayout()
        }
    }

    private fun updateSurfaceLayout(surfaceView: SurfaceView) {
        val parent = surfaceView.parent as? View ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth == 0 || parentHeight == 0) {
            return
        }

        val selectedSize = videoSize ?: ReceiverPreferences.selectedVideoSize(this)
        val sourceWidth = if (streamVideoWidth > 0) streamVideoWidth else selectedSize.width
        val sourceHeight = if (streamVideoHeight > 0) streamVideoHeight else selectedSize.height
        var width = parentWidth
        var height = width * sourceHeight / sourceWidth
        if (height > parentHeight) {
            height = parentHeight
            width = height * sourceWidth / sourceHeight
        }

        val currentParams = surfaceView.layoutParams
        if (currentParams.width != width || currentParams.height != height) {
            surfaceView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    private fun handleReceiverState(state: ReceiverState) {
        when (state) {
            ReceiverState.IDLE_ADVERTISING -> {
                showWaitingStatus()
                applyWakeMode()
            }
            ReceiverState.VIDEO_ACTIVE -> hideStatus()
            ReceiverState.AUDIO_ACTIVE -> showAudioOnlyStatus()
            ReceiverState.VIDEO_STALLED -> showStreamStatus(getString(R.string.status_recovering))
            ReceiverState.STARTING,
            ReceiverState.STOPPED,
            ReceiverState.ERROR_RECOVERABLE -> showWaitingStatus()
            else -> Unit
        }
    }

    private fun showWaitingStatus() {
        isStreaming = false
        streamVideoWidth = 0
        streamVideoHeight = 0
        if (streamStatus.isBlank() || streamStatus == getString(R.string.status_streaming)) {
            streamStatus = "Waiting"
        }
        updateSurfaceLayout(playbackSurface)
        audioOnlyOverlay.visibility = View.GONE
        audioVolumeOverlay.visibility = View.GONE
        streamInfoOverlay.visibility = View.GONE
        waitingOverlay.visibility = View.VISIBLE
        versionLabel.visibility = View.VISIBLE
        updateWaitingStatus()
        checkOverlayPermission()
        showControl(waitingOverlay)
        waitingOverlay.requestFocus()
    }

    private fun showAudioOnlyStatus() {
        isStreaming = false
        streamStatus = getString(R.string.status_audio_only)
        updateWaitingStatus()
        waitingOverlay.visibility = View.VISIBLE
        versionLabel.visibility = View.VISIBLE
        audioOnlyOverlay.visibility = View.VISIBLE
        audioVolumeOverlay.visibility = View.GONE
        checkOverlayPermission()
        showControl(waitingOverlay)
        showControl(audioOnlyOverlay)
    }

    private fun updateWaitingStatus() {
        versionLabel.text = "v${BuildConfig.VERSION_NAME}"
        receiverDeviceName = runtime?.deviceDisplayName ?: receiverDeviceName
        deviceNameLabel.text = "Available as $receiverDeviceName"
        val network = runtime?.getLocalIpAddress() ?: "unknown"
        val resolution = ReceiverPreferences.videoModeSummary(this)
        statusLabel.text = if (streamStatus == getString(R.string.status_audio_only)) {
            "${getString(R.string.status_audio_playing)}\nNetwork: $network"
        } else {
            "${getString(R.string.status_waiting)}\nResolution: $resolution\nNetwork: $network\n$streamStatus"
        }
        discoveryStatusView.text = discoveryStatus
        updateAudioVolumeUi()
    }

    private fun hideStatus() {
        runOnUiThread {
            isStreaming = true
            streamStatus = getString(R.string.status_streaming)
            waitingOverlay.visibility = View.GONE
            audioOnlyOverlay.visibility = View.GONE
            permissionExplanation.visibility = View.GONE
        }
    }

    private fun showStreamStatus(status: String) {
        streamStatus = status
        if (::statusLabel.isInitialized && !isStreaming) {
            updateWaitingStatus()
        }
    }

    private fun checkOverlayPermission() {
        if (!::permissionExplanation.isInitialized) return
        val shouldShow = !isStreaming &&
            ReceiverPreferences.automaticVideoTakeover(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        permissionExplanation.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun loadAudioVolume(): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return DEFAULT_AUDIO_VOLUME
        }
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
            .coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    }

    private fun setAudioVolume(volume: Float, persist: Boolean) {
        audioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        audioVolume = applySystemAudioVolume(audioVolume, showUi = false)
        if (persist) {
            audioVolume = loadAudioVolume()
        }
        runtime?.setAudioVolume(audioVolume)
        updateAudioVolumeUi()
        if (isStreaming || audioOnlyOverlay.visibility == View.VISIBLE) {
            showAudioVolumeOverlay()
        }
    }

    private fun applySystemAudioVolume(volume: Float, showUi: Boolean): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        }
        val volumeIndex = (volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME) * maxVolume)
            .roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volumeIndex,
            if (showUi) AudioManager.FLAG_SHOW_UI else 0
        )
        return volumeIndex.toFloat() / maxVolume
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        audioVolume = loadAudioVolume()
        runtime?.setAudioVolume(audioVolume)
        updateAudioVolumeUi()
        if (isStreaming || audioOnlyOverlay.visibility == View.VISIBLE) {
            showAudioVolumeOverlay()
        }
    }

    private fun updateAudioVolumeUi() {
        if (::audioManager.isInitialized) {
            audioVolume = loadAudioVolume()
        }
        val progress = (audioVolume * 100).toInt()
        val label = "Volume $progress%"
        if (::audioVolumeLabel.isInitialized) {
            audioVolumeLabel.text = label
        }
        if (::audioVolumeOverlayLabel.isInitialized) {
            audioVolumeOverlayLabel.text = label
            audioVolumeOverlayBar.progress = progress
        }
    }

    private fun showAudioVolumeOverlay() {
        audioVolumeOverlay.visibility = View.VISIBLE
        showControl(audioVolumeOverlay)
        audioVolumeOverlay.removeCallbacks(hideAudioVolumeOverlay)
        audioVolumeOverlay.postDelayed(hideAudioVolumeOverlay, VOLUME_OVERLAY_DURATION_MS)
    }

    private fun showStreamInfoOverlay() {
        val boundRuntime = runtime ?: return
        val selectedSize = videoSize ?: ReceiverPreferences.selectedVideoSize(this)
        val resolution = if (streamVideoWidth > 0 && streamVideoHeight > 0) {
            "${streamVideoWidth}x${streamVideoHeight}"
        } else {
            "${selectedSize.width}x${selectedSize.height}"
        }
        val info = buildString {
            appendLine(boundRuntime.deviceDisplayName)
            appendLine(resolution)
            if (audioOnlyOverlay.visibility == View.VISIBLE || boundRuntime.state == ReceiverState.AUDIO_ACTIVE) {
                appendLine("Audio active")
            }
            append("Network: ${boundRuntime.getLocalIpAddress()}")
        }
        streamInfoOverlayText.text = info
        streamInfoOverlay.visibility = View.VISIBLE
        showControl(streamInfoOverlay)
        streamInfoDiagnosticsButton.requestFocus()
        streamInfoOverlay.removeCallbacks(hideStreamInfoOverlay)
        streamInfoOverlay.postDelayed(hideStreamInfoOverlay, STREAM_INFO_DURATION_MS)
    }

    private fun toggleTrafficMonitor() {
        if (!::trafficMonitor.isInitialized) return
        trafficMonitor.visibility = if (trafficMonitor.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (trafficMonitor.visibility == View.VISIBLE) {
            showControl(trafficMonitor)
            trafficMonitor.requestFocus()
        }
    }

    private fun handleVideoActivity(hasVideoActivity: Boolean) {
        if (wakeMode != ReceiverPreferences.WAKE_MODE_ACTIVITY || !hasVideoActivity) {
            return
        }
        if (SystemClock.elapsedRealtime() - lastWakeNudgeAtMs < WAKE_NUDGE_THROTTLE_MS) {
            return
        }
        if (wakeNudgePending) {
            return
        }
        wakeNudgePending = true
        runOnUiThread {
            try {
                if (wakeMode == ReceiverPreferences.WAKE_MODE_ACTIVITY) {
                    nudgeDisplayAwake()
                }
            } finally {
                wakeNudgePending = false
            }
        }
    }

    private fun handleTrafficMonitorGesture(event: MotionEvent) {
        if (!isStreaming) {
            isTrafficGestureCandidate = false
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trafficGestureStartX = event.x
                trafficGestureStartY = event.y
                val edgeSize = TRAFFIC_GESTURE_EDGE_DP * resources.displayMetrics.density
                isTrafficGestureCandidate = event.x >= window.decorView.width - edgeSize &&
                    event.y <= edgeSize
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTrafficGestureCandidate || trafficMonitor.visibility == View.VISIBLE) {
                    return
                }
                val draggedLeft = trafficGestureStartX - event.x
                val draggedDown = event.y - trafficGestureStartY
                val dragThreshold = TRAFFIC_GESTURE_DRAG_DP * resources.displayMetrics.density
                if (draggedLeft >= dragThreshold || draggedDown >= dragThreshold) {
                    trafficMonitor.visibility = View.VISIBLE
                    showControl(trafficMonitor)
                    isTrafficGestureCandidate = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTrafficGestureCandidate = false
            }
        }
    }

    private fun handleVolumeGesture(event: MotionEvent): Boolean {
        if (!isStreaming) {
            isVolumeGestureCandidate = false
            isAdjustingVolume = false
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                volumeGestureStartY = event.y
                volumeGestureStartLevel = audioVolume
                isAdjustingVolume = false
                val edgeSize = VOLUME_GESTURE_EDGE_DP * resources.displayMetrics.density
                isVolumeGestureCandidate = event.x >= window.decorView.width - edgeSize &&
                    event.y > edgeSize
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isVolumeGestureCandidate) {
                    return false
                }
                val verticalDrag = volumeGestureStartY - event.y
                val range = VOLUME_GESTURE_RANGE_DP * resources.displayMetrics.density
                if (!isAdjustingVolume && kotlin.math.abs(verticalDrag) < VOLUME_GESTURE_START_DP * resources.displayMetrics.density) {
                    return false
                }
                isAdjustingVolume = true
                setAudioVolume(volumeGestureStartLevel + verticalDrag / range, persist = false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isAdjustingVolume) {
                    audioVolume = loadAudioVolume()
                    runtime?.setAudioVolume(audioVolume)
                }
                val consumed = isAdjustingVolume
                isVolumeGestureCandidate = false
                isAdjustingVolume = false
                return consumed
            }
            MotionEvent.ACTION_CANCEL -> {
                isVolumeGestureCandidate = false
                isAdjustingVolume = false
            }
        }
        return false
    }

    private fun logSupportedCodecs() {
        val mediaCodecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        for (mediaCodecInfo in mediaCodecList.codecInfos) {
            if (mediaCodecInfo.isEncoder) {
                continue
            }
            Log.d(TAG, "supported codec = ${mediaCodecInfo.supportedTypes.joinToString()}")
        }
    }

    private fun showControl(control: View) {
        control.bringToFront()
        control.translationZ = CONTROL_OVERLAY_ELEVATION_DP * resources.displayMetrics.density
        control.invalidate()
    }

    private fun startReceiverForegroundService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindReceiverForegroundService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    companion object {
        private const val TAG = "Receiver"
        private const val WAKE_LOCK_TAG = "ReceiverActive"
        private const val WAKE_NUDGE_LOCK_TAG = "ReceiverActivity"
        private const val WAKE_NUDGE_DURATION_MS = 10_000L
        private const val WAKE_NUDGE_THROTTLE_MS = 5_000L
        private const val CONTROL_OVERLAY_ELEVATION_DP = 24f
        private const val TRAFFIC_GESTURE_EDGE_DP = 96f
        private const val TRAFFIC_GESTURE_DRAG_DP = 48f
        private const val VOLUME_GESTURE_EDGE_DP = 120f
        private const val VOLUME_GESTURE_START_DP = 12f
        private const val VOLUME_GESTURE_RANGE_DP = 240f
        private const val VOLUME_OVERLAY_DURATION_MS = 1_500L
        private const val STREAM_INFO_DURATION_MS = 3_000L
        private const val MIN_AUDIO_VOLUME = 0.0f
        private const val MAX_AUDIO_VOLUME = 1.0f
        private const val DEFAULT_AUDIO_VOLUME = 1.0f
        private const val DEBUG_CODECS = false

        @Suppress("DEPRECATION")
        private val IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
