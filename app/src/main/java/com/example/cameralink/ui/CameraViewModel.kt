package com.example.cameralink.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StreamingPresets(
    val host: String = "192.168.1.24",
    val port: Int = 3684,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrate: Int = 4_000_000,
    val iFrameInterval: Int = 2
)

class CameraViewModel : ViewModel() {
    private val _isCameraEnabled = MutableStateFlow(false)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _isSrtActive = MutableStateFlow(false)
    val isSrtActive: StateFlow<Boolean> = _isSrtActive.asStateFlow()

    private val _wasSrtActive = MutableStateFlow(false)
    val wasSrtActive: StateFlow<Boolean> = _wasSrtActive.asStateFlow()

    private val _isConfigurationChanging = MutableStateFlow(false)
    val isConfigurationChanging: StateFlow<Boolean> = _isConfigurationChanging.asStateFlow()

    val streamingPresets = StreamingPresets()

    fun setCameraEnabled(enabled: Boolean) {
        _isCameraEnabled.value = enabled
    }

    fun setConfigurationChanging(changing: Boolean) {
        _isConfigurationChanging.value = changing
    }

    fun toggleCameraEnabled() {
        _isCameraEnabled.value = !_isCameraEnabled.value
    }

    fun toggleFrontCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
    }

    fun toggleSrtActive() {
        if (_isCameraEnabled.value) {
            _wasSrtActive.value = _isSrtActive.value
            _isSrtActive.value = !_isSrtActive.value
        }
    }

    fun resetSrtActive() {
            _wasSrtActive.value = false
            _isSrtActive.value = false
            println("resetSrtActive run: isActive: ${isSrtActive.value} and wasActive: ${wasSrtActive.value}")
    }
}