package com.example.cameralink.camera

import android.content.Context
import android.util.Log
import android.view.View
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.cameralink.ui.CameraViewModel
import java.util.concurrent.Executors

class CameraOperations(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraViewModel: CameraViewModel // Pass ViewModel in constructor
) {
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    fun toggleCameraState(previewView: PreviewView) {
        val isEnabled = cameraViewModel.isCameraEnabled.value ?: false
        cameraViewModel.toggleCameraEnabled() // Update ViewModel state

        if (!isEnabled) {
            previewView.visibility = View.VISIBLE
            startCamera(previewView)
        } else {
            previewView.visibility = View.INVISIBLE
            shutdownCamera()
        }
    }

    private fun bindCameraPreview(previewView: PreviewView) {
        cameraProvider?.let { provider ->
            provider.unbindAll()

            val cameraSelector = if (cameraViewModel.isFrontCamera.value == true) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            try {
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
                )
                camera?.cameraControl?.setZoomRatio(1f) // Reset zoom on switch
            } catch (e: Exception) {
                Log.e("CameraOperations", "Failed to bind camera use cases", e)
            }
        }
    }

    fun switchCameraType(previewView: PreviewView) {
        cameraViewModel.toggleFrontCamera() // Update ViewModel state
        bindCameraPreview(previewView) // Restart preview with the new camera type
    }

    fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraPreview(previewView)
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

    fun shutdownCamera() {
        cameraProvider?.unbindAll()
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
