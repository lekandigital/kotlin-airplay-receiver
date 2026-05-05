package io.carmo.airplay.receiver

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class DNSNotify(
    context: Context,
    private val onStatusChanged: (String) -> Unit = {}
) {

    val deviceName: String = resolveDeviceName(context)
    private val macAddress: String = NetUtils.localMacAddress()
    private val lock = Any()
    private var announcer: MdnsAnnouncer? = null
    private var airplayService: MdnsService? = null
    private var raopService: MdnsService? = null
    private var airplayStatus: String = "AirPlay idle"
    private var raopStatus: String = "RAOP idle"

    fun registerAirplay(port: Int) {
        Log.d(TAG, "registerAirplay port = $port, macAddress = $macAddress")
        val service = MdnsService(
            label = AIRPLAY_LABEL,
            instance = deviceName.toDnsLabel(MAX_INSTANCE_LABEL_LENGTH),
            type = AIRPLAY_TYPE,
            port = port,
            txt = linkedMapOf(
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
            )
        )
        synchronized(lock) {
            airplayService = service
            updateRegistrationStatus(AIRPLAY_LABEL, "AirPlay announcing on port $port")
            ensureAnnouncerLocked().setServices(airplayService, raopService)
        }
    }

    fun registerRaop(port: Int, acceptAudio: Boolean) {
        Log.d(TAG, "registerRaop port = $port, acceptAudio = $acceptAudio")
        val txt = linkedMapOf(
            "da" to acceptAudio.toString(),
            "et" to "0,3,5",
            "vv" to "2",
            "ft" to "0x5A7FFFF7,0x1E",
            "am" to "AppleTV2,1",
            "rhd" to "5.6.0.0",
            "pw" to "false",
            "sv" to "false",
            "tp" to "UDP",
            "txtvers" to "1",
            "sf" to "0x4",
            "vs" to "220.68",
            "vn" to "65537",
            "pk" to "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7"
        )
        if (acceptAudio) {
            txt["ch"] = "2"
            txt["cn"] = "0,1,2,3"
            txt["md"] = "0,1,2"
            txt["sr"] = "44100"
            txt["ss"] = "16"
        }
        val prefix = "${macAddress.replace(":", "")}@"
        val service = MdnsService(
            label = RAOP_LABEL,
            instance = prefix + deviceName.toDnsLabel(MAX_INSTANCE_LABEL_LENGTH - prefix.length),
            type = RAOP_TYPE,
            port = port,
            txt = txt
        )
        synchronized(lock) {
            raopService = service
            updateRegistrationStatus(RAOP_LABEL, "RAOP announcing on port $port")
            ensureAnnouncerLocked().setServices(airplayService, raopService)
        }
    }

    fun stop() {
        synchronized(lock) {
            airplayService = null
            raopService = null
            announcer?.stopAnnouncing()
            announcer = null
            updateRegistrationStatus(AIRPLAY_LABEL, "AirPlay idle")
            updateRegistrationStatus(RAOP_LABEL, "RAOP idle")
        }
    }

    private fun ensureAnnouncerLocked(): MdnsAnnouncer {
        return announcer ?: MdnsAnnouncer(::handleAnnouncerStatus).also {
            announcer = it
            it.start()
        }
    }

    private fun handleAnnouncerStatus(label: String, status: String) {
        synchronized(lock) {
            updateRegistrationStatus(label, status)
        }
    }

    private fun updateRegistrationStatus(label: String, status: String) {
        if (label == AIRPLAY_LABEL) {
            airplayStatus = status
        } else {
            raopStatus = status
        }
        onStatusChanged("$airplayStatus\n$raopStatus")
    }

    private data class MdnsService(
        val label: String,
        val instance: String,
        val type: String,
        val port: Int,
        val txt: LinkedHashMap<String, String>
    ) {
        val typeName: String = "$type.local"
        val instanceName: String = "$instance.$type.local"

        fun matchesQuery(query: String): Boolean {
            return query.contains(type.substringBefore('.'), ignoreCase = true) ||
                query.contains(instance, ignoreCase = true)
        }
    }

    private class MdnsAnnouncer(
        private val onStatusChanged: (String, String) -> Unit
    ) : Thread("ReceiverMdnsAnnouncer") {
        private val running = AtomicBoolean(true)
        private val servicesLock = Any()
        private var services: List<MdnsService> = emptyList()
        private var multicastSocket: MulticastSocket? = null
        private var sendSocket: MulticastSocket? = null

        fun setServices(airplay: MdnsService?, raop: MdnsService?) {
            synchronized(servicesLock) {
                services = listOfNotNull(airplay, raop)
            }
            interrupt()
        }

        fun stopAnnouncing() {
            running.set(false)
            interrupt()
            multicastSocket?.close()
            sendSocket?.close()
        }

        override fun run() {
            var nextAnnouncementAtMs = 0L
            val receiveBuffer = ByteArray(MAX_DNS_PACKET_SIZE)
            while (running.get()) {
                val localAddress = localIpv4Address()
                if (localAddress == null) {
                    reportAll("mDNS waiting for Wi-Fi address")
                    sleepQuietly(RETRY_DELAY_MS)
                    continue
                }

                val socket = ensureMulticastSocket(localAddress)
                val sender = socket ?: ensureSendSocket(localAddress)
                if (sender == null) {
                    reportAll("mDNS socket unavailable")
                    sleepQuietly(RETRY_DELAY_MS)
                    continue
                }

                val now = System.currentTimeMillis()
                val snapshot = serviceSnapshot()
                if (snapshot.isNotEmpty() && now >= nextAnnouncementAtMs) {
                    sendAnnouncement(sender, localAddress, snapshot, onlyMatching = null)
                    snapshot.forEach { onStatusChanged(it.label, "${it.label} announced on port ${it.port}") }
                    nextAnnouncementAtMs = now + ANNOUNCE_INTERVAL_MS
                }

                if (socket == null) {
                    sleepQuietly(SEND_ONLY_IDLE_MS)
                    continue
                }

                try {
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(packet)
                    val query = String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1)
                    val matching = matchingServices(query, snapshot)
                    if (matching.isNotEmpty()) {
                        sendAnnouncement(socket, localAddress, matching, query)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.w(TAG, "mDNS receive/send failed", e)
                        closeSockets()
                        sleepQuietly(RETRY_DELAY_MS)
                    }
                }
            }
            closeSockets()
        }

        private fun ensureMulticastSocket(localAddress: InetAddress): MulticastSocket? {
            val existing = multicastSocket
            if (existing != null && !existing.isClosed) {
                return existing
            }
            return try {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MDNS_PORT))
                    timeToLive = MDNS_TTL
                    soTimeout = RECEIVE_TIMEOUT_MS.toInt()
                    networkInterface = NetworkInterface.getByInetAddress(localAddress)
                    joinGroup(MDNS_GROUP)
                    multicastSocket = this
                }
            } catch (e: Exception) {
                Log.w(TAG, "mDNS listen socket unavailable; falling back to announce-only", e)
                multicastSocket = null
                null
            }
        }

        private fun ensureSendSocket(localAddress: InetAddress): MulticastSocket? {
            val existing = sendSocket
            if (existing != null && !existing.isClosed) {
                return existing
            }
            return try {
                MulticastSocket().apply {
                    timeToLive = MDNS_TTL
                    networkInterface = NetworkInterface.getByInetAddress(localAddress)
                    sendSocket = this
                }
            } catch (e: Exception) {
                Log.w(TAG, "mDNS send socket unavailable", e)
                sendSocket = null
                null
            }
        }

        private fun closeSockets() {
            try {
                multicastSocket?.close()
            } catch (_: Exception) {
            }
            try {
                sendSocket?.close()
            } catch (_: Exception) {
            }
            multicastSocket = null
            sendSocket = null
        }

        private fun sendAnnouncement(
            socket: MulticastSocket,
            localAddress: InetAddress,
            services: List<MdnsService>,
            onlyMatching: String?
        ) {
            val packet = buildResponse(localAddress, services, onlyMatching)
            if (packet.isEmpty()) {
                return
            }
            socket.send(DatagramPacket(packet, packet.size, MDNS_GROUP, MDNS_PORT))
        }

        private fun buildResponse(
            localAddress: InetAddress,
            services: List<MdnsService>,
            query: String?
        ): ByteArray {
            val answers = ArrayList<ByteArray>()
            val includeEnumeration = query == null || query.contains(SERVICE_ENUMERATION_QUERY, ignoreCase = true)
            val targetName = hostName(localAddress)

            if (includeEnumeration) {
                services.forEach { service ->
                    answers += resourceRecord(
                        SERVICE_ENUMERATION_NAME,
                        DNS_TYPE_PTR,
                        DNS_CLASS_IN,
                        PTR_TTL_SECONDS,
                        dnsName(service.typeName)
                    )
                }
            }

            services.forEach { service ->
                val queryMatchesService = query == null ||
                    service.matchesQuery(query) ||
                    query.contains(targetName, ignoreCase = true) ||
                    includeEnumeration
                if (queryMatchesService) {
                    answers += resourceRecord(
                        service.typeName,
                        DNS_TYPE_PTR,
                        DNS_CLASS_IN,
                        PTR_TTL_SECONDS,
                        dnsName(service.instanceName)
                    )
                    answers += resourceRecord(
                        service.instanceName,
                        DNS_TYPE_SRV,
                        DNS_CLASS_FLUSH,
                        RECORD_TTL_SECONDS,
                        srvData(service.port, targetName)
                    )
                    answers += resourceRecord(
                        service.instanceName,
                        DNS_TYPE_TXT,
                        DNS_CLASS_FLUSH,
                        RECORD_TTL_SECONDS,
                        txtData(service.txt)
                    )
                }
            }

            if (answers.isNotEmpty()) {
                answers += resourceRecord(
                    targetName,
                    DNS_TYPE_A,
                    DNS_CLASS_FLUSH,
                    RECORD_TTL_SECONDS,
                    localAddress.address
                )
            }

            if (answers.isEmpty()) {
                return ByteArray(0)
            }

            return ByteArrayOutputStream().apply {
                writeShort(0)
                writeShort(DNS_FLAGS_RESPONSE_AUTHORITATIVE)
                writeShort(0)
                writeShort(answers.size)
                writeShort(0)
                writeShort(0)
                answers.forEach { write(it) }
            }.toByteArray()
        }

        private fun resourceRecord(name: String, type: Int, dnsClass: Int, ttl: Int, data: ByteArray): ByteArray {
            return ByteArrayOutputStream().apply {
                write(dnsName(name))
                writeShort(type)
                writeShort(dnsClass)
                writeInt(ttl)
                writeShort(data.size)
                write(data)
            }.toByteArray()
        }

        private fun srvData(port: Int, targetName: String): ByteArray {
            return ByteArrayOutputStream().apply {
                writeShort(0)
                writeShort(0)
                writeShort(port)
                write(dnsName(targetName))
            }.toByteArray()
        }

        private fun txtData(values: Map<String, String>): ByteArray {
            return ByteArrayOutputStream().apply {
                values.forEach { (key, value) ->
                    val entry = "$key=$value".toByteArray(Charsets.UTF_8)
                    if (entry.size <= MAX_TXT_ENTRY_LENGTH) {
                        write(entry.size)
                        write(entry)
                    }
                }
            }.toByteArray()
        }

        private fun dnsName(name: String): ByteArray {
            return ByteArrayOutputStream().apply {
                name.trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { rawLabel ->
                    val label = rawLabel.toByteArray(Charsets.UTF_8)
                    write(label.size.coerceAtMost(MAX_DNS_LABEL_LENGTH))
                    write(label, 0, label.size.coerceAtMost(MAX_DNS_LABEL_LENGTH))
                }
                write(0)
            }.toByteArray()
        }

        private fun matchingServices(query: String, services: List<MdnsService>): List<MdnsService> {
            if (query.contains(SERVICE_ENUMERATION_QUERY, ignoreCase = true)) {
                return services
            }
            return services.filter { service ->
                service.matchesQuery(query)
            }
        }

        private fun serviceSnapshot(): List<MdnsService> {
            return synchronized(servicesLock) { services.toList() }
        }

        private fun reportAll(status: String) {
            serviceSnapshot().forEach { onStatusChanged(it.label, status) }
        }

        private fun hostName(localAddress: InetAddress): String {
            return "receiver-${localAddress.hostAddress.replace('.', '-')}.local"
        }

        private fun sleepQuietly(ms: Long) {
            try {
                sleep(ms)
            } catch (_: InterruptedException) {
            }
        }
    }

    companion object {
        private const val TAG = "Receiver-DNS"
        private const val AIRPLAY_LABEL = "AirPlay"
        private const val RAOP_LABEL = "RAOP"
        private const val AIRPLAY_TYPE = "_airplay._tcp"
        private const val RAOP_TYPE = "_raop._tcp"
        private const val SERVICE_ENUMERATION_NAME = "_services._dns-sd._udp.local"
        private const val SERVICE_ENUMERATION_QUERY = "_services"
        private const val MDNS_PORT = 5353
        private const val MDNS_TTL = 255
        private const val RECEIVE_TIMEOUT_MS = 500L
        private const val RETRY_DELAY_MS = 2_000L
        private const val SEND_ONLY_IDLE_MS = 500L
        private const val ANNOUNCE_INTERVAL_MS = 20_000L
        private const val MAX_DNS_PACKET_SIZE = 1500
        private const val MAX_DNS_LABEL_LENGTH = 63
        private const val MAX_INSTANCE_LABEL_LENGTH = 63
        private const val MAX_TXT_ENTRY_LENGTH = 255
        private const val DNS_FLAGS_RESPONSE_AUTHORITATIVE = 0x8400
        private const val DNS_CLASS_IN = 0x0001
        private const val DNS_CLASS_FLUSH = 0x8001
        private const val DNS_TYPE_A = 1
        private const val DNS_TYPE_PTR = 12
        private const val DNS_TYPE_TXT = 16
        private const val DNS_TYPE_SRV = 33
        private const val PTR_TTL_SECONDS = 4_500
        private const val RECORD_TTL_SECONDS = 120
        private val MDNS_GROUP: InetAddress = InetAddress.getByName("224.0.0.251")

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
            return name.toDnsLabel(MAX_INSTANCE_LABEL_LENGTH)
        }

        private fun String.toDnsLabel(maxLength: Int): String {
            val sanitized = trim()
                .replace('.', '-')
                .ifBlank { "Receiver" }
            return if (sanitized.length > maxLength) sanitized.substring(0, maxLength) else sanitized
        }

        private fun localIpv4Address(): InetAddress? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces().asSequence().toList()
                interfaces.firstNotNullOfOrNull { networkInterface ->
                    if (!networkInterface.isUp || networkInterface.isLoopback) {
                        null
                    } else {
                        networkInterface.inetAddresses.asSequence()
                            .filterIsInstance<Inet4Address>()
                            .firstOrNull { !it.isLoopbackAddress }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to resolve local IPv4 address", e)
                null
            }
        }

        private fun ByteArrayOutputStream.writeShort(value: Int) {
            write((value ushr 8) and 0xFF)
            write(value and 0xFF)
        }

        private fun ByteArrayOutputStream.writeInt(value: Int) {
            write((value ushr 24) and 0xFF)
            write((value ushr 16) and 0xFF)
            write((value ushr 8) and 0xFF)
            write(value and 0xFF)
        }
    }
}
