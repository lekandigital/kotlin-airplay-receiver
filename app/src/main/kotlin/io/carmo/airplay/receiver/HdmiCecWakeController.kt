package io.carmo.airplay.receiver

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Display
import java.lang.reflect.Proxy

class HdmiCecWakeController(private val context: Context) {
    private val appContext = context.applicationContext

    @Volatile private var lastResult: String = "not attempted"
    @Volatile private var lastAttemptAtMs: Long = 0L

    fun status(): HdmiCecWakeStatus {
        return HdmiCecWakeStatus(
            enabled = ReceiverPreferences.experimentalHdmiCecWake(appContext),
            hdmiControlAvailable = playbackClientAvailable(),
            lastResult = lastResult,
            lastAttemptAtMs = lastAttemptAtMs
        )
    }

    fun wakeForIncomingConnection() {
        if (!ReceiverPreferences.experimentalHdmiCecWake(appContext)) {
            lastResult = "disabled"
            return
        }
        if (isDisplayInteractive()) {
            lastResult = "display already on"
            return
        }
        lastAttemptAtMs = System.currentTimeMillis()
        val playbackClient = playbackClient()
        if (playbackClient != null) {
            try {
                Log.d(TAG, "requesting HDMI-CEC one touch play")
                invokeOneTouchPlay(playbackClient)
                return
            } catch (e: Throwable) {
                lastResult = "CEC failed: ${e.javaClass.simpleName}"
                Log.d(TAG, "HDMI-CEC wake failed", e)
            }
        } else {
            lastResult = "HDMI control unavailable"
            Log.d(TAG, lastResult)
        }
        acquireFallbackWakeLock()
    }

    private fun playbackClientAvailable(): Boolean {
        return playbackClient() != null
    }

    private fun hdmiControlManager(): Any? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            appContext.getSystemService("hdmi_control")
        } else {
            null
        }
    }

    private fun playbackClient(): Any? {
        val manager = hdmiControlManager() ?: return null
        return runCatching {
            manager.javaClass.getMethod("getPlaybackClient").invoke(manager)
        }.getOrNull()
    }

    private fun invokeOneTouchPlay(playbackClient: Any) {
        val callbackClass = Class.forName("android.hardware.hdmi.HdmiPlaybackClient\$OneTouchPlayCallback")
        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
        ) { _, _, args ->
            val result = (args?.firstOrNull() as? Int) ?: -1
            lastResult = if (result == 0) {
                "CEC one touch play sent"
            } else {
                "CEC one touch play result=$result"
            }
            Log.d(TAG, lastResult)
            null
        }
        playbackClient.javaClass.getMethod("oneTouchPlay", callbackClass).invoke(playbackClient, callback)
    }

    private fun isDisplayInteractive(): Boolean {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive) return true
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays.any { display ->
            display.state == Display.STATE_ON || display.state == Display.STATE_DOZE
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireFallbackWakeLock() {
        runCatching {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "${appContext.packageName}:cec-fallback"
            )
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(FALLBACK_WAKE_MS)
            lastResult = "$lastResult; fallback wake lock acquired"
            Log.d(TAG, lastResult)
        }.onFailure { error ->
            lastResult = "$lastResult; fallback failed: ${error.javaClass.simpleName}"
            Log.d(TAG, "fallback wake failed", error)
        }
    }

    companion object {
        private const val TAG = "Receiver-CEC"
        private const val FALLBACK_WAKE_MS = 3_000L
    }
}

data class HdmiCecWakeStatus(
    val enabled: Boolean,
    val hdmiControlAvailable: Boolean,
    val lastResult: String,
    val lastAttemptAtMs: Long
)
