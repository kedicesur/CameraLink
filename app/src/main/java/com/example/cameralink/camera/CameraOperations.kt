package com.example.cameralink.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class CameraOperations(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    var isCameraEnabled = false
    var isFrontCamera = false

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    fun toggleCameraState(previewView: PreviewView) {
        isCameraEnabled = !isCameraEnabled
        if (isCameraEnabled) startCamera(previewView) else {
            shutdownCamera()
            shutdown()
        }
    }

    fun switchCameraType(previewView: PreviewView) {
        isFrontCamera = !isFrontCamera
        startCamera(previewView)
    }

    fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            cameraProvider?.unbindAll() // Ensure previous session is closed

            val cameraSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            try {
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
                )
            } catch (e: Exception) {
                Log.e("CameraOperations", "Camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun adjustZoom(scaleFactor: Float) {
        camera?.let {
            val currentZoomRatio = it.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            val maxZoom = it.cameraInfo.zoomState.value?.maxZoomRatio ?: 5f
            val newZoomRatio = (currentZoomRatio * scaleFactor).coerceIn(1f, maxZoom)
            it.cameraControl.setZoomRatio(newZoomRatio)
        }
    }

    private fun shutdownCamera() {
        cameraProvider?.unbindAll()
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}