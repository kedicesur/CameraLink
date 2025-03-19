package com.example.cameralink.camera

import android.content.Context
import android.util.Log
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

    fun toggleCameraState() {
        cameraViewModel.toggleCameraEnabled() // UI/camera handled by observer
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
        if (cameraProvider != null) {
            cameraViewModel.toggleFrontCamera()
            bindCameraPreview(previewView)
        }
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
