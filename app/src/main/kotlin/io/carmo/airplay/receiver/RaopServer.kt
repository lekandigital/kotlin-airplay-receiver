package io.carmo.airplay.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.carmo.airplay.receiver.model.NALPacket
import io.carmo.airplay.receiver.model.PCMPacket
import io.carmo.airplay.receiver.player.AudioPlayer
import io.carmo.airplay.receiver.player.VideoPlayer
import java.nio.ByteBuffer
import java.util.ArrayDeque

class RaopServer(
    private val context: Context,
    private val onConnectionStarted: () -> Unit,
    private val onVideoActivity: (Boolean) -> Unit,
    private val onTrafficSample: (Int) -> Unit,
    private val onLatencySample: (Long) -> Unit,
    private val onVideoSizeChanged: (Int, Int) -> Unit,
    private val onStreamStatusChanged: (String) -> Unit,
    private val onStateChanged: (ReceiverState) -> Unit,
    initialVideoWidth: Int,
    initialVideoHeight: Int,
    initialAudioVolume: Float
) {

    @Volatile private var videoPlayer: VideoPlayer? = null
    @Volatile private var currentSurface: Surface? = null
    private val preSurfaceBuffer = ArrayDeque<NALPacket>(PRE_SURFACE_BUFFER_SIZE)
    private val preSurfaceLock = Any()
    private var audioPlayer: AudioPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var serverId: Long = 0
    @Volatile private var lastMediaPacketAtMs = 0L
    @Volatile private var lastVideoPacketAtMs = 0L
    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var videoWidth = initialVideoWidth
    @Volatile private var videoHeight = initialVideoHeight
    @Volatile private var fallbackAudioVolume = initialAudioVolume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    @Volatile private var audioVolume = fallbackAudioVolume
    @Volatile private var hasConnection = false
    @Volatile private var hasStartedVideo = false
    @Volatile private var hasSessionVolume = false
    @Volatile private var currentState = ReceiverState.IDLE_ADVERTISING

    /**
     * Cached SPS/PPS bytes from the most recent codec-config NAL we received
     * over the wire. The Mac sends SPS/PPS once at session start; if our
     * VideoPlayer is recreated mid-session we replay this into the new player.
     */
    @Volatile private var cachedCodecConfig: ByteArray? = null
    @Volatile private var firstVideoBytesAtMs = 0L
    @Volatile private var firstAudioBytesAtMs = 0L
    @Volatile private var lastVideoStatusAtMs = 0L
    @Volatile private var lastNoSurfaceVideoLogAtMs = 0L
    @Volatile private var lastRenderedFrameCountAtMs = 0L
    @Volatile private var renderedFrameCount = 0
    @Volatile private var decoderRestartCount = 0
    @Volatile private var audioUnderrunCount = 0
    @Volatile private var maxPreSurfacePacketsBuffered = 0
    @Volatile private var lastSessionStats = ReceiverSessionStats()
    private var renderWatchdogFrameCount = 0

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onRecvVideoData(buffer: ByteBuffer, size: Int, nativePointer: Long, nalType: Int, dts: Long, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvVideoData dts = $dts, pts = $pts, nalType = $nalType, nal length = $size")
        }
        markMediaTraffic()
        val now = SystemClock.elapsedRealtime()
        lastVideoPacketAtMs = now
        if (firstVideoBytesAtMs == 0L) {
            firstVideoBytesAtMs = now
            scheduleStartupWatchdog()
            reportStreamStatus("Video bytes received", now)
            emitState(ReceiverState.VIDEO_REQUESTED)
            Log.i(TAG, "first video bytes received (size=$size, nalType=$nalType)")
        } else if (!hasStartedVideo && now - lastVideoStatusAtMs >= VIDEO_STATUS_INTERVAL_MS) {
            reportStreamStatus("Video bytes receiving", now)
        }
        markVideoActivity()
        onTrafficSample(size)

        if (nalType == NAL_TYPE_CODEC_CONFIG && size > 0) {
            val copy = ByteArray(size)
            val duplicate = buffer.duplicate()
            duplicate.position(0)
            duplicate.limit(size)
            duplicate.get(copy)
            cachedCodecConfig = copy
            Log.i(TAG, "cached SPS/PPS (size=$size) for replay")
        }

        val packet = NALPacket(
            data = buffer,
            size = size,
            nativePointer = nativePointer,
            nalType = nalType,
            pts = pts,
            dts = dts,
            receivedAtMs = SystemClock.elapsedRealtime()
        )
        val player = videoPlayer ?: ensureVideoPlayer()
        if (player == null) {
            bufferPreSurfacePacket(packet)
            emitState(ReceiverState.WAITING_FOR_SURFACE)
            logMissingSurface(nalType, size)
            return
        }
        player.addPacket(packet)
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onRecvAudioData(buffer: ByteBuffer, size: Int, nativePointer: Long, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvAudioData pcm bytes = $size, pts = $pts")
        }
        markMediaTraffic()
        if (firstAudioBytesAtMs == 0L) {
            firstAudioBytesAtMs = SystemClock.elapsedRealtime()
            if (!hasSessionVolume) {
                audioVolume = fallbackAudioVolume
                audioPlayer?.setVolume(audioVolume)
            }
            onStreamStatusChanged("Audio bytes received")
            if (firstVideoBytesAtMs == 0L) {
                emitState(ReceiverState.AUDIO_ACTIVE)
            }
            Log.i(TAG, "first audio bytes received (size=$size, pts=$pts)")
        }
        onTrafficSample(size)
        val packet = PCMPacket(
            data = buffer,
            size = size,
            nativePointer = nativePointer,
            pts = pts,
            receivedAtMs = SystemClock.elapsedRealtime()
        )
        ensureAudioPlayer().addPacket(packet)
    }

    @Synchronized
    fun attachSurface(surface: Surface) {
        if (currentSurface === surface && videoPlayer != null) {
            return
        }
        currentSurface = surface
        if (firstVideoBytesAtMs != 0L && surface.isValid) {
            ensureVideoPlayer(surface)
        }
    }

    @Synchronized
    fun detachSurface() {
        currentSurface = null
        stopVideoPlayer()
        hasStartedVideo = false
        if (firstVideoBytesAtMs != 0L && currentState != ReceiverState.STOPPING_SESSION) {
            emitState(ReceiverState.WAITING_FOR_SURFACE)
        }
    }

    fun startServer() {
        if (serverId == 0L) {
            serverId = start()
            if (serverId != 0L) {
                onStreamStatusChanged("Receiver ready")
            }
        }
    }

    fun stopServer() {
        mainHandler.removeCallbacks(startupWatchdog)
        mainHandler.removeCallbacks(startupWatchdogFinal)
        mainHandler.removeCallbacks(renderWatchdog)
        if (serverId != 0L) {
            stop(serverId)
        }
        serverId = 0L
        stopVideoPlayer()
        audioPlayer?.stopPlay()
        audioPlayer = null
        resetSessionCounters()
        clearPreSurfaceBuffer()
        cachedCodecConfig = null
        onStreamStatusChanged("Stream idle")
    }

    fun setVideoMode(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        if (hasConnection) {
            return
        }
        stopVideoPlayer()
    }

    fun setAudioVolume(volume: Float) {
        fallbackAudioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        if (!hasSessionVolume) {
            audioVolume = fallbackAudioVolume
        }
        audioPlayer?.setVolume(audioVolume)
    }

    fun sessionStats(): ReceiverSessionStats {
        return if (sessionStartedAtMs != 0L || hasConnection) {
            currentSessionStats()
        } else {
            lastSessionStats
        }
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onSetAudioVolume(volumeDb: Float) {
        hasSessionVolume = true
        audioVolume = raopVolumeToLinear(volumeDb)
        audioPlayer?.setVolume(audioVolume)
        Log.i(TAG, "RAOP volume changed: db=$volumeDb, linear=$audioVolume")
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onAudioFlush() {
        Log.i(TAG, "RAOP audio flush requested")
        audioPlayer?.flushPlayback()
    }

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun getVideoWidth(): Int = videoWidth

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun getVideoHeight(): Int = videoHeight

    @Suppress("unused")
    @SuppressLint("AnnotateVersionCheck")
    fun onStreamStopped() {
        mainHandler.post {
            if (serverId != 0L) {
                handleStreamStopped()
            }
        }
    }

    val port: Int
        get() = if (serverId != 0L) getPort(serverId) else 0

    private external fun start(): Long
    private external fun stop(serverId: Long)
    private external fun getPort(serverId: Long): Int

    private fun markMediaTraffic() {
        lastMediaPacketAtMs = SystemClock.elapsedRealtime()
        if (sessionStartedAtMs == 0L) {
            sessionStartedAtMs = lastMediaPacketAtMs
        }
        if (!hasConnection) {
            hasConnection = true
        }
    }

    @Synchronized
    private fun ensureAudioPlayer(): AudioPlayer {
        val existingPlayer = audioPlayer
        if (existingPlayer != null) {
            existingPlayer.setVolume(audioVolume)
            return existingPlayer
        }
        return AudioPlayer(context, audioVolume, onLatencySample, ::handleAudioUnderrun)
            .also {
                audioPlayer = it
                it.start()
            }
    }

    private fun handleStreamStopped() {
        emitState(ReceiverState.STOPPING_SESSION)
        onStreamStatusChanged("Waiting")
        lastSessionStats = currentSessionStats()
        stopVideoPlayer()
        audioPlayer?.stopPlay()
        audioPlayer = null
        resetSessionCounters()
        clearPreSurfaceBuffer()
        emitState(ReceiverState.IDLE_ADVERTISING)
    }

    private fun resetSessionCounters() {
        mainHandler.removeCallbacks(startupWatchdog)
        mainHandler.removeCallbacks(startupWatchdogFinal)
        mainHandler.removeCallbacks(renderWatchdog)
        lastMediaPacketAtMs = 0L
        lastVideoPacketAtMs = 0L
        sessionStartedAtMs = 0L
        lastRenderedFrameCountAtMs = 0L
        renderedFrameCount = 0
        decoderRestartCount = 0
        audioUnderrunCount = 0
        maxPreSurfacePacketsBuffered = 0
        renderWatchdogFrameCount = 0
        hasConnection = false
        hasStartedVideo = false
        hasSessionVolume = false
        audioVolume = fallbackAudioVolume
        firstVideoBytesAtMs = 0L
        firstAudioBytesAtMs = 0L
        lastVideoStatusAtMs = 0L
        lastNoSurfaceVideoLogAtMs = 0L
    }

    private fun bufferPreSurfacePacket(packet: NALPacket) {
        synchronized(preSurfaceLock) {
            if (packet.isCodecConfig) {
                packet.release()
                return
            }
            if (preSurfaceBuffer.size >= PRE_SURFACE_BUFFER_SIZE) {
                preSurfaceBuffer.removeFirst().release()
            }
            preSurfaceBuffer.addLast(packet)
            maxPreSurfacePacketsBuffered = maxOf(maxPreSurfacePacketsBuffered, preSurfaceBuffer.size)
        }
    }

    private fun drainPreSurfaceBuffer(player: VideoPlayer) {
        synchronized(preSurfaceLock) {
            cachedCodecConfig?.let { config ->
                player.addPacket(NALPacket.forCodecConfig(config, SystemClock.elapsedRealtime()))
            }
            while (preSurfaceBuffer.isNotEmpty()) {
                player.addPacket(preSurfaceBuffer.removeFirst())
            }
        }
    }

    private fun clearPreSurfaceBuffer() {
        synchronized(preSurfaceLock) {
            while (preSurfaceBuffer.isNotEmpty()) {
                preSurfaceBuffer.removeFirst().release()
            }
        }
    }

    private fun logMissingSurface(nalType: Int, size: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNoSurfaceVideoLogAtMs < NO_SURFACE_LOG_INTERVAL_MS) {
            return
        }
        lastNoSurfaceVideoLogAtMs = now
        val queued = synchronized(preSurfaceLock) { preSurfaceBuffer.size }
        Log.w(
            TAG,
            "no video surface attached; buffering video packet " +
                "(nalType=$nalType, size=$size, queued=$queued)"
        )
    }

    @Synchronized
    private fun ensureVideoPlayer(surface: Surface? = currentSurface): VideoPlayer? {
        val currentPlayer = videoPlayer
        if (currentPlayer != null) {
            return currentPlayer
        }
        val validSurface = surface
        if (validSurface == null || !validSurface.isValid) {
            return null
        }
        val newPlayer = VideoPlayer(
            validSurface,
            videoWidth,
            videoHeight,
            onLatencySample,
            ::handleVideoFrameRendered,
            onVideoSizeChanged
        )
        videoPlayer = newPlayer
        onStreamStatusChanged("Decoder starting")
        emitState(ReceiverState.VIDEO_STARTING)
        newPlayer.start()
        drainPreSurfaceBuffer(newPlayer)
        return newPlayer
    }

    @Synchronized
    private fun stopVideoPlayer() {
        mainHandler.removeCallbacks(renderWatchdog)
        videoPlayer?.let { player ->
            player.stopDecode()
            try {
                player.join(VIDEO_RESTART_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        videoPlayer = null
    }

    private fun handleVideoFrameRendered() {
        renderedFrameCount++
        val now = SystemClock.elapsedRealtime()
        if (!hasStartedVideo) {
            hasStartedVideo = true
            mainHandler.removeCallbacks(startupWatchdog)
            mainHandler.removeCallbacks(startupWatchdogFinal)
            lastMediaPacketAtMs = now
            lastRenderedFrameCountAtMs = now
            onConnectionStarted()
            onStreamStatusChanged("First frame rendered")
            emitState(ReceiverState.VIDEO_ACTIVE)
            scheduleRenderWatchdog()
        } else {
            lastRenderedFrameCountAtMs = now
        }
    }

    private val renderWatchdog = object : Runnable {
        override fun run() {
            if (!hasStartedVideo || currentState == ReceiverState.STOPPING_SESSION) return
            val currentCount = renderedFrameCount
            val packetAgeMs = SystemClock.elapsedRealtime() - lastMediaPacketAtMs
            val renderAgeMs = SystemClock.elapsedRealtime() - lastRenderedFrameCountAtMs

            if (currentCount == renderWatchdogFrameCount && packetAgeMs < STALL_TRAFFIC_MAX_AGE_MS) {
                Log.w(TAG, "render watchdog: decoder stalled (renderAge=${renderAgeMs}ms, packets active)")
                emitState(ReceiverState.VIDEO_STALLED)
                onStreamStatusChanged("Recovering video")
                restartVideoDecoderOnly()
            }
            renderWatchdogFrameCount = currentCount
            mainHandler.postDelayed(this, RENDER_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun scheduleRenderWatchdog() {
        renderWatchdogFrameCount = renderedFrameCount
        mainHandler.removeCallbacks(renderWatchdog)
        mainHandler.postDelayed(renderWatchdog, RENDER_WATCHDOG_INTERVAL_MS)
    }

    @Synchronized
    private fun restartVideoDecoderOnly() {
        decoderRestartCount++
        stopVideoPlayer()
        hasStartedVideo = false
        lastRenderedFrameCountAtMs = 0L
        val surface = currentSurface
        if (surface != null && surface.isValid) {
            ensureVideoPlayer(surface)
        } else {
            emitState(ReceiverState.WAITING_FOR_SURFACE)
        }
    }

    private fun reportStreamStatus(status: String, now: Long = SystemClock.elapsedRealtime()) {
        lastVideoStatusAtMs = now
        onStreamStatusChanged(status)
    }

    private val startupWatchdog = Runnable {
        if (hasStartedVideo) {
            return@Runnable
        }
        val trafficAgeMs = SystemClock.elapsedRealtime() - lastMediaPacketAtMs
        if (lastMediaPacketAtMs == 0L || trafficAgeMs > STARTUP_WATCHDOG_TRAFFIC_MAX_AGE_MS) {
            return@Runnable
        }
        Log.w(TAG, "startup watchdog fired: ${STARTUP_WATCHDOG_MS}ms with traffic, no rendered frame")
        onStreamStatusChanged("Decoder starting (retry)")
        emitState(ReceiverState.VIDEO_STALLED)
        restartVideoDecoderOnly()
        mainHandler.postDelayed(startupWatchdogFinal, STARTUP_WATCHDOG_MS)
    }

    private val startupWatchdogFinal = Runnable {
        if (hasStartedVideo) {
            return@Runnable
        }
        Log.e(TAG, "startup watchdog final: decoder did not recover after two attempts")
        onStreamStatusChanged("Video failed - disconnect and retry")
        handleStreamStopped()
    }

    private fun scheduleStartupWatchdog() {
        mainHandler.removeCallbacks(startupWatchdog)
        mainHandler.removeCallbacks(startupWatchdogFinal)
        mainHandler.postDelayed(startupWatchdog, STARTUP_WATCHDOG_MS)
    }

    private fun handleAudioUnderrun() {
        audioUnderrunCount++
    }

    private fun markVideoActivity() {
        onVideoActivity(true)
    }

    private fun emitState(state: ReceiverState) {
        currentState = state
        onStateChanged(state)
    }

    private fun currentSessionStats(): ReceiverSessionStats {
        val startedAt = sessionStartedAtMs
        val durationMs = if (startedAt == 0L) {
            0L
        } else {
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        }
        return ReceiverSessionStats(
            durationMs = durationMs,
            videoFramesRendered = renderedFrameCount,
            decoderRestarts = decoderRestartCount,
            audioUnderruns = audioUnderrunCount,
            preSurfacePacketsBuffered = maxPreSurfacePacketsBuffered
        )
    }

    private fun raopVolumeToLinear(volumeDb: Float): Float {
        if (volumeDb <= MIN_RAOP_VOLUME_DB) {
            return MIN_AUDIO_VOLUME
        }
        if (volumeDb >= MAX_RAOP_VOLUME_DB) {
            return MAX_AUDIO_VOLUME
        }
        return Math.pow(10.0, volumeDb.toDouble() / 20.0)
            .toFloat()
            .coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    }

    companion object {
        private const val TAG = "Receiver-RAOP"
        private const val DEBUG_FRAMES = false
        private const val MIN_AUDIO_VOLUME = 0.0f
        private const val MAX_AUDIO_VOLUME = 1.0f
        private const val MIN_RAOP_VOLUME_DB = -144.0f
        private const val MAX_RAOP_VOLUME_DB = 0.0f
        private const val VIDEO_RESTART_TIMEOUT_MS = 500L
        private const val VIDEO_STATUS_INTERVAL_MS = 1_000L
        private const val NO_SURFACE_LOG_INTERVAL_MS = 2_000L
        private const val PRE_SURFACE_BUFFER_SIZE = 16
        private const val STARTUP_WATCHDOG_MS = 6_000L
        private const val STARTUP_WATCHDOG_TRAFFIC_MAX_AGE_MS = 2_000L
        private const val RENDER_WATCHDOG_INTERVAL_MS = 4_000L
        private const val STALL_TRAFFIC_MAX_AGE_MS = 3_000L
        private const val NAL_TYPE_CODEC_CONFIG = 0

        init {
            System.loadLibrary("raop_server")
            System.loadLibrary("play-lib")
        }
    }
}
