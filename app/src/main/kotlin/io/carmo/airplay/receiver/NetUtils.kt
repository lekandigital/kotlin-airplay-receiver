package io.carmo.airplay.receiver

import java.net.NetworkInterface
import java.util.Locale

object NetUtils {
    fun localMacAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .firstOrNull { it.name.equals("wlan0", ignoreCase = true) }
                ?.hardwareAddress
                ?.joinToString(":") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
                ?: FALLBACK_MAC
        } catch (e: Exception) {
            e.printStackTrace()
            FALLBACK_MAC
        }
    }

    private const val FALLBACK_MAC = "00:00:00:00:00:00"
}
