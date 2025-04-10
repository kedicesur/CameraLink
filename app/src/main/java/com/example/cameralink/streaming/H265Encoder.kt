package com.example.cameralink.streaming

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.*
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class H265Encoder(
    private val context: Context,  // Add context to constructor
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
        const val ERROR_CODEC_FAILURE = 1001
        const val ERROR_THREAD_FAILURE = 1002
        const val ERROR_BITRATE_ADJUSTMENT = 1003
    }

    private var codec: MediaCodec? = null
    private var callback: EncoderCallback? = null
    private var isEncoding = false
    private var adaptiveFrameRate = frameRate
    private var currentBitrate = bitrate

    private var encodingThread: HandlerThread? = null
    private var encodingHandler: Handler? = null
    private var thermalHandler: Handler? = null
    private var frameCounter = 0

    private fun initializeCodec(surface: Surface) {
        val format = createMediaFormat()
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            setInputSurface(surface)
            start()
        }
    }

    private fun createMediaFormat(): MediaFormat {
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
            encodingThread = HandlerThread("H265EncoderThread").apply {
                start()
                encodingHandler = Handler(looper)
            }
            encodingHandler?.post(encodingRunnable)
        } catch (e: Exception) {
            callback?.onEncoderError(ERROR_THREAD_FAILURE, "Encoding thread failed: ${e.message}")
        }
    }

    private val encodingRunnable = object : Runnable {
        override fun run() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                val bufferInfo = MediaCodec.BufferInfo()

                while (isEncoding) {
                    processOutputBuffers(bufferInfo)
                    frameCounter++
                    if (isEncoding) encodingHandler?.postDelayed(this, calculateFrameDelay())
                }
            } catch (e: Exception) {
                callback?.onEncoderError(ERROR_THREAD_FAILURE, "Encoding loop crashed: ${e.message}")
                stop()
            }
        }
    }

    private fun calculateFrameDelay(): Long {
        val safeRate = frameRate.coerceAtLeast(1)
        return (1000L / safeRate)
    }

    private fun processOutputBuffers(bufferInfo: MediaCodec.BufferInfo) {
        try {
            codec?.let { codec ->
                when (val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        handleFormatChange(codec.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> handleValidBuffer(index, bufferInfo)
                }
            }
        } catch (e: IllegalStateException) {
            handleEncoderError(ERROR_CODEC_FAILURE, "Codec in illegal state: ${e.message}")
        } catch (e: Exception) {
            handleEncoderError(ERROR_CODEC_FAILURE, "Encoding error: ${e.message}")
        }
    }

    private fun startThermalMonitoring() {
        HandlerThread("ThermalMonitor").apply {
            start()
            thermalHandler = Handler(looper)
        }.also {
            thermalHandler?.postDelayed(thermalRunnable, 5000)
        }
    }

    private fun getDeviceTemperature(): Float {
        return try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            when (val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) {
                0 -> 35f  // Default if unavailable
                null -> 35f
                else -> temp / 10f
            }
        } catch (e: SecurityException) {
            Log.w("H265Encoder", "Battery access denied", e)
            35f
        } catch (e: Exception) {
            Log.e("H265Encoder", "Temperature read failed", e)
            35f
        }
    }

    private val thermalRunnable = object : Runnable {
        override fun run() {
            val temp = getDeviceTemperature()
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
        encodingHandler?.removeCallbacks(encodingRunnable)
        encodingThread?.quitSafely()
        thermalHandler?.removeCallbacks(thermalRunnable)

        codec?.apply {
            try {
                signalEndOfInputStream()
                flush()
                stop()
                release()
            } catch (e: Exception) {
                Log.e("H265Encoder", "Codec release error", e)
            }
        }

        encodingThread = null
        encodingHandler = null
        thermalHandler = null
        codec = null
    }

    private fun handleEncoderError(code: Int, message: String) {
        callback?.onEncoderError(code, message)
        stop()
    }

    private fun isHevcSupported(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos.any {
            it.isEncoder &&
                    it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC)
        }
    }

    private fun handleFormatChange(format: MediaFormat) { // Added missing function
        callback?.onFormatChanged(format)
    }

    private fun handleValidBuffer(index: Int, info: MediaCodec.BufferInfo) { // Added missing function
        if (index < 0) return

        codec?.getOutputBuffer(index)?.let { buffer ->
            try {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                callback?.onOutputBufferAvailable(buffer, info)
            } catch (e: Exception) {
                Log.e("H265Encoder", "Buffer handling error", e)
            }
        }
        codec?.releaseOutputBuffer(index, false)
    }

    fun setCallback(callback: EncoderCallback) {
        this.callback = callback
    }

    fun start(inputSurface: Surface) {
        if (!isHevcSupported()) {
            callback?.onEncoderError(ERROR_CODEC_FAILURE, "HEVC not supported")
            return
        }

        try {
            initializeCodec(inputSurface)
            startEncodingThread()
            startThermalMonitoring()
            isEncoding = true
        } catch (e: Exception) {
            callback?.onEncoderError(ERROR_CODEC_FAILURE, "Codec initialization failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        isEncoding = false
        cleanupResources()
    }
}