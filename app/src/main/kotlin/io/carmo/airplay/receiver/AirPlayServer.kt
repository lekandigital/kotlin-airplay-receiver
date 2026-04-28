package io.carmo.airplay.receiver

import android.util.Log
import java.io.IOException
import java.net.ServerSocket

class AirPlayServer {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun startServer() {
        if (serverSocket != null) {
            return
        }

        try {
            Log.d(TAG, "starting server")
            serverSocket = ServerSocket(0)
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val socket = serverSocket ?: return
        serverThread = Thread({
            try {
                socket.accept().use {
                    Log.d(TAG, "received accept")
                }
            } catch (e: IOException) {
                if (serverSocket != null) {
                    e.printStackTrace()
                }
            }
        }, "AirPlayServer").also { it.start() }
    }

    fun stopServer() {
        try {
            Log.d(TAG, "stopping server")
            serverSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            serverSocket = null
            serverThread = null
        }
    }

    companion object {
        private const val TAG = "Receiver-AirPlay"
    }
}
