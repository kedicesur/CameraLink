package com.example.cameralink.streaming

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class SrtStreamer(
    private val context: Context
) {
    private var encoder: H265Encoder? = null
    private var socketId: Int = -1
    private var currentHost: String = "192.168.1.24"
    private var currentPort: Int = 3684
    private var isStreaming = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    private var bytesSent: Long = 0
    private var framesSent: Long = 0
    private val reconnectDelay = longArrayOf(1000, 2000, 4000) // Exponential backoff
    private var reconnectScope: CoroutineScope? = null
    private var currentReconnectDelay = 0

    private fun initializeSrtSocket(): Boolean {
        return try {
            val initResult = SrtWrapper.srtInit()
            if (initResult != 0) throw Exception("SRT initialization failed")

            socketId = SrtWrapper.srtCreateSocket()

            val result = SrtWrapper.srtConnect(socketId,currentHost,currentPort)
            if (result < 0) throw RuntimeException("SRT failed to connect in Caller mode")
            Log.i("SRT", "Socket connected successfully")
            true
        } catch (e: Exception) {
            Log.e("SRT", "Socket error: ${e.message}")
            SrtWrapper.srtClose(socketId)
            socketId = -1
            false
        }
    }

    private fun initializeEncoder(
        surface: Surface,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        iFrameInterval: Int
    ) {
        encoder = H265Encoder(
            context = context,
            width = width,
            height = height,
            frameRate = fps,
            bitrate = bitrate,
            iFrameInterval = iFrameInterval
        ).apply {
            setCallback(object : H265Encoder.EncoderCallback {
                override fun onOutputBufferAvailable(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                    sendVideoData(buffer, info)
                }

                override fun onFormatChanged(format: MediaFormat) {
                    sendCodecHeaders(format)
                }

                override fun onEncoderError(errorCode: Int, errorMessage: String) {
                    handleEncoderError(errorCode, errorMessage)
                }
            })
            start(surface)
        }
    }

    private fun sendVideoData(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!isStreaming || socketId < 0) return

        try {
            buffer.apply {
                position(info.offset)
                limit(info.offset + info.size)
            }

            val data = ByteArray(info.size).apply {
                buffer.get(this, 0, info.size)
            }

            val sent = SrtWrapper.srtSend(socketId, data, data.size)
            if (sent < 0) handleSendError() // Only handle complete failures
            else {
                bytesSent += data.size // Count as sent, let SRT handle retries
                framesSent++
            }
        } catch (e: Exception) {
            Log.e("SRT", "Send error: ${e.message}")
            handleSendError()
        }
    }

    private fun handleSendError() {
        if (reconnectAttempts++ < maxReconnectAttempts) {
            Log.i("SRT", "Reconnecting attempt $reconnectAttempts/$maxReconnectAttempts")
            reconnectSocket()
        } else {
            stop()
            Log.e("SRT", "Permanent send failure")
        }
    }

    private fun reconnectSocket() {
        if (socketId >= 0) {
            SrtWrapper.srtClose(socketId)
            socketId = -1
        }

        reconnectScope?.launch {
            if (currentReconnectDelay >= reconnectDelay.size) {
                Log.e("SRT", "Max reconnect attempts reached")
                return@launch
            }

            delay(reconnectDelay[currentReconnectDelay])
            currentReconnectDelay++

            if (initializeSrtSocket()) {
                currentReconnectDelay = 0
                Log.i("SRT", "Reconnected successfully")
            }
        }
    }

    private fun sendCodecHeaders(format: MediaFormat) {
        if (!isStreaming) return

        // HEVC requires VPS(csd-0), SPS(csd-1), PPS(csd-2)
        listOf("csd-0", "csd-1", "csd-2").forEach { key ->
            format.getByteBuffer(key)?.let { buffer ->
                try {
                    val headerData = ByteArray(buffer.remaining()).apply {
                        buffer.get(this)
                    }
                    if (SrtWrapper.srtSend(socketId, headerData, headerData.size) == -1) throw Exception("SRT send failed")
                } catch (e: Exception) {
                    Log.e("SRT", "Header send error: ${e.message}")
                }
            }
        }
    }

    private fun handleEncoderError(errorCode: Int, errorMessage: String) {
        Log.e("ENCODER", "Error $errorCode: $errorMessage")
        stop() // Simply stop on any encoder error
    }

    fun start(
        surface: Surface,
        host: String,
        port: Int,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int = 2_000_000,
        iFrameInterval: Int
    ) {
        require(bitrate in 500_000..50_000_000) { "Invalid bitrate" }
        require(iFrameInterval in 1..10) { "Invalid GOP size" }
        Log.i("SRT", "Starting stream with params:")
        Log.i("SRT", "Resolution: ${width}x$height")
        Log.i("SRT", "Frame rate: $fps fps")
        Log.i("SRT", "Bitrate: ${bitrate/1_000_000} Mbps")
        if (isStreaming) {
            Log.w("SRT", "Streaming already active")
            return
        }

        reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        currentHost = host
        currentPort = port

        if (!initializeSrtSocket()) return

        initializeEncoder(surface, width, height, fps, bitrate, iFrameInterval)
        isStreaming = true
    }

    fun stop() {
        isStreaming = false // Set first to prevent new data
        encoder?.stop()
        encoder = null
        reconnectScope?.cancel()
        reconnectScope = null

        if (socketId >= 0) {
            SrtWrapper.srtClose(socketId)
            socketId = -1
        }

        bytesSent = 0
        framesSent = 0
        reconnectAttempts = 0
        currentReconnectDelay = 0

        try {
            SrtWrapper.srtCleanup()
        } catch (e: Exception) {
            Log.e("SRT", "Cleanup error: ${e.message}")
        }
    }

    fun getStreamStats() = "Sent: $framesSent frames (${bytesSent/1024}KB)"
}