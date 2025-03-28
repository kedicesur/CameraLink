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
    private val cameraViewModel: CameraViewModel
) : AutoCloseable {
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var cameraState: CameraState = CameraState.IDLE
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private enum class CameraState {
        IDLE, INITIALIZING, READY
    }

    fun startCamera(previewView: PreviewView) {
        when (cameraState) {
            CameraState.INITIALIZING -> return
            CameraState.IDLE -> {
                cameraState = CameraState.INITIALIZING
                initializeCamera(previewView)
            }
            CameraState.READY -> switchCamera()
        }
    }

    private fun initializeCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                cameraState = CameraState.READY
                switchCamera()
            } catch (e: Exception) {
                Log.e("CameraOperations", "Failed to initialize camera", e)
                cameraViewModel.setCameraEnabled(false)
                cameraState = CameraState.IDLE
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        try {
            cameraProvider?.let { provider ->
                val cameraSelector = if (cameraViewModel.isFrontCamera.value) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                // Only unbind camera use cases, keeping provider and preview
                provider.unbindAll()

                preview?.let { preview ->
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview
                    )
                    camera?.cameraControl?.setZoomRatio(1f)
                }
            }
        } catch (e: Exception) {
            Log.e("CameraOperations", "Failed to switch camera", e)
            cameraViewModel.setCameraEnabled(false)
        }
    }

    fun adjustZoom(scaleFactor: Float) {
        camera?.let {
            val currentZoomRatio = it.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            val maxZoom = it.cameraInfo.zoomState.value?.maxZoomRatio ?: 5f
            val newZoomRatio = (currentZoomRatio * scaleFactor).coerceIn(1f, maxZoom)
            it.cameraControl.setZoomRatio(newZoomRatio)
        }
    }

    override fun close() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            camera = null
            preview = null
            cameraState = CameraState.IDLE
        } catch (e: Exception) {
            Log.e("CameraOperations", "Error during camera shutdown", e)
        } finally {
            try {
                cameraExecutor.shutdownNow() // Use shutdownNow for immediate termination
            } catch (e: Exception) {
                Log.e("CameraOperations", "Error during executor shutdown", e)
            }
        }
    }
}
