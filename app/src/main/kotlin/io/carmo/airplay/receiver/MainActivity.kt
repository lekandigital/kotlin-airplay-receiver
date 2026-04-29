package io.carmo.airplay.receiver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var airPlayServer: AirPlayServer
    private lateinit var raopServer: RaopServer
    private lateinit var dnsNotify: DNSNotify
    private lateinit var startupPanel: View
    private lateinit var statusView: TextView
    private lateinit var wakeModeGroup: RadioGroup
    private lateinit var trafficMonitor: TrafficMonitorView
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wakeNudgeLock: PowerManager.WakeLock? = null
    private var wakeMode = WakeMode.WAKE_ON_ACTIVITY
    private var lastWakeNudgeAtMs = 0L
    private var trafficGestureStartX = 0f
    private var trafficGestureStartY = 0f
    private var isTrafficGestureCandidate = false
    private var isStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configurePlaybackWindow()

        val surfaceView = findViewById<SurfaceView>(R.id.surface)
        startupPanel = findViewById(R.id.startup_panel)
        statusView = findViewById(R.id.status)
        wakeModeGroup = findViewById(R.id.wake_mode_group)
        trafficMonitor = findViewById(R.id.traffic_monitor)
        trafficMonitor.setOnClickListener { trafficMonitor.visibility = View.GONE }
        keepSurfaceProportional(surfaceView)

        airPlayServer = AirPlayServer()
        raopServer = RaopServer(
            surfaceView,
            ::hideStatus,
            ::handleVideoActivity,
            ::handleTrafficSample,
            ::handleLatencySample
        )
        dnsNotify = DNSNotify(this)
        wakeMode = loadWakeMode()
        configureWakeModeControl()
        showWaitingStatus()

        if (DEBUG_CODECS) {
            logSupportedCodecs()
        }

        startServer()
    }

    override fun onResume() {
        super.onResume()
        if (isStarted) {
            applyWakeMode()
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        handleTrafficMonitorGesture(event)
        return super.dispatchTouchEvent(event)
    }

    private fun configurePlaybackWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
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
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                releaseWakeLock()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun keepReceiverAwake() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        releaseWakeLock()
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
        bringReceiverToFront()

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
        surfaceView.holder.setFixedSize(STREAM_WIDTH, STREAM_HEIGHT)

        fun updateSurfaceLayout() {
            val parent = surfaceView.parent as? View ?: return
            val parentWidth = parent.width
            val parentHeight = parent.height
            if (parentWidth == 0 || parentHeight == 0) {
                return
            }

            var width = parentWidth
            var height = width * STREAM_HEIGHT / STREAM_WIDTH
            if (height > parentHeight) {
                height = parentHeight
                width = height * STREAM_WIDTH / STREAM_HEIGHT
            }

            val currentParams = surfaceView.layoutParams
            if (currentParams.width != width || currentParams.height != height) {
                surfaceView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }

        surfaceView.post {
            val parent = surfaceView.parent as? View ?: return@post
            parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSurfaceLayout() }
            updateSurfaceLayout()
        }
    }

    private fun showWaitingStatus() {
        statusView.text = "Announcing myself as ${dnsNotify.deviceName}\nWaiting for connection"
        startupPanel.visibility = View.VISIBLE
        startupPanel.bringToFront()
    }

    private fun hideStatus() {
        runOnUiThread {
            startupPanel.visibility = View.GONE
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

    private fun loadWakeMode(): WakeMode {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return WakeMode.fromPreferenceValue(preferences.getString(PREFERENCE_WAKE_MODE, null))
            ?: WakeMode.WAKE_ON_ACTIVITY
    }

    private fun saveWakeMode(mode: WakeMode) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_WAKE_MODE, mode.preferenceValue)
            .apply()
    }

    private fun handleVideoActivity(isMajorUpdate: Boolean) {
        if (wakeMode != WakeMode.WAKE_ON_ACTIVITY || !isMajorUpdate) {
            return
        }
        runOnUiThread {
            if (wakeMode == WakeMode.WAKE_ON_ACTIVITY) {
                nudgeDisplayAwake()
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
                    trafficMonitor.bringToFront()
                    isTrafficGestureCandidate = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTrafficGestureCandidate = false
            }
        }
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

    private fun startServer() {
        if (isStarted) {
            return
        }

        startReceiverForegroundService()
        airPlayServer.startServer()
        val airplayPort = airPlayServer.port
        if (airplayPort == 0) {
            Toast.makeText(applicationContext, "Start the AirPlay service failed", Toast.LENGTH_SHORT).show()
        } else {
            dnsNotify.registerAirplay(airplayPort)
        }

        raopServer.startServer()
        val raopPort = raopServer.port
        if (raopPort == 0) {
            Toast.makeText(applicationContext, "Start the RAOP service failed", Toast.LENGTH_SHORT).show()
        } else {
            dnsNotify.registerRaop(raopPort)
        }

        isStarted = true
        applyWakeMode()
        Log.d(TAG, "deviceName = ${dnsNotify.deviceName}, airplayPort = $airplayPort, raopPort = $raopPort")
    }

    private fun startReceiverForegroundService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopReceiverForegroundService() {
        stopService(Intent(this, ReceiverForegroundService::class.java))
    }

    private fun stopServer() {
        if (!isStarted) {
            return
        }
        dnsNotify.stop()
        airPlayServer.stopServer()
        raopServer.stopServer()
        isStarted = false
        lastWakeNudgeAtMs = 0L
        allowScreenSaver()
        releaseWakeNudgeLock()
        stopReceiverForegroundService()
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

    companion object {
        private const val TAG = "Receiver"
        private const val WAKE_LOCK_TAG = "ReceiverActive"
        private const val WAKE_NUDGE_LOCK_TAG = "ReceiverActivity"
        private const val WAKE_NUDGE_DURATION_MS = 10_000L
        private const val WAKE_NUDGE_THROTTLE_MS = 5_000L
        private const val TRAFFIC_GESTURE_EDGE_DP = 96f
        private const val TRAFFIC_GESTURE_DRAG_DP = 48f
        private const val PREFERENCES_NAME = "receiver"
        private const val PREFERENCE_WAKE_MODE = "wake_mode"
        private const val DEBUG_CODECS = false
        private const val STREAM_WIDTH = 1280
        private const val STREAM_HEIGHT = 720
    }
}
