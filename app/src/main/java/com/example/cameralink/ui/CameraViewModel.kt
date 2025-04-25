package com.example.cameralink.ui

import android.util.Log
import android.view.Surface
import android.media.MediaCodec
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.cameralink.camera.CameraOperations
import com.example.cameralink.streaming.SrtStreamer

/**
 * Represents the current status of the SRT streaming pipeline.
 */
sealed class StreamStatus {
    object Idle : StreamStatus()
    object Connecting : StreamStatus()
    object Running : StreamStatus()
    data class Error(val exception: Throwable) : StreamStatus()
}

/**
 * Holds all user‐configurable SRT/video parameters.
 */
data class StreamingPresets(
    val host: String = "192.168.1.24",
    val port: Int = 3684,
    val latency: Int = 200,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrate: Int = 1_000_000,
    val iFrameInterval: Int = 2
)

class CameraViewModel(
    private val srtStreamer: SrtStreamer = SrtStreamer()
) : ViewModel() {

    private var cameraOperations: CameraOperations? = null

    // Camera state
    private val _isCameraEnabled = MutableStateFlow(false)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _wasCameraEnabled = MutableStateFlow(false)
    val wasCameraEnabled: StateFlow<Boolean> = _wasCameraEnabled.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    // SRT toggle state and message
    private val _isSrtActive = MutableStateFlow(false)
    val isSrtActive: StateFlow<Boolean> = _isSrtActive.asStateFlow()

    private val _wasSrtActive = MutableStateFlow(false)
    val wasSrtActive: StateFlow<Boolean> = _wasSrtActive.asStateFlow()

    private val _srtToastMessage = MutableStateFlow<String?>(null)
    val srtToastMessage: StateFlow<String?> = _srtToastMessage.asStateFlow()

    // Streaming status for UI
    private val _streamStatus = MutableStateFlow<StreamStatus>(StreamStatus.Idle)
    val streamStatus: StateFlow<StreamStatus> = _streamStatus.asStateFlow()

    // Current presets
    val streamingPresets = StreamingPresets()

    // Keep a reference for eventual release
    private var encoderSurface: Surface? = null

    /**
     * Toggles SRT streaming on/off. Caller must supply both Context and Surface.
     */
    fun toggleSrtActive() {
        _wasSrtActive.value = _isSrtActive.value  // access isSrtActive.value only once
        val shouldStart = !_wasSrtActive.value
        _isSrtActive.value = shouldStart

        if (shouldStart) {
            if (encoderSurface == null) {
                encoderSurface = MediaCodec.createPersistentInputSurface()
            }
            startStreaming(encoderSurface)
            _srtToastMessage.value = "Activating SRT streaming"
        } else {
            stopStreaming()
            _srtToastMessage.value = "Finalizing SRT streaming"
        }
    }

    /**
     * Resets SRT state.
     */
    fun resetSrtActive() {
        _wasSrtActive.value = false
        _isSrtActive.value = false
        _streamStatus.value = StreamStatus.Idle
        encoderSurface?.release()
        encoderSurface = null
        Log.d("CameraViewModel", "SRT reset: isActive=${_isSrtActive.value}")
    }

    /**
     * Launches the SRT start sequence on IO dispatcher.
     */
    private fun startStreaming(surface: Surface?) {
        viewModelScope.launch(Dispatchers.IO) {
            _streamStatus.value = StreamStatus.Connecting
            try {
                val p = streamingPresets
                srtStreamer.start(
                    surface = surface,
                    host = p.host,
                    port = p.port,
                    latency = p.latency,
                    width = p.width,
                    height = p.height,
                    fps = p.fps,
                    bitrate = p.bitrate,
                    iFrameInterval = p.iFrameInterval
                )
                _streamStatus.value = StreamStatus.Running
                cameraOperations?.setEncoderSurface(surface)
            } catch (e: Exception) {
                Log.e("CameraViewModel", "SRT start failed", e)
                _streamStatus.value = StreamStatus.Error(e)
                _isSrtActive.value = false
                cameraOperations?.clearEncoderSurface()
                encoderSurface?.release()
                encoderSurface = null
            }
        }
    }

    /**
     * Launches the SRT stop sequence on IO dispatcher.
     */
    private fun stopStreaming() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                srtStreamer.stop()
            } catch (e: Exception) {
                Log.e("CameraViewModel", "SRT stop failed", e)
            } finally {
                _streamStatus.value = StreamStatus.Idle
                cameraOperations?.clearEncoderSurface()
                encoderSurface?.release()
                encoderSurface = null
            }
        }
    }

    // Camera enable/disable
    fun setCameraEnabled(isEnabled: Boolean) {
        _wasCameraEnabled.value = _isCameraEnabled.value
        _isCameraEnabled.value = isEnabled
    }

    fun toggleCameraEnabled() {
        _wasCameraEnabled.value = _isCameraEnabled.value
        _isCameraEnabled.value = !_isCameraEnabled.value
    }

    fun toggleFrontCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
    }

    fun setCameraOperations(ops: CameraOperations) {
        cameraOperations = ops
    }
}
