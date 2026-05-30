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
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private var runtime: ReceiverRuntime? = null
    private var isBound = false
    private var isSurfaceAvailable = false
    private lateinit var playbackSurface: SurfaceView
    private lateinit var startupPanel: View
    private lateinit var startupVersionLabel: TextView
    private lateinit var statusView: TextView
    private lateinit var discoveryStatusView: TextView
    private lateinit var videoModeGroup: RadioGroup
    private lateinit var wakeModeGroup: RadioGroup
    private lateinit var audioVolumeLabel: TextView
    private lateinit var audioVolumeBar: ProgressBar
    private lateinit var audioVolumeOverlay: View
    private lateinit var audioVolumeOverlayLabel: TextView
    private lateinit var audioVolumeOverlayBar: ProgressBar
    private lateinit var overlayPermissionExplanation: View
    private lateinit var overlayPermissionButton: Button
    private lateinit var startOnBootToggle: CheckBox
    private lateinit var trafficMonitor: TrafficMonitorView
    private lateinit var audioManager: AudioManager
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wakeNudgeLock: PowerManager.WakeLock? = null
    private var videoMode = VideoMode.HD
    private var wakeMode = WakeMode.WAKE_ON_ACTIVITY
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
        runOnUiThread {
            handleReceiverState(state)
        }
    }

    private val discoveryStatusListener: (String) -> Unit = { status ->
        runOnUiThread {
            discoveryStatus = status
            if (!isStreaming && ::discoveryStatusView.isInitialized) {
                discoveryStatusView.text = status
            }
        }
    }

    private val streamStatusListener: (String) -> Unit = { status ->
        runOnUiThread {
            showStreamStatus(status)
        }
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
        runOnUiThread {
            handleTrafficSample(byteCount)
        }
    }

    private val latencyListener: (Long) -> Unit = { latencyMs ->
        runOnUiThread {
            handleLatencySample(latencyMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configurePlaybackWindow()

        playbackSurface = findViewById(R.id.surface)
        startupPanel = findViewById(R.id.startup_panel)
        startupVersionLabel = findViewById(R.id.startup_version_label)
        statusView = findViewById(R.id.status)
        discoveryStatusView = findViewById(R.id.discovery_status)
        videoModeGroup = findViewById(R.id.video_mode_group)
        wakeModeGroup = findViewById(R.id.wake_mode_group)
        audioVolumeLabel = findViewById(R.id.audio_volume_label)
        audioVolumeBar = findViewById(R.id.audio_volume_bar)
        audioVolumeOverlay = findViewById(R.id.audio_volume_overlay)
        audioVolumeOverlayLabel = findViewById(R.id.audio_volume_overlay_label)
        audioVolumeOverlayBar = findViewById(R.id.audio_volume_overlay_bar)
        overlayPermissionExplanation = findViewById(R.id.overlay_permission_explanation)
        overlayPermissionButton = findViewById(R.id.overlay_permission_button)
        startOnBootToggle = findViewById(R.id.start_on_boot_toggle)
        trafficMonitor = findViewById(R.id.traffic_monitor)
        trafficMonitor.setOnClickListener { trafficMonitor.visibility = View.GONE }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        configurePlaybackSurface(playbackSurface)
        configureControlLayer()

        videoMode = loadVideoMode()
        audioVolume = loadAudioVolume()
        wakeMode = loadWakeMode()
        keepSurfaceProportional(playbackSurface)
        configureVideoModeControl()
        configureWakeModeControl()
        configureAudioControls()
        configureStartOnBootControl()
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
        if (::audioManager.isInitialized) {
            audioVolume = loadAudioVolume()
            updateAudioVolumeUi()
        }
        runtime?.let {
            syncRuntimeSettings(it)
            if (it.state != ReceiverState.STOPPED) {
                applyWakeMode()
                it.refreshDiscovery()
            }
        }
        checkOverlayPermission()
        if (::startupPanel.isInitialized && !isStreaming) {
            updateWaitingStatus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::startupPanel.isInitialized) {
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

    private fun registerRuntimeListeners(boundRuntime: ReceiverRuntime) {
        boundRuntime.addStateListener(stateListener)
        boundRuntime.addDiscoveryStatusListener(discoveryStatusListener)
        boundRuntime.addStreamStatusListener(streamStatusListener)
        boundRuntime.addVideoActivityListener(videoActivityListener)
        boundRuntime.addVideoSizeListener(videoSizeListener)
        boundRuntime.addTrafficListener(trafficListener)
        boundRuntime.addLatencyListener(latencyListener)
    }

    private fun unregisterRuntimeListeners(boundRuntime: ReceiverRuntime) {
        boundRuntime.removeStateListener(stateListener)
        boundRuntime.removeDiscoveryStatusListener(discoveryStatusListener)
        boundRuntime.removeStreamStatusListener(streamStatusListener)
        boundRuntime.removeVideoActivityListener(videoActivityListener)
        boundRuntime.removeVideoSizeListener(videoSizeListener)
        boundRuntime.removeTrafficListener(trafficListener)
        boundRuntime.removeLatencyListener(latencyListener)
    }

    private fun syncRuntimeSettings(boundRuntime: ReceiverRuntime) {
        boundRuntime.setVideoMode(videoMode.width, videoMode.height)
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
        startupPanel.elevation = elevation
        startupVersionLabel.elevation = elevation
        audioVolumeOverlay.elevation = elevation
        trafficMonitor.elevation = elevation
    }

    private fun applyWakeMode() {
        when (wakeMode) {
            WakeMode.DEFAULT -> {
                allowScreenSaver()
                releaseWakeNudgeLock()
            }
            WakeMode.ALWAYS_AWAKE -> {
                releaseWakeNudgeLock()
                keepReceiverAwake()
            }
            WakeMode.WAKE_ON_ACTIVITY -> {
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

        val sourceWidth = if (streamVideoWidth > 0) streamVideoWidth else videoMode.width
        val sourceHeight = if (streamVideoHeight > 0) streamVideoHeight else videoMode.height
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
        updateWaitingStatus()
        if (startupVersionLabel.visibility != View.VISIBLE) {
            startupVersionLabel.visibility = View.VISIBLE
        }
        if (startupPanel.visibility != View.VISIBLE) {
            startupPanel.visibility = View.VISIBLE
        }
        if (audioVolumeOverlay.visibility != View.GONE) {
            audioVolumeOverlay.visibility = View.GONE
        }
        showControl(startupVersionLabel)
        showControl(startupPanel)
    }

    private fun showAudioOnlyStatus() {
        isStreaming = false
        streamStatus = getString(R.string.status_audio_only)
        updateWaitingStatus()
        startupVersionLabel.visibility = View.VISIBLE
        startupPanel.visibility = View.VISIBLE
        audioVolumeOverlay.visibility = View.GONE
        showControl(startupVersionLabel)
        showControl(startupPanel)
    }

    private fun updateWaitingStatus() {
        startupVersionLabel.text = "v${BuildConfig.VERSION_NAME}"
        val status = if (streamStatus == getString(R.string.status_audio_only)) {
            getString(R.string.status_audio_only)
        } else {
            "${getString(R.string.status_waiting)} in ${videoMode.label} mode\n$streamStatus"
        }
        statusView.text = "$receiverDeviceName\n$status"
        discoveryStatusView.text = discoveryStatus
    }

    private fun hideStatus() {
        runOnUiThread {
            isStreaming = true
            streamStatus = getString(R.string.status_streaming)
            startupPanel.visibility = View.GONE
            startupVersionLabel.visibility = View.GONE
        }
    }

    private fun showStreamStatus(status: String) {
        streamStatus = status
        if (::statusView.isInitialized && !isStreaming) {
            updateWaitingStatus()
        }
    }

    private fun configureVideoModeControl() {
        videoModeGroup.check(videoMode.radioButtonId)
        videoModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = VideoMode.fromRadioButtonId(checkedId) ?: return@setOnCheckedChangeListener
            if (selectedMode == videoMode) {
                return@setOnCheckedChangeListener
            }
            videoMode = selectedMode
            saveVideoMode(selectedMode)
            runtime?.setVideoMode(selectedMode.width, selectedMode.height)
            updateSurfaceLayout(playbackSurface)
            if (!isStreaming) {
                updateWaitingStatus()
            }
        }
    }

    private fun configureWakeModeControl() {
        wakeModeGroup.check(wakeMode.radioButtonId)
        wakeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = WakeMode.fromRadioButtonId(checkedId) ?: return@setOnCheckedChangeListener
            if (selectedMode == wakeMode) {
                return@setOnCheckedChangeListener
            }
            wakeMode = selectedMode
            saveWakeMode(selectedMode)
            applyWakeMode()
        }
    }

    private fun configureAudioControls() {
        updateAudioVolumeUi()
    }

    private fun configureStartOnBootControl() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        startOnBootToggle.isChecked = preferences.getBoolean(PREFERENCE_START_ON_BOOT, true)
        startOnBootToggle.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(PREFERENCE_START_ON_BOOT, isChecked).apply()
        }
    }

    private fun checkOverlayPermission() {
        if (!::overlayPermissionExplanation.isInitialized) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            overlayPermissionExplanation.visibility = View.GONE
            return
        }
        showOverlayPermissionExplanation()
    }

    private fun showOverlayPermissionExplanation() {
        overlayPermissionExplanation.visibility = View.VISIBLE
        overlayPermissionButton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun loadWakeMode(): WakeMode {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return WakeMode.fromPreferenceValue(preferences.getString(PREFERENCE_WAKE_MODE, null))
            ?: WakeMode.WAKE_ON_ACTIVITY
    }

    private fun loadVideoMode(): VideoMode {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return VideoMode.fromPreferenceValue(preferences.getString(PREFERENCE_VIDEO_MODE_V2, null))
            ?: VideoMode.HD
    }

    private fun saveVideoMode(mode: VideoMode) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_VIDEO_MODE_V2, mode.preferenceValue)
            .apply()
    }

    private fun saveWakeMode(mode: WakeMode) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_WAKE_MODE, mode.preferenceValue)
            .apply()
    }

    private fun loadAudioVolume(): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return DEFAULT_AUDIO_VOLUME
        }
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
            .coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    }

    private fun saveAudioVolume(volume: Float) {
        audioVolume = applySystemAudioVolume(volume)
    }

    private fun setAudioVolume(volume: Float, persist: Boolean) {
        audioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        audioVolume = applySystemAudioVolume(audioVolume)
        if (persist) {
            saveAudioVolume(audioVolume)
        }
        runtime?.setAudioVolume(audioVolume)
        updateAudioVolumeUi()
        if (isStreaming) {
            showAudioVolumeOverlay()
        }
    }

    private fun applySystemAudioVolume(volume: Float): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        }
        val volumeIndex = (volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME) * maxVolume)
            .roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeIndex, 0)
        return volumeIndex.toFloat() / maxVolume
    }

    private fun updateAudioVolumeUi() {
        if (::audioManager.isInitialized) {
            audioVolume = loadAudioVolume()
        }
        val progress = (audioVolume * 100).toInt()
        val label = "Volume $progress%"
        audioVolumeLabel.text = label
        audioVolumeBar.progress = progress
        audioVolumeBar.isEnabled = true
        audioVolumeOverlayLabel.text = label
        audioVolumeOverlayBar.progress = progress
    }

    private fun showAudioVolumeOverlay() {
        audioVolumeOverlay.visibility = View.VISIBLE
        showControl(audioVolumeOverlay)
        audioVolumeOverlay.removeCallbacks(hideAudioVolumeOverlay)
        audioVolumeOverlay.postDelayed(hideAudioVolumeOverlay, VOLUME_OVERLAY_DURATION_MS)
    }

    private val hideAudioVolumeOverlay = Runnable {
        audioVolumeOverlay.visibility = View.GONE
    }

    private fun handleVideoActivity(hasVideoActivity: Boolean) {
        if (wakeMode != WakeMode.WAKE_ON_ACTIVITY || !hasVideoActivity) {
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
                if (wakeMode == WakeMode.WAKE_ON_ACTIVITY) {
                    nudgeDisplayAwake()
                }
            } finally {
                wakeNudgePending = false
            }
        }
    }

    private fun handleTrafficSample(byteCount: Int) {
        trafficMonitor.recordTraffic(byteCount)
    }

    private fun handleLatencySample(latencyMs: Long) {
        trafficMonitor.recordLatency(latencyMs)
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
                    saveAudioVolume(audioVolume)
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

    private enum class WakeMode(val preferenceValue: String, val radioButtonId: Int) {
        DEFAULT("default", R.id.wake_mode_default),
        ALWAYS_AWAKE("always_awake", R.id.wake_mode_always),
        WAKE_ON_ACTIVITY("wake_on_activity", R.id.wake_mode_activity);

        companion object {
            fun fromPreferenceValue(value: String?): WakeMode? = values().firstOrNull { it.preferenceValue == value }
            fun fromRadioButtonId(id: Int): WakeMode? = values().firstOrNull { it.radioButtonId == id }
        }
    }

    private enum class VideoMode(
        val preferenceValue: String,
        val radioButtonId: Int,
        val label: String,
        val width: Int,
        val height: Int
    ) {
        HD("720p", R.id.video_mode_720p, "720p", 1280, 720),
        FULL_HD("1080p", R.id.video_mode_1080p, "1080p", 1920, 1080);

        companion object {
            fun fromPreferenceValue(value: String?): VideoMode? = values().firstOrNull { it.preferenceValue == value }
            fun fromRadioButtonId(id: Int): VideoMode? = values().firstOrNull { it.radioButtonId == id }
        }
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
        private const val MIN_AUDIO_VOLUME = 0.0f
        private const val MAX_AUDIO_VOLUME = 1.0f
        private const val DEFAULT_AUDIO_VOLUME = 1.0f
        private const val PREFERENCES_NAME = "receiver"
        private const val PREFERENCE_VIDEO_MODE_V2 = "video_mode_v2"
        private const val PREFERENCE_WAKE_MODE = "wake_mode"
        private const val PREFERENCE_START_ON_BOOT = "start_on_boot"
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
