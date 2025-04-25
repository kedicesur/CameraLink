package com.example.cameralink.streaming

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.nio.ByteBuffer

private const val TAG = "SrtStreamer"

class SrtStreamer {
    private var encoder: H265Encoder? = null
    private var socketId: Int = -1

    @Volatile
    private var isStreaming = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    private val reconnectDelay = listOf(1_000L, 2_000L, 4_000L)

    // A dedicated scope for reconnect/backoff logic, automatically cancelled on stop()
    private var reconnectScope: CoroutineScope? = null

    // Store host, port, and latency as properties
    private var host: String = ""
    private var port: Int = 0
    private var latency: Int = 0

    /**
     * Starts the SRT+HEVC pipeline.
     * Must be called from a coroutine (e.g. viewModelScope.launch).
     */
    suspend fun start(
        surface: Surface?,
        host: String,
        port: Int,
        latency: Int,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        iFrameInterval: Int
    ) = withContext(Dispatchers.IO) {
        require(!isStreaming) { "Already streaming" }
        require(bitrate in 500_000..50_000_000) { "Bitrate out of range" }
        require(iFrameInterval in 1..10)        { "Invalid GOP size" }

        // Save host, port, and latency
        this@SrtStreamer.host = host
        this@SrtStreamer.port = port
        this@SrtStreamer.latency = latency

        // 1) Init SRT socket
        if (!initSrt(host, port, latency)) {
            throw RuntimeException("Failed to connect SRT socket")
        }

        // 2) Init encoder
        encoder = H265Encoder(width, height, fps, bitrate, iFrameInterval)
            .apply {
                setCallback(object : H265Encoder.EncoderCallback {
                    override fun onOutputBufferAvailable(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                        Log.d(TAG, "onOutputBufferAvailable: size=${info.size}")
                        sendVideoData(buffer, info)
                    }
                    override fun onFormatChanged(format: MediaFormat) {
                        sendCodecHeaders(format)
                    }
                    override fun onEncoderError(code: Int, msg: String) {
                        Log.e(TAG, "Encoder error: $msg")
                        stop()  // safely tear down
                    }
                })
                start(surface)
            }

       isStreaming = true
        reconnectAttempts = 0

        // Prepare a reconnect scope for later if needed
        reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * Stops streaming, tears down encoder, sockets & coroutines.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
            if (!isStreaming) return@withContext
            isStreaming = false

        // 1) Stop encoder & release surface
        encoder?.stop()
        encoder = null

        // 2) Cancel any pending reconnect attempts
        reconnectScope?.cancel()
        reconnectScope = null

        // 3) Close SRT socket and clean up srt library
        if (socketId >= 0) {
            SrtWrapper.srtClose(socketId)
            socketId = -1
            SrtWrapper.srtCleanup()
        }

        reconnectAttempts = 0
    }

    private fun initSrt(host: String, port: Int, latency: Int): Boolean {
        Log.i(TAG, "Initializing SRT socket with host: $host, port: $port, latency: $latency")
        try {
            if (SrtWrapper.srtInit() != 0) {
                Log.e(TAG, "Failed to initialize SRT library")
                return false
            }
            socketId = SrtWrapper.srtCreateSocket(latency)
            Log.i(TAG, "SRT socket ID: $socketId")
            if (socketId < 0) {
                Log.e(TAG, "Failed to create SRT socket")
                return false
            }
            if (SrtWrapper.srtConnect(socketId, host, port) < 0) {
                Log.e(TAG, "Failed to connect SRT socket to $host:$port")
                SrtWrapper.srtClose(socketId)
                socketId = -1
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "SRT initialization error: ${e.message}")
            if (socketId >= 0) SrtWrapper.srtClose(socketId)
            return false
        } finally {
            if (socketId < 0) {
                SrtWrapper.srtCleanup()
            }
        }
    }

    private fun sendVideoData(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!isStreaming || socketId < 0) return
        try {
            if (info.size > 0) {
                val data = ByteArray(info.size)
                synchronized(buffer) {
                    buffer.get(data, info.offset, info.size)
                }
                val result = SrtWrapper.srtSend(socketId, data, data.size)
                if (result < 0) handleSendError()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending video data: ${e.message}")
        }
    }

    private fun handleSendError() {
        if (reconnectScope?.isActive != true) return // Ensure scope is active
        if (reconnectAttempts++ >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnect attempts reached. Stopping streaming.")
            reconnectScope?.launch {
                stop()
            }
            return
        } else {
            val delayMs = reconnectDelay.getOrElse(reconnectAttempts - 1) { 4_000L }
            reconnectScope?.launch {
                delay(delayMs)
                val reconnected = synchronized(this@SrtStreamer) {
                    if (socketId >= 0) SrtWrapper.srtClose(socketId)
                    initSrt(host, port, latency)
                }
                if (!reconnected){
                    Log.e(TAG, "Error sending data. Attempt $reconnectAttempts of $maxReconnectAttempts")
                    handleSendError()
                }
            }
        }
    }

    private fun sendCodecHeaders(format: MediaFormat) {
        listOf("csd-0", "csd-1", "csd-2").forEach { key ->
            format.getByteBuffer(key)?.let { buf ->
                val hdr = ByteArray(buf.remaining()).also { buf.get(it) }
                val result = SrtWrapper.srtSend(socketId, hdr, hdr.size)
                if (result < 0) {
                    Log.e(TAG, "Failed to send codec header $key. Error code: $result")
                    handleSendError()
                }
            }
        }
    }
}
