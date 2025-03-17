package com.example.cameralink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.cameralink.camera.CameraOperations
import com.example.cameralink.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraOperations: CameraOperations
    // State keys
    private val STATE_CAMERA_ENABLED = "camera_enabled"
    private val STATE_FRONT_CAMERA = "front_camera"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleCameraState() else showPermissionWarning()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore state if available
        val isCameraEnabled = savedInstanceState?.getBoolean(STATE_CAMERA_ENABLED, false) ?: false
        val isFrontCamera = savedInstanceState?.getBoolean(STATE_FRONT_CAMERA, false) ?: false

        cameraOperations = CameraOperations(this, this).apply {
            this.isCameraEnabled = isCameraEnabled
            this.isFrontCamera = isFrontCamera
        }

        binding.powerButton.setOnClickListener { handlePowerButton() }
        binding.flipButton.setOnClickListener { handleFlipButton() }

        updateButtonStates()

        // Restart camera if it was enabled
        if (cameraOperations.isCameraEnabled) {
            cameraOperations.startCamera(binding.previewView)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save camera state
        outState.putBoolean(STATE_CAMERA_ENABLED, cameraOperations.isCameraEnabled)
        outState.putBoolean(STATE_FRONT_CAMERA, cameraOperations.isFrontCamera)
    }

    private fun handlePowerButton() {
        if (hasCameraPermission()) {
            toggleCameraState()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun handleFlipButton() {
        cameraOperations.switchCameraType(binding.previewView)
    }

    private fun toggleCameraState() {
        cameraOperations.toggleCameraState(binding.previewView)
        updateButtonStates()
    }

    private fun updateButtonStates() {
        binding.powerButton.isActivated = cameraOperations.isCameraEnabled
        binding.flipButton.isEnabled = cameraOperations.isCameraEnabled
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun showPermissionWarning() {
        Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraOperations.shutdown()
    }
}