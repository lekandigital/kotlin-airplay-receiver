package io.carmo.airplay.receiver

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
    private val onConnectionStarted: () -> Unit
) : SurfaceHolder.Callback {

    private var videoPlayer: VideoPlayer? = null
    private var audioPlayer: AudioPlayer? = AudioPlayer().also { it.start() }
    private var serverId: Long = 0
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
        val packet = NALPacket(data = buffer, size = size, nativePointer = nativePointer, nalType = nalType, pts = pts, dts = dts)
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
        val packet = PCMPacket(data = buffer, size = size, nativePointer = nativePointer, pts = pts)
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
            videoPlayer = VideoPlayer(holder.surface).also { it.start() }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    fun startServer() {
        if (audioPlayer == null) {
            audioPlayer = AudioPlayer().also { it.start() }
        }
        if (videoPlayer == null && surfaceView.holder.surface.isValid) {
            videoPlayer = VideoPlayer(surfaceView.holder.surface).also { it.start() }
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

    companion object {
        private const val TAG = "Receiver-RAOP"
        private const val DEBUG_FRAMES = false

        init {
            System.loadLibrary("raop_server")
            System.loadLibrary("play-lib")
        }
    }
}
