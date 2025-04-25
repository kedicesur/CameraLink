package com.example.cameralink.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.*
import android.util.Log
import android.view.Surface
import com.example.cameralink.ui.TemperatureProviderHolder
import java.nio.ByteBuffer

class H265Encoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    bitrate: Int,
    private val iFrameInterval: Int = 2
) {
    interface EncoderCallback {
        fun onOutputBufferAvailable(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onFormatChanged(format: MediaFormat)
        fun onEncoderError(errorCode: Int, errorMessage: String)
    }

    companion object {
        const val TAG = "H265Encoder"
        const val ERROR_CODEC_FAILURE = 1001
        const val ERROR_THREAD_FAILURE = 1002
        const val ERROR_BITRATE_ADJUSTMENT = 1003
    }

    private var codec: MediaCodec? = null
    private var callback: EncoderCallback? = null
    @Volatile private var isEncoding = false
    private var adaptiveFrameRate = frameRate
    private var currentBitrate = bitrate

    private var encodingThread: HandlerThread? = null
    private var encodingHandler: Handler? = null
    private var thermalHandler: Handler? = null
    private var frameCounter = 0

    private fun initializeCodec(surface: Surface?) {
        Log.d(TAG, "Initializing codec with surface: $surface")
        if (surface == null || !surface.isValid) {
            throw IllegalArgumentException("Invalid or null surface")
        }

        val format = createMediaFormat()
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            setInputSurface(surface)
            start()
        }
        Log.d(TAG, "Codec initialized successfully")
    }

    private fun createMediaFormat(): MediaFormat {
        Log.d(TAG, "Creating MediaFormat with width=$width, height=$height, bitrate=$currentBitrate")
        return MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        }
    }

    private fun startEncodingThread() {
        try {
            Log.d(TAG, "Starting encoding thread")
            encodingThread = HandlerThread("H265EncoderThread").apply {
                start()
                encodingHandler = Handler(looper)
            }
            encodingHandler?.post(encodingRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Encoding thread failed: ${e.message}")
            callback?.onEncoderError(ERROR_THREAD_FAILURE, "Encoding thread failed: ${e.message}")
        }
    }

    private val encodingRunnable = object : Runnable {
        override fun run() {
            try {
                Log.d(TAG, "EncodingRunnable running, isEncoding=$isEncoding, frameCounter=$frameCounter")
                if (!isEncoding) return

                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                val info = MediaCodec.BufferInfo()
                Log.d(TAG, "Processing output buffers")
                processOutputBuffers(info)
                frameCounter++

                // schedule next frame
                val delayMs = calculateFrameDelay()
                if (isEncoding) {
                    encodingHandler?.postDelayed(this, delayMs)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Encoding loop crashed: ${t.message}")
                callback?.onEncoderError(
                    ERROR_THREAD_FAILURE,
                    "Encoding loop crashed: ${t.message}"
                )
                stop()
            }
        }
    }

    private fun calculateFrameDelay(): Long {
        val safeRate = frameRate.coerceAtLeast(1)
        val delay = (1000L / safeRate)
        Log.d(TAG, "Calculated frame delay: $delay ms")
        return delay
    }

    private fun processOutputBuffers(bufferInfo: MediaCodec.BufferInfo) {
        try {
            Log.d(TAG, "processOutputBuffers called, codec=$codec")
            codec?.let { codec ->
                when (val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Output format changed")
                        handleFormatChange(codec.outputFormat)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Log.d(TAG, "No output buffer available, trying again later")
                    }
                    else -> {
                        Log.d(TAG, "Valid output buffer available at index: $index")
                        handleValidBuffer(index, bufferInfo)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Codec in illegal state: ${e.message}")
            handleEncoderError(ERROR_CODEC_FAILURE, "Codec in illegal state: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Encoding error: ${e.message}")
            handleEncoderError(ERROR_CODEC_FAILURE, "Encoding error: ${e.message}")
        }
    }

    private fun startThermalMonitoring() {
        Log.d(TAG, "Starting thermal monitoring")
        HandlerThread("ThermalMonitor").apply {
            start()
            thermalHandler = Handler(looper)
        }.also {
            thermalHandler?.postDelayed(thermalRunnable, 5000)
        }
    }

    private val thermalRunnable = object : Runnable {
        override fun run() {
            val temp = TemperatureProviderHolder.temperatureProvider.getDeviceTemperature()
            Log.d(TAG, "Device temperature: $temp")
            adaptiveFrameRate = when {
                temp > 70f -> (frameRate * 0.5).toInt()  // Critical
                temp > 60f -> (frameRate * 0.75).toInt() // High
                else -> frameRate                         // Normal
            }
            thermalHandler?.postDelayed(this, 5000)
        }
    }

    @Synchronized
    private fun cleanupResources() {
        Log.d(TAG, "Cleaning up resources")
        encodingHandler?.removeCallbacks(encodingRunnable)
        encodingThread?.quitSafely()
        thermalHandler?.removeCallbacks(thermalRunnable)

        codec?.apply {
            try {
                signalEndOfInputStream()
                flush()
                stop()
                release()
                Log.d(TAG, "Codec resources released")
            } catch (e: Exception) {
                Log.e(TAG, "Codec release error", e)
            }
        }

        encodingThread = null
        encodingHandler = null
        thermalHandler = null
        codec = null
    }

    private fun handleEncoderError(code: Int, message: String) {
        Log.e(TAG, "Encoder error: $message (code: $code)")
        callback?.onEncoderError(code, message)
        stop()
    }

    private fun isHevcSupported(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val supported = codecList.codecInfos.any {
            it.isEncoder &&
                    it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC)
        }
        Log.d(TAG, "HEVC support: $supported")
        return supported
    }

    private fun handleFormatChange(format: MediaFormat) {
        Log.d(TAG, "Format changed: $format")
        callback?.onFormatChanged(format)
    }

    private fun handleValidBuffer(index: Int, info: MediaCodec.BufferInfo) {
        if (index < 0) return

        codec?.getOutputBuffer(index)?.let { buffer ->
            try {
                Log.d(TAG, "Handling valid buffer at index: $index, size: ${info.size}")
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                callback?.onOutputBufferAvailable(buffer, info)
            } catch (e: Exception) {
                Log.e(TAG, "Buffer handling error", e)
            }
        }
        codec?.releaseOutputBuffer(index, false)
    }

    fun setCallback(callback: EncoderCallback) {
        Log.d(TAG, "Setting encoder callback")
        this.callback = callback
    }

    fun start(inputSurface: Surface?) {
        if (isEncoding) {
            Log.w(TAG, "Encoder is already running")
            return  // Prevent double start
        }
        if (!isHevcSupported()) {
            Log.e(TAG, "HEVC not supported")
            callback?.onEncoderError(ERROR_CODEC_FAILURE, "HEVC not supported")
            return
        }

        try {
            Log.d(TAG, "Starting encoder")
            initializeCodec(inputSurface)
            isEncoding = true
            startEncodingThread()
            startThermalMonitoring()
            Log.d(TAG, "Encoder started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Codec initialization failed: ${e.message}")
            callback?.onEncoderError(ERROR_CODEC_FAILURE, "Codec initialization failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        Log.d(TAG, "Stopping encoder")
        isEncoding = false
        encodingHandler?.removeCallbacks(encodingRunnable)
        cleanupResources()
        Log.d(TAG, "Encoder stopped")
    }
}