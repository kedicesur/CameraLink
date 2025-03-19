package com.example.cameralink.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class CameraViewModel : ViewModel() {
    private val _isCameraEnabled = MutableLiveData(false)
    val isCameraEnabled: LiveData<Boolean> = _isCameraEnabled

    private val _isFrontCamera = MutableLiveData(false)
    val isFrontCamera: LiveData<Boolean> = _isFrontCamera
/*
    fun setCameraEnabled(enabled: Boolean) {
        _isCameraEnabled.value = enabled
    }
*/
    fun toggleCameraEnabled() {
        _isCameraEnabled.value = _isCameraEnabled.value != true
    }
/*
    fun setFrontCamera(front: Boolean) {
        _isFrontCamera.value = front
    }
*/
    fun toggleFrontCamera() {
        _isFrontCamera.value = _isFrontCamera.value != true
    }
}
