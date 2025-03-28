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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.example.cameralink.camera.CameraOperations
import com.example.cameralink.databinding.ActivityMainBinding
import com.example.cameralink.streaming.SrtWrapper

class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    private val cameraViewModel: CameraViewModel by viewModels()

    private val cameraOperations by lazy {
        CameraOperations(this, this, cameraViewModel)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCameraIfEnabled()
        } else {
            cameraViewModel.setCameraEnabled(false)
            showPermissionWarning()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupStateCollectors()
        checkCameraPermission()
    }

    override fun onPause() {
        super.onPause()
        if (isChangingConfigurations) {
            cameraViewModel.setConfigurationChanging(true)
        }
    }

    override fun onResume() {
        super.onResume()
        cameraViewModel.setConfigurationChanging(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraOperations.close()
        _binding = null
    }

    private fun setupUI() {
        with(binding) {
            powerButton.setOnClickListener { cameraViewModel.toggleCameraEnabled() }
            flipButton.setOnClickListener { cameraViewModel.toggleFrontCamera() }
            srtButton.setOnClickListener { cameraViewModel.toggleSrtActive() }
        }
        setupGestureDetector()
    }

    private fun setupStateCollectors() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    cameraViewModel.isCameraEnabled.collect { isEnabled ->
                        when (isEnabled) {
                            true -> {
                                binding.previewView.visibility = View.VISIBLE
                                cameraOperations.startCamera(binding.previewView)
                            }
                            false -> {
                                println("isCameraEnabled collector triggered with isSrtActive: ${cameraViewModel.isSrtActive.value} and wasSrtActive: ${cameraViewModel.wasSrtActive.value}")
                                binding.previewView.visibility = View.INVISIBLE
                                cameraOperations.close()
                                if (cameraViewModel.isSrtActive.value) {
                                    cameraViewModel.resetSrtActive()
                                    handleSrtState(false)
                                }
                            }
                        }
                        updateButtonStates()
                    }
                }

                launch {
                    cameraViewModel.isFrontCamera.collect { _ ->
                        if (cameraViewModel.isCameraEnabled.value) {
                            cameraOperations.switchCamera()
                        }
                    }
                }

                launch {
                    cameraViewModel.isSrtActive.collect { isActive ->
                        println("isSrtActive collector triggered: isActive: $isActive and wasActive: ${cameraViewModel.wasSrtActive.value}")
                        if (!cameraViewModel.isConfigurationChanging.value && (isActive xor cameraViewModel.wasSrtActive.value)) {
                            handleSrtState(isActive)
                        }
                        // cameraViewModel.setConfigurationChanging(false)
                        binding.srtButton.isActivated = isActive
                        updateButtonStates()
                    }
                }
            }
        }
    }

    private fun handleSrtState(isEnabled: Boolean) {
        when (isEnabled) {
            true -> {
                val result = SrtWrapper.srtInit()
                Toast.makeText(this, "SRT Started: $result", Toast.LENGTH_SHORT).show()
            }
            false -> {
                SrtWrapper.srtClose(0)
                SrtWrapper.srtCleanup()
                Toast.makeText(this, "SRT Stopped", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkCameraPermission() {
        if (!hasCameraPermission()) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun startCameraIfEnabled() {
        if (cameraViewModel.isCameraEnabled.value) {
            binding.previewView.visibility = View.VISIBLE
            cameraOperations.startCamera(binding.previewView)
        }
    }

    private fun setupGestureDetector() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null || !cameraViewModel.isCameraEnabled.value) return false
                val sensitivity = binding.previewView.height.toFloat()
                val zoomFactor = 1f + distanceY / sensitivity
                cameraOperations.adjustZoom(zoomFactor)
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        binding.previewView.setOnTouchListener { view, event ->
            if (gestureDetector.onTouchEvent(event)) return@setOnTouchListener true
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            false
        }
    }

    private fun updateButtonStates() {
        val isCameraOn = cameraViewModel.isCameraEnabled.value
        with(binding) {
            powerButton.isActivated = isCameraOn
            flipButton.isEnabled = isCameraOn
            srtButton.isEnabled = isCameraOn
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun showPermissionWarning() {
        Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }
}