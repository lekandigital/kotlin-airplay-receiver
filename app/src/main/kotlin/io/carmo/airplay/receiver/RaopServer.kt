package io.carmo.airplay.receiver

import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.carmo.airplay.receiver.model.NALPacket
import io.carmo.airplay.receiver.model.PCMPacket
import io.carmo.airplay.receiver.player.AudioPlayer
import io.carmo.airplay.receiver.player.VideoPlayer
import java.nio.ByteBuffer

class RaopServer(
    private val surfaceView: SurfaceView,
    private val onConnectionStarted: () -> Unit,
    private val onVideoActivity: (Boolean) -> Unit,
    private val onTrafficSample: (Int) -> Unit,
    private val onLatencySample: (Long) -> Unit
) : SurfaceHolder.Callback {

    private var videoPlayer: VideoPlayer? = null
    private var audioPlayer: AudioPlayer? = AudioPlayer(onLatencySample).also { it.start() }
    private var serverId: Long = 0
    private var lastVideoActivityAtMs = 0L
    @Volatile private var hasConnection = false

    init {
        surfaceView.holder.addCallback(this)
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
        val player = videoPlayer
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (videoPlayer == null) {
            videoPlayer = VideoPlayer(holder.surface, onLatencySample).also { it.start() }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    fun startServer() {
        if (audioPlayer == null) {
            audioPlayer = AudioPlayer(onLatencySample).also { it.start() }
        }
        if (videoPlayer == null && surfaceView.holder.surface.isValid) {
            videoPlayer = VideoPlayer(surfaceView.holder.surface, onLatencySample).also { it.start() }
        }
        if (serverId == 0L) {
            serverId = start()
        }
    }

    fun stopServer() {
        if (serverId != 0L) {
            stop(serverId)
        }
        serverId = 0L
        videoPlayer?.stopDecode()
        videoPlayer = null
        audioPlayer?.stopPlay()
        audioPlayer = null
        lastVideoActivityAtMs = 0L
        hasConnection = false
    }

    val port: Int
        get() = if (serverId != 0L) getPort(serverId) else 0

    private external fun start(): Long
    private external fun stop(serverId: Long)
    private external fun getPort(serverId: Long): Int

    private fun markConnected() {
        if (!hasConnection) {
            hasConnection = true
            onConnectionStarted()
        }
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
