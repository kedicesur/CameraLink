package com.example.cameralink.streaming

object SrtWrapper {
    init {
        System.loadLibrary("srt_wrapper")
    }

    external fun srtInit(): Int
    external fun srtCreateSocket(): Int
    external fun srtConnect(socket: Int, host: String, port: Int): Int
    external fun srtSend(socket: Int, data: ByteArray, size: Int): Int
    external fun srtClose(socket: Int): Int
    external fun srtCleanup(): Int
}
