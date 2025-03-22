package com.example.cameralink.streaming

object SrtWrapper {
    init {
        System.loadLibrary("srt_wrapper")
    }

    external fun init(): Int
    external fun cleanup(): Int
}
