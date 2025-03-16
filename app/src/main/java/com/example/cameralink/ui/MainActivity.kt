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
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleCameraState() else showPermissionWarning()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraOperations = CameraOperations(this, this)

        binding.powerButton.setOnClickListener { handlePowerButton() }
        binding.flipButton.setOnClickListener { handleFlipButton() }

        updateButtonStates()
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