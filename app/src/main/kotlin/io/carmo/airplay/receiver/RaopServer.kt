package io.carmo.airplay.receiver

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.carmo.airplay.receiver.model.NALPacket
import io.carmo.airplay.receiver.model.NativeMemory
import io.carmo.airplay.receiver.model.PCMPacket
import io.carmo.airplay.receiver.player.AudioPlayer
import io.carmo.airplay.receiver.player.VideoPlayer
import java.nio.ByteBuffer

class RaopServer(
    private val surfaceView: SurfaceView,
    private val onConnectionStarted: () -> Unit,
    private val onVideoActivity: (Boolean) -> Unit,
    private val onTrafficSample: (Int) -> Unit,
    private val onLatencySample: (Long) -> Unit,
    private val onStreamStoppedCallback: () -> Unit,
    initialVideoWidth: Int,
    initialVideoHeight: Int,
    initialAcceptAudio: Boolean,
    initialAudioVolume: Float
) : SurfaceHolder.Callback {

    @Volatile private var videoPlayer: VideoPlayer? = null
    private var audioPlayer: AudioPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverId: Long = 0
    private var lastVideoActivityAtMs = 0L
    @Volatile private var lastMediaPacketAtMs = 0L
    @Volatile private var videoWidth = initialVideoWidth
    @Volatile private var videoHeight = initialVideoHeight
    @Volatile private var acceptAudio = initialAcceptAudio
    @Volatile private var audioVolume = initialAudioVolume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
    @Volatile private var hasConnection = false
    @Volatile private var hasRenderedVideo = false
    @Volatile private var streamStopThresholdMs = MEDIA_IDLE_STOP_MS

    init {
        surfaceView.holder.addCallback(this)
        if (acceptAudio) {
            ensureAudioPlayer()
        }
    }

    @Suppress("unused")
    fun onRecvVideoData(buffer: ByteBuffer, size: Int, nativePointer: Long, nalType: Int, dts: Long, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvVideoData dts = $dts, pts = $pts, nalType = $nalType, nal length = $size")
        }
        markConnected()
        markVideoActivity(buffer, size)
        onTrafficSample(size)
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
            packet.release()
            return
        }
        player.addPacket(packet)
    }

    @Suppress("unused")
    fun onRecvAudioData(buffer: ByteBuffer, size: Int, nativePointer: Long, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvAudioData pcm bytes = $size, pts = $pts")
        }
        markConnected()
        if (!acceptAudio) {
            NativeMemory.free(nativePointer)
            return
        }
        onTrafficSample(size)
        val packet = PCMPacket(
            data = buffer,
            size = size,
            nativePointer = nativePointer,
            pts = pts,
            receivedAtMs = SystemClock.elapsedRealtime()
        )
        val player = audioPlayer
        if (player == null) {
            packet.release()
            return
        }
        player.addPacket(packet)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoPlayer?.stopDecode()
        videoPlayer = null
        hasRenderedVideo = false
    }

    fun startServer() {
        if (acceptAudio) {
            ensureAudioPlayer()
        }
        if (serverId == 0L) {
            serverId = start()
        }
    }

    fun stopServer() {
        mainHandler.removeCallbacks(confirmStreamStopped)
        if (serverId != 0L) {
            stop(serverId)
        }
        serverId = 0L
        stopVideoPlayer()
        audioPlayer?.stopPlay()
        audioPlayer = null
        lastVideoActivityAtMs = 0L
        lastMediaPacketAtMs = 0L
        hasConnection = false
        hasRenderedVideo = false
    }

    fun setAcceptAudio(acceptAudio: Boolean) {
        this.acceptAudio = acceptAudio
        if (acceptAudio) {
            ensureAudioPlayer()
        } else {
            audioPlayer?.stopPlay()
            audioPlayer = null
        }
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
        audioVolume = volume.coerceIn(MIN_AUDIO_VOLUME, MAX_AUDIO_VOLUME)
        audioPlayer?.setVolume(audioVolume)
    }

    @Suppress("unused")
    fun isAudioAccepted(): Boolean = acceptAudio

    @Suppress("unused")
    fun getVideoWidth(): Int = videoWidth

    @Suppress("unused")
    fun getVideoHeight(): Int = videoHeight

    @Suppress("unused")
    fun onStreamStopped() {
        scheduleStreamStopCheck(STREAM_STOP_GRACE_MS)
    }

    val port: Int
        get() = if (serverId != 0L) getPort(serverId) else 0

    private external fun start(): Long
    private external fun stop(serverId: Long)
    private external fun getPort(serverId: Long): Int

    private fun markConnected() {
        lastMediaPacketAtMs = SystemClock.elapsedRealtime()
        scheduleStreamStopCheck(MEDIA_IDLE_STOP_MS)
        if (!hasConnection) {
            hasConnection = true
        }
    }

    private val confirmStreamStopped = Runnable {
        if (!hasConnection) {
            return@Runnable
        }
        val lastPacketAgeMs = SystemClock.elapsedRealtime() - lastMediaPacketAtMs
        if (lastPacketAgeMs >= streamStopThresholdMs) {
            onStreamStoppedCallback.invoke()
        }
    }

    private fun scheduleStreamStopCheck(thresholdMs: Long) {
        streamStopThresholdMs = thresholdMs
        mainHandler.removeCallbacks(confirmStreamStopped)
        mainHandler.postDelayed(confirmStreamStopped, thresholdMs)
    }

    private fun ensureAudioPlayer() {
        if (audioPlayer == null) {
            audioPlayer = AudioPlayer(audioVolume, onLatencySample).also { it.start() }
        } else {
            audioPlayer?.setVolume(audioVolume)
        }
    }

    @Synchronized
    private fun ensureVideoPlayer(): VideoPlayer? {
        val currentPlayer = videoPlayer
        if (currentPlayer != null) {
            return currentPlayer
        }
        if (!surfaceView.holder.surface.isValid) {
            return null
        }
        return VideoPlayer(
            surfaceView.holder.surface,
            videoWidth,
            videoHeight,
            onLatencySample,
            ::handleVideoFrameRendered
        )
            .also {
                videoPlayer = it
                it.start()
            }
    }

    @Synchronized
    private fun stopVideoPlayer() {
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
        if (hasRenderedVideo) {
            return
        }
        hasRenderedVideo = true
        onConnectionStarted()
    }

    private fun markVideoActivity(buffer: ByteBuffer, size: Int) {
        val now = SystemClock.elapsedRealtime()
        val resumedAfterIdle = lastVideoActivityAtMs == 0L || now - lastVideoActivityAtMs >= VIDEO_IDLE_WAKE_MS
        lastVideoActivityAtMs = now
        onVideoActivity(resumedAfterIdle || hasMajorVideoUpdate(buffer, size))
    }

    private fun hasMajorVideoUpdate(buffer: ByteBuffer, size: Int): Boolean {
        var index = 0
        while (index + 3 < size) {
            val startCodeLength = when {
                index + 3 < size &&
                    buffer.get(index) == START_CODE_BYTE &&
                    buffer.get(index + 1) == START_CODE_BYTE &&
                    buffer.get(index + 2) == START_CODE_ONE -> 3
                index + 4 < size &&
                    buffer.get(index) == START_CODE_BYTE &&
                    buffer.get(index + 1) == START_CODE_BYTE &&
                    buffer.get(index + 2) == START_CODE_BYTE &&
                    buffer.get(index + 3) == START_CODE_ONE -> 4
                else -> 0
            }

            if (startCodeLength > 0) {
                val nalHeaderIndex = index + startCodeLength
                if (nalHeaderIndex < size) {
                    when (buffer.get(nalHeaderIndex).toInt() and NAL_TYPE_MASK) {
                        NAL_TYPE_IDR, NAL_TYPE_SPS, NAL_TYPE_PPS -> return true
                    }
                }
                index = nalHeaderIndex + 1
            } else {
                index++
            }
        }

        return false
    }

    companion object {
        private const val TAG = "Receiver-RAOP"
        private const val DEBUG_FRAMES = false
        private const val MIN_AUDIO_VOLUME = 0.0f
        private const val MAX_AUDIO_VOLUME = 1.0f
        private const val VIDEO_RESTART_TIMEOUT_MS = 500L
        private const val STREAM_STOP_GRACE_MS = 5_000L
        private const val MEDIA_IDLE_STOP_MS = 20_000L
        private const val VIDEO_IDLE_WAKE_MS = 10_000L
        private const val NAL_TYPE_MASK = 0x1F
        private const val NAL_TYPE_IDR = 5
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
        private const val START_CODE_BYTE: Byte = 0
        private const val START_CODE_ONE: Byte = 1

        init {
            System.loadLibrary("raop_server")
            System.loadLibrary("play-lib")
        }
    }
}
