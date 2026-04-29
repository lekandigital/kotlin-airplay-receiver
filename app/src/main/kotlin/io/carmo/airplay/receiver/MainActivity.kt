package io.carmo.airplay.receiver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var airPlayServer: AirPlayServer
    private lateinit var raopServer: RaopServer
    private lateinit var dnsNotify: DNSNotify
    private lateinit var statusView: TextView
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var isStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configurePlaybackWindow()

        val surfaceView = findViewById<SurfaceView>(R.id.surface)
        statusView = findViewById(R.id.status)
        keepSurfaceProportional(surfaceView)

        airPlayServer = AirPlayServer()
        raopServer = RaopServer(surfaceView, ::hideStatus)
        dnsNotify = DNSNotify(this)
        showWaitingStatus()

        if (DEBUG_CODECS) {
            logSupportedCodecs()
        }

        startServer()
    }

    override fun onResume() {
        super.onResume()
        if (isStarted) {
            keepReceiverAwake()
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
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

        val wakeLock = screenWakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
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
        statusView.visibility = View.VISIBLE
        statusView.bringToFront()
    }

    private fun hideStatus() {
        runOnUiThread {
            statusView.visibility = View.GONE
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
        keepReceiverAwake()
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
        allowScreenSaver()
        stopReceiverForegroundService()
    }

    companion object {
        private const val TAG = "Receiver"
        private const val WAKE_LOCK_TAG = "ReceiverActive"
        private const val DEBUG_CODECS = false
        private const val STREAM_WIDTH = 1280
        private const val STREAM_HEIGHT = 720
    }
}
