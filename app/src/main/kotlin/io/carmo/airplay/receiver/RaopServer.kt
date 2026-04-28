package io.carmo.airplay.receiver

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.carmo.airplay.receiver.model.NALPacket
import io.carmo.airplay.receiver.model.PCMPacket
import io.carmo.airplay.receiver.player.AudioPlayer
import io.carmo.airplay.receiver.player.VideoPlayer

class RaopServer(private val surfaceView: SurfaceView) : SurfaceHolder.Callback {

    private var videoPlayer: VideoPlayer? = null
    private var audioPlayer: AudioPlayer? = AudioPlayer().also { it.start() }
    private var serverId: Long = 0

    init {
        surfaceView.holder.addCallback(this)
    }

    @Suppress("unused")
    fun onRecvVideoData(nal: ByteArray, nalType: Int, dts: Long, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvVideoData dts = $dts, pts = $pts, nalType = $nalType, nal length = ${nal.size}")
        }
        videoPlayer?.addPacket(NALPacket(nalData = nal, nalType = nalType, pts = pts, dts = dts))
    }

    @Suppress("unused")
    fun onRecvAudioData(pcm: ShortArray, pts: Long) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "onRecvAudioData pcm length = ${pcm.size}, pts = $pts")
        }
        audioPlayer?.addPacket(PCMPacket(data = pcm, pts = pts))
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
    }

    val port: Int
        get() = if (serverId != 0L) getPort(serverId) else 0

    private external fun start(): Long
    private external fun stop(serverId: Long)
    private external fun getPort(serverId: Long): Int

    companion object {
        private const val TAG = "Receiver-RAOP"
        private const val DEBUG_FRAMES = false

        init {
            System.loadLibrary("raop_server")
            System.loadLibrary("play-lib")
        }
    }
}
