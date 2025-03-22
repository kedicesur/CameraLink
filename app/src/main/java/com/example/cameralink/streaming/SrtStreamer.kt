package com.example.cameralink.streaming

import java.nio.ByteBuffer
import android.media.MediaCodec
import android.util.Log

class SrtStreamer(private val host: String, private val port: Int) {
    private var socketId: Int = -1

    fun start() {
        if (SrtWrapper.srtInit() < 0) {
            Log.e("SRT", "Failed to initialize SRT")
            return
        }

        socketId = SrtWrapper.srtCreateSocket()
        if (socketId < 0) {
            Log.e("SRT", "Failed to create SRT socket")
            return
        }

        if (SrtWrapper.srtConnect(socketId, host, port) < 0) {
            Log.e("SRT", "Failed to connect to SRT server")
        } else {
            Log.i("SRT", "Connected to SRT server at $host:$port")
        }
    }

    fun sendData(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        data.get(bytes)
        if (SrtWrapper.srtSend(socketId, bytes, bytes.size) < 0) {
            Log.e("SRT", "Failed to send data over SRT")
        }
    }

    fun stop() {
        SrtWrapper.srtClose(socketId)
        SrtWrapper.srtCleanup()
    }
}
