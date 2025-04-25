package com.example.cameralink.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.app.ActivityCompat
import com.example.cameralink.ui.CameraViewModel
import java.util.concurrent.Executors

private const val TAG = "CameraOperations"
private const val PREVIEW_WIDTH = 1920
private const val PREVIEW_HEIGHT = 1080


class CameraOperations(
    private val context: Context,
    private val cameraViewModel: CameraViewModel
) : AutoCloseable {

    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val zoomManager = ZoomManager()
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    private var currentSurfaceHolder: SurfaceHolder? = null
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            currentSurfaceHolder = holder
            handleValidSurface()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "Surface destroyed")
            previewSurface = null
            closeSession()
        }
    }

    private var backgroundThread = HandlerThread("CameraBackgroundThread").apply {
        setUncaughtExceptionHandler { _, e ->
            Log.e(TAG, "Camera background thread crashed", e)
        }
        start()
    }
    private var backgroundHandler = Handler(backgroundThread.looper)
    private var cameraExecutor = Executors.newSingleThreadExecutor()
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            try {
                val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
                zoomManager.initializeZoom(characteristics)
            } catch (_: Exception) {}
            createCaptureSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            cameraDevice = null
        }
    }

    private var currentCameraId: String = getDefaultCameraId(cameraViewModel.isFrontCamera.value)

    private enum class SessionType {
        PREVIEW_ONLY,
        PREVIEW_AND_ENCODER
    }
    private var currentSessionType: SessionType = SessionType.PREVIEW_ONLY

    private fun startOrUpdateSession() {
        if (previewSurface == null) return
        if (cameraDevice == null) {
            Log.d(TAG, "Camera device is null, opening camera")
            openCamera()
        } else {
            Log.d(TAG, "Camera device is not null, reconfiguring session")
            captureSession?.close()
            captureSession = null
            createCaptureSession()
        }
    }

    private fun openCamera() {
        synchronized(this) {
            if (!backgroundThread.isAlive) {
                backgroundThread.quitSafely()
                backgroundThread = HandlerThread("CameraBackgroundThread").apply {
                    start()
                    looper?.let {
                        backgroundHandler = Handler(it)
                    } ?: throw IllegalStateException("HandlerThread looper is null")
                }
            }
            if (cameraExecutor.isShutdown) {
                cameraExecutor = Executors.newSingleThreadExecutor()
            }
        }

        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) return
            Log.d(TAG, "Current Camera ID: $currentCameraId. Opening camera")
            cameraManager.openCamera(currentCameraId, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
        }
    }

    private fun createCaptureSession() {
        val surfaces = mutableListOf<Surface>()
        previewSurface?.let { surfaces.add(it) }
        if (currentSessionType == SessionType.PREVIEW_AND_ENCODER) {
            if (encoderSurface == null || !encoderSurface!!.isValid) {
                Log.e(TAG, "Encoder surface is null or invalid")
                return
            }
            encoderSurface?.let { surfaces.add(it) }
        }
        if (surfaces.isEmpty()) return

        try {
            val camera = cameraDevice ?: return
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "CameraCaptureSession configured")
                    captureSession = session
                    startPreviewRequest()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.d(TAG, "CameraCaptureSession configuration failed")
                }
            }

            val outputConfigs = surfaces.map { OutputConfiguration(it) }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                cameraExecutor,
                sessionCallback
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    private fun startPreviewRequest() {
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
            val (newZoom, cropRegion) = zoomManager.adjustZoom(1f, characteristics)

            previewSurface?.let { builder?.addTarget(it) }
            if (currentSessionType == SessionType.PREVIEW_AND_ENCODER) {
                encoderSurface?.let { builder?.addTarget(it) }
            }
            builder?.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
            builder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val request = builder?.build() ?: return
            captureSession?.setRepeatingRequest(request, null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.d(TAG, "Error starting preview request", e)
        }
    }

    private fun getDefaultCameraId(useFront: Boolean): String {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (useFront) {
                lensFacing == CameraCharacteristics.LENS_FACING_FRONT
            } else {
                lensFacing == CameraCharacteristics.LENS_FACING_BACK
            }
        } ?: throw IllegalStateException("No camera found with desired facing")
    }

    private fun handleValidSurface() {
        previewSurface = currentSurfaceHolder?.surface
        currentSurfaceHolder?.setFixedSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
        startOrUpdateSession()
    }

    private fun closeSession() {
        captureSession?.close()
        captureSession = null

    }

    private fun closeDevice() {
        closeSession()
        cameraDevice?.close()
        cameraDevice = null
    }

    fun setEncoderSurface(surface: Surface?) {
        encoderSurface?.release()
        encoderSurface = surface
        currentSessionType = SessionType.PREVIEW_AND_ENCODER
        startOrUpdateSession()
    }

    fun clearEncoderSurface() {
        encoderSurface?.release()
        encoderSurface = null
        currentSessionType = SessionType.PREVIEW_ONLY
        startOrUpdateSession()
    }

    fun adjustZoom(scaleFactor: Float) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
            val (newZoom, cropRegion) = zoomManager.adjustZoom(scaleFactor, characteristics)
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder?.addTarget(it) }
            if (currentSessionType == SessionType.PREVIEW_AND_ENCODER) {
                encoderSurface?.let { builder?.addTarget(it) }
            }
            if (cropRegion != null) {
                Log.d(TAG, "Setting crop region: $cropRegion")
                builder?.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
            }
            builder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val request = builder?.build() ?: return
            captureSession?.setRepeatingRequest(request, null, backgroundHandler)
        } catch (_: Exception) {}
    }

    fun startCamera(surfaceHolder: SurfaceHolder) {
        Log.d(TAG, "Starting camera with surface holder $surfaceHolder")
        currentSurfaceHolder = surfaceHolder
        if (currentSurfaceHolder?.surface?.isValid == true) handleValidSurface()
        else currentSurfaceHolder?.addCallback(surfaceCallback)
    }

    fun switchCamera() {
        closeDevice()
        currentCameraId = getDefaultCameraId(cameraViewModel.isFrontCamera.value)
        openCamera()
    }
    @Synchronized
    override fun close() {
        closeDevice()
        encoderSurface?.release()
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error shutting down background thread", e)
        }
        currentSurfaceHolder?.removeCallback(surfaceCallback)
        previewSurface = null
        Log.d(TAG, "Closing CameraOperations and cameraDevice is $cameraDevice")
    }
}