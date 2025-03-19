package com.example.cameralink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.cameralink.camera.CameraOperations
import com.example.cameralink.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    // ViewModel instance
    private val cameraViewModel: CameraViewModel by viewModels()

    private lateinit var cameraOperations: CameraOperations

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCameraIfEnabled()
        } else {
            showPermissionWarning()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraOperations = CameraOperations(this, this, cameraViewModel)

        binding.powerButton.setOnClickListener { cameraOperations.toggleCameraState(binding.previewView) }
        binding.flipButton.setOnClickListener { handleFlipButton() }

        setupGestureDetector()
        observeViewModel()
        updateButtonStates()

        if (!hasCameraPermission()) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun handleFlipButton() {
        if (cameraViewModel.isCameraEnabled.value == true) {
            cameraOperations.switchCameraType(binding.previewView)
        }
    }

    private fun observeViewModel() {
        cameraViewModel.isCameraEnabled.observe(this) { isEnabled ->
            if (isEnabled) {
                binding.previewView.visibility = View.VISIBLE
                cameraOperations.startCamera(binding.previewView)
            } else {
                binding.previewView.visibility = View.INVISIBLE
                cameraOperations.shutdownCamera()
            }
            updateButtonStates()
        }
    }

    private fun startCameraIfEnabled() {
        if (cameraViewModel.isCameraEnabled.value == true) {
            binding.previewView.visibility = View.VISIBLE
            cameraOperations.startCamera(binding.previewView)
        }
    }

    private fun setupGestureDetector() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                val zoomFactor = 1f + distanceY / 500f // Adjust zoom based on swipe direction
                cameraOperations.adjustZoom(zoomFactor)
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })

        binding.previewView.setOnTouchListener { view, event ->
            if (gestureDetector.onTouchEvent(event)) {
                return@setOnTouchListener true
            }

            if (event.action == MotionEvent.ACTION_UP) {
                view.performClick() // Fix linter warning & accessibility issue
            }
            false
        }
    }


    private fun updateButtonStates() {
        runOnUiThread {
            binding.powerButton.isActivated = cameraViewModel.isCameraEnabled.value == true
            binding.flipButton.isEnabled = cameraViewModel.isCameraEnabled.value == true
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionWarning() {
        Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraOperations.shutdown()
    }
}
