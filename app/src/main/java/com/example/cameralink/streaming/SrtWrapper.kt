package com.example.cameralink.streaming

object SrtWrapper {
    init {
        System.loadLibrary("srt_wrapper")
    }

    class SrtException(message: String) : RuntimeException(message)

    external fun srtInit(): Int
    @Throws(SrtException::class)
    external fun srtCreateSocket(latency: Int): Int
    @Throws(SrtException::class)
    external fun srtConnect(socket: Int, host: String, port: Int): Int
    @Throws(SrtException::class)
    external fun srtSend(socket: Int, data: ByteArray, size: Int): Int
    external fun srtClose(socket: Int): Int
    external fun srtCleanup(): Int
}
