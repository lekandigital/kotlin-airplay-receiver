package io.carmo.airplay.receiver

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object ReceiverPreferences {
    const val PREFERENCES_NAME = "receiver"

    const val KEY_VIDEO_MODE = "video_mode_v2"
    const val VIDEO_MODE_AUTO = "auto"
    const val VIDEO_MODE_720P = "720p"
    const val VIDEO_MODE_1080P = "1080p"

    const val KEY_WAKE_MODE = "wake_mode"
    const val WAKE_MODE_DEFAULT = "default"
    const val WAKE_MODE_ALWAYS = "always_awake"
    const val WAKE_MODE_ACTIVITY = "wake_on_activity"

    const val KEY_START_ON_BOOT = "start_on_boot"
    const val KEY_AFTER_DISCONNECT = "after_disconnect"
    const val AFTER_DISCONNECT_WAITING = "waiting"
    const val AFTER_DISCONNECT_HOME = "home"

    const val KEY_AUTO_VIDEO_TAKEOVER = "auto_video_takeover"
    const val KEY_AUDIO_ONLY_DISPLAY = "audio_only_display"
    const val AUDIO_ONLY_BACKGROUND = "background"
    const val AUDIO_ONLY_STATUS = "status"

    const val KEY_DIAGNOSTICS_LEVEL = "diagnostics_level"
    const val DIAGNOSTICS_OFF = "off"
    const val DIAGNOSTICS_BASIC = "basic"

    const val KEY_CUSTOM_DEVICE_NAME = "custom_device_name"

    data class VideoSize(
        val width: Int,
        val height: Int,
        val modeLabel: String,
        val effectiveLabel: String
    )

    fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun selectedVideoSize(context: Context): VideoSize {
        return when (prefs(context).getString(KEY_VIDEO_MODE, VIDEO_MODE_AUTO)) {
            VIDEO_MODE_720P -> VideoSize(1280, 720, "720p", "720p")
            VIDEO_MODE_1080P -> VideoSize(1920, 1080, "1080p", "1080p")
            else -> detectOptimalResolution(context)
        }
    }

    fun videoModeSummary(context: Context): String {
        return selectedVideoSize(context).let { size ->
            if (size.modeLabel == "Auto") {
                "Auto - ${size.effectiveLabel}"
            } else {
                size.effectiveLabel
            }
        }
    }

    fun wakeMode(context: Context): String {
        return prefs(context).getString(KEY_WAKE_MODE, WAKE_MODE_ACTIVITY) ?: WAKE_MODE_ACTIVITY
    }

    fun wakeModeSummary(context: Context): String {
        return when (wakeMode(context)) {
            WAKE_MODE_DEFAULT -> "OS default"
            WAKE_MODE_ALWAYS -> "Always on"
            else -> "Wake on activity"
        }
    }

    fun afterDisconnect(context: Context): String {
        return prefs(context).getString(KEY_AFTER_DISCONNECT, AFTER_DISCONNECT_WAITING)
            ?: AFTER_DISCONNECT_WAITING
    }

    fun afterDisconnectSummary(context: Context): String {
        return if (afterDisconnect(context) == AFTER_DISCONNECT_HOME) {
            "Exit to home"
        } else {
            "Return to waiting"
        }
    }

    fun automaticVideoTakeover(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_VIDEO_TAKEOVER, true)
    }

    fun audioOnlyDisplay(context: Context): String {
        return prefs(context).getString(KEY_AUDIO_ONLY_DISPLAY, AUDIO_ONLY_BACKGROUND)
            ?: AUDIO_ONLY_BACKGROUND
    }

    fun audioOnlyDisplaySummary(context: Context): String {
        return if (audioOnlyDisplay(context) == AUDIO_ONLY_STATUS) {
            "Show status"
        } else {
            "Stay background"
        }
    }

    fun diagnosticsLevel(context: Context): String {
        return prefs(context).getString(KEY_DIAGNOSTICS_LEVEL, DIAGNOSTICS_OFF) ?: DIAGNOSTICS_OFF
    }

    fun diagnosticsSummary(context: Context): String {
        return if (diagnosticsLevel(context) == DIAGNOSTICS_BASIC) "Basic" else "Off"
    }

    fun customDeviceName(context: Context): String? {
        return prefs(context).getString(KEY_CUSTOM_DEVICE_NAME, null)?.trim()?.takeIf { it.isNotBlank() }
    }

    @Suppress("DEPRECATION")
    fun detectOptimalResolution(context: Context): VideoSize {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = windowManager?.defaultDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && display != null) {
            val hasFullHd = display.supportedModes.any {
                it.physicalWidth >= 1920 && it.physicalHeight >= 1080
            }
            if (hasFullHd) {
                return VideoSize(1920, 1080, "Auto", "1080p")
            }
        }

        val metrics = DisplayMetrics()
        display?.getMetrics(metrics)
        return if (metrics.widthPixels >= 1920 || metrics.heightPixels >= 1080) {
            VideoSize(1920, 1080, "Auto", "1080p")
        } else {
            VideoSize(1280, 720, "Auto", "720p")
        }
    }
}
