package io.carmo.airplay.receiver

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var airPlayServer: AirPlayServer
    private lateinit var raopServer: RaopServer
    private lateinit var dnsNotify: DNSNotify
    private var isStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keepPlaybackSurfaceFullScreen()

        val surfaceView = findViewById<SurfaceView>(R.id.surface)
        airPlayServer = AirPlayServer()
        raopServer = RaopServer(surfaceView)
        dnsNotify = DNSNotify(this)

        if (DEBUG_CODECS) {
            logSupportedCodecs()
        }

        startServer()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun keepPlaybackSurfaceFullScreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
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
        Log.d(TAG, "deviceName = ${dnsNotify.deviceName}, airplayPort = $airplayPort, raopPort = $raopPort")
    }

    private fun stopServer() {
        if (!isStarted) {
            return
        }
        dnsNotify.stop()
        airPlayServer.stopServer()
        raopServer.stopServer()
        isStarted = false
    }

    companion object {
        private const val TAG = "Receiver"
        private const val DEBUG_CODECS = false
    }
}
