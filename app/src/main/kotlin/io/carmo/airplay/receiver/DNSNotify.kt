package io.carmo.airplay.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class DNSNotify(
    context: Context,
    private val onStatusChanged: (String) -> Unit = {}
) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var airplayRegister: NsdRegister? = null
    val deviceName: String = resolveDeviceName(context)
    private val macAddress: String = NetUtils.localMacAddress()
    private var airplayStatus: String = "AirPlay idle"

    fun registerAirplay(port: Int) {
        Log.d(TAG, "registerAirplay port = $port, macAddress = $macAddress")
        airplayRegister?.stop()
        updateRegistrationStatus("AirPlay announcing on port $port")
        airplayRegister = NsdRegister(
            nsdManager,
            deviceName,
            "_airplay._tcp.",
            port,
            mapOf(
                "deviceid" to macAddress,
                "features" to "0x5A7FFFF7,0x1E",
                "srcvers" to "220.68",
                "flags" to "0x4",
                "vv" to "2",
                "model" to "AppleTV2,1",
                "pw" to "false",
                "rhd" to "5.6.0.0",
                "pk" to "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7",
                "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536"
            ),
            AIRPLAY_LABEL,
            ::updateRegistrationStatus
        )
    }

    private fun updateRegistrationStatus(status: String) {
        airplayStatus = status
        onStatusChanged(airplayStatus)
    }

    fun stop() {
        airplayRegister?.stop()
        airplayRegister = null
    }

    private class NsdRegister(
        private val nsdManager: NsdManager,
        serviceName: String,
        serviceType: String,
        port: Int,
        private val attributes: Map<String, String>,
        private val label: String,
        private val onStatusChanged: (String) -> Unit
    ) : NsdManager.RegistrationListener {
        private val lock = ReentrantLock()
        private var isStopped = false
        private var isRegistered = false

        init {
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = serviceType
                this.port = port
            }
            attributes.forEach { (key, value) ->
                serviceInfo.setAttribute(key, value)
            }
            lock.lock()
            try {
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, this)
            } catch (e: Throwable) {
                e.printStackTrace()
                onStatusChanged("$label failed: ${e.javaClass.simpleName}")
            } finally {
                lock.unlock()
            }
        }

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "$label registered: ${serviceInfo.serviceName}.${serviceInfo.serviceType}")
            var shouldUnregister = false
            lock.lock()
            try {
                isRegistered = true
                shouldUnregister = isStopped
            } finally {
                lock.unlock()
            }
            if (shouldUnregister) {
                stop()
                return
            }
            onStatusChanged("$label announced as ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "$label registration failed: $errorCode")
            if (!isStopped) {
                onStatusChanged("$label failed: $errorCode")
            }
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "$label unregistered: ${serviceInfo.serviceName}.${serviceInfo.serviceType}")
            lock.lock()
            try {
                isRegistered = false
            } finally {
                lock.unlock()
            }
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "$label unregistration failed: $errorCode")
        }

        fun stop() {
            lock.lock()
            try {
                isStopped = true
                if (isRegistered) {
                    nsdManager.unregisterService(this)
                    isRegistered = false
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "$label was already unregistered")
            } finally {
                lock.unlock()
            }
        }
    }

    companion object {
        private const val TAG = "Receiver-DNS"
        private const val AIRPLAY_LABEL = "AirPlay"

        private fun resolveDeviceName(context: Context): String {
            val configuredName = Settings.Global.getString(context.contentResolver, "device_name")
            if (configuredName.isUsableName()) {
                return configuredName.sanitizeServiceName()
            }

            val bluetoothName = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            if (bluetoothName.isUsableName()) {
                return bluetoothName.sanitizeServiceName()
            }

            val manufacturer = Build.MANUFACTURER.cleanBuildValue()
            val model = Build.MODEL.cleanBuildValue()
            return if (model.lowercase(Locale.US).startsWith(manufacturer.lowercase(Locale.US))) {
                model.sanitizeServiceName()
            } else {
                "$manufacturer $model".sanitizeServiceName()
            }
        }

        private fun String?.isUsableName(): Boolean = !this.isNullOrBlank()

        private fun String?.cleanBuildValue(): String = if (this.isNullOrBlank()) {
            "Android"
        } else {
            trim()
        }

        private fun String.sanitizeServiceName(): String {
            val name = trim().replace(Regex("\\s+"), " ")
            return if (name.length > 63) name.substring(0, 63) else name
        }
    }
}
