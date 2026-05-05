package io.carmo.airplay.receiver

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.apple.dnssd.DNSSD
import com.apple.dnssd.DNSSDRegistration
import com.apple.dnssd.DNSSDService
import com.apple.dnssd.RegisterListener
import com.apple.dnssd.TXTRecord
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class DNSNotify(
    context: Context,
    private val onStatusChanged: (String) -> Unit = {}
) {

    private var airplayRegister: Register? = null
    private var raopRegister: Register? = null
    val deviceName: String = resolveDeviceName(context)
    private val macAddress: String = NetUtils.localMacAddress()
    private var airplayStatus: String = "AirPlay idle"
    private var raopStatus: String = "RAOP idle"

    fun registerAirplay(port: Int) {
        Log.d(TAG, "registerAirplay port = $port, macAddress = $macAddress")
        val txtRecord = TXTRecord().apply {
            set("deviceid", macAddress)
            set("features", "0x5A7FFFF7,0x1E")
            set("srcvers", "220.68")
            set("flags", "0x4")
            set("vv", "2")
            set("model", "AppleTV2,1")
            set("pw", "false")
            set("rhd", "5.6.0.0")
            set("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7")
            set("pi", "2e388006-13ba-4041-9a67-25dd4a43d536")
        }
        if (airplayRegister?.matches(deviceName, AIRPLAY_TYPE, port, txtRecord) == true) {
            return
        }
        airplayRegister?.stop()
        updateRegistrationStatus(AIRPLAY_LABEL, "AirPlay announcing on port $port")
        airplayRegister = Register(
            txtRecord,
            deviceName,
            AIRPLAY_TYPE,
            LOCAL_DOMAIN,
            EMPTY_HOST,
            port,
            AIRPLAY_LABEL,
            ::updateRegistrationStatus
        )
    }

    fun registerRaop(port: Int, acceptAudio: Boolean) {
        Log.d(TAG, "registerRaop port = $port, acceptAudio = $acceptAudio")
        val txtRecord = TXTRecord().apply {
            set("da", acceptAudio.toString())
            set("et", "0,3,5")
            set("vv", "2")
            set("ft", "0x5A7FFFF7,0x1E")
            set("am", "AppleTV2,1")
            set("rhd", "5.6.0.0")
            set("pw", "false")
            set("sv", "false")
            set("tp", "UDP")
            set("txtvers", "1")
            set("sf", "0x4")
            set("vs", "220.68")
            set("vn", "65537")
            set("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7")
            if (acceptAudio) {
                set("ch", "2")
                set("cn", "0,1,2,3")
                set("md", "0,1,2")
                set("sr", "44100")
                set("ss", "16")
            }
        }
        val serviceName = "${macAddress.replace(":", "")}@$deviceName"
        if (raopRegister?.matches(serviceName, RAOP_TYPE, port, txtRecord) == true) {
            return
        }
        raopRegister?.stop()
        updateRegistrationStatus(RAOP_LABEL, "RAOP announcing on port $port")
        raopRegister = Register(
            txtRecord,
            serviceName,
            RAOP_TYPE,
            LOCAL_DOMAIN,
            EMPTY_HOST,
            port,
            RAOP_LABEL,
            ::updateRegistrationStatus
        )
    }

    private fun updateRegistrationStatus(label: String, status: String) {
        if (label == AIRPLAY_LABEL) {
            airplayStatus = status
        } else {
            raopStatus = status
        }
        onStatusChanged("$airplayStatus\n$raopStatus")
    }

    fun stop() {
        airplayRegister?.stop()
        airplayRegister = null
        raopRegister?.stop()
        raopRegister = null
    }

    private class Register(
        private val txtRecord: TXTRecord,
        private val serviceName: String,
        private val regType: String,
        private val domain: String,
        private val host: String,
        private val port: Int,
        private val label: String,
        private val onStatusChanged: (String, String) -> Unit
    ) : RegisterListener {
        private val lock = ReentrantLock()
        private var registration: DNSSDRegistration? = null
        private var hasFailed = false

        init {
            lock.lock()
            try {
                registration = DNSSD.register(0, 0, serviceName, regType, domain, host, port, txtRecord, this)
            } catch (e: Throwable) {
                hasFailed = true
                Log.e(TAG, "$label registration failed", e)
                onStatusChanged(label, "$label failed: ${e.javaClass.simpleName}")
            } finally {
                lock.unlock()
            }
        }

        override fun serviceRegistered(
            registration: DNSSDRegistration,
            flags: Int,
            serviceName: String,
            regType: String,
            domain: String
        ) {
            Log.i(TAG, "$label registered: $serviceName.$regType$domain")
            onStatusChanged(label, "$label announced as $serviceName")
        }

        override fun operationFailed(service: DNSSDService, errorCode: Int) {
            Log.e(TAG, "$label registration failed: $errorCode")
            lock.lock()
            try {
                hasFailed = true
            } finally {
                lock.unlock()
            }
            onStatusChanged(label, "$label failed: $errorCode")
        }

        fun stop() {
            lock.lock()
            try {
                registration?.stop()
                registration = null
            } finally {
                lock.unlock()
            }
        }

        fun matches(serviceName: String, regType: String, port: Int, txtRecord: TXTRecord): Boolean {
            lock.lock()
            try {
                return !hasFailed &&
                    registration != null &&
                    this.serviceName == serviceName &&
                    this.regType == regType &&
                    this.port == port &&
                    this.txtRecord.rawBytes.contentEquals(txtRecord.rawBytes)
            } finally {
                lock.unlock()
            }
        }
    }

    companion object {
        private const val TAG = "Receiver-DNS"
        private const val AIRPLAY_LABEL = "AirPlay"
        private const val RAOP_LABEL = "RAOP"
        private const val AIRPLAY_TYPE = "_airplay._tcp"
        private const val RAOP_TYPE = "_raop._tcp"
        private const val LOCAL_DOMAIN = "local."
        private const val EMPTY_HOST = ""

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
