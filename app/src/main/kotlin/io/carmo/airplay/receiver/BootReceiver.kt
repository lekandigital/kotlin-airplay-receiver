package io.carmo.airplay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts the receiver service on device boot and after package updates.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)
        val startOnBoot = prefs.getBoolean("start_on_boot", true)
        if (!startOnBoot && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "start on boot disabled; skipping")
            return
        }

        Log.i(TAG, "starting receiver service (action=$action)")
        val serviceIntent = Intent(context, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "Receiver-Boot"
    }
}
