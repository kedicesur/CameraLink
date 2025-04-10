package com.example.cameralink.ui

import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.example.cameralink.camera.CameraOperations
import com.example.cameralink.databinding.ActivityMainBinding
import com.example.cameralink.permissions.PermissionHandler
import com.example.cameralink.streaming.SrtStreamer
import com.example.cameralink.streaming.SrtWrapper

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val srtStreamer by lazy {
        SrtStreamer(this)
    }

    private val cameraViewModel: CameraViewModel by viewModels()

    private val cameraOperations by lazy {
        CameraOperations(this, cameraViewModel)
    }
    private var orientationListener: OrientationEventListener? = null

    private val permissionHandler by lazy { PermissionHandler(this) }
    private var wasCameraEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initially disable all UI interactions except Power button
        with(binding) {
            flipButton.isEnabled = false
            srtButton.isEnabled = false
        }

        lifecycleScope.launch {
            Log.d(TAG, "Starting permission handling")
            // Suspend until permission is handled
            if (permissionHandler.handlePermissions()) {
                // Only setup if permission granted
                Log.d(TAG, "Permission granted, setting up UI")
                setupUI()
                setupStateCollectors()
                setupOrientationListener()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        if (isChangingConfigurations) {
            cameraViewModel.setConfigurationChanging(true)
        }
        wasCameraEnabled = cameraViewModel.isCameraEnabled.value
        cameraViewModel.setCameraEnabled(false)
        orientationListener?.disable()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        if (wasCameraEnabled) {
            cameraViewModel.setCameraEnabled(true)
        }
        wasCameraEnabled = false
        cameraViewModel.setConfigurationChanging(false)
        orientationListener?.enable()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        permissionHandler.cleanup()
        cameraOperations.close()
        orientationListener?.disable()
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
                        updateButtonStates()
                        when (isEnabled) {
                            true -> {
                                binding.previewView.visibility = View.VISIBLE
                                cameraOperations.startCamera(binding.previewView.holder)
                            }
                            false -> {
                                Log.d(TAG, "isCameraEnabled reset and Camera disabled")
                                binding.previewView.visibility = View.INVISIBLE
                                cameraOperations.close()
                                if (cameraViewModel.isSrtActive.value) {
                                    cameraViewModel.resetSrtActive()
                                    handleSrtState(false)
                                }
                            }
                        }
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
                        if (!cameraViewModel.isConfigurationChanging.value &&
                            (isActive xor cameraViewModel.wasSrtActive.value)
                        ) {
                            handleSrtState(isActive)
                        }
                        binding.srtButton.isActivated = isActive
                        updateButtonStates()
                    }
                }
            }
        }
    }

    private fun setupOrientationListener() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                when {
                    orientation in 45..135 || orientation in 225..315 -> {
                        supportActionBar?.let {
                            if (it.isShowing) it.hide()
                            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE // Needed for Min API 29
                        }
                    }
                    else -> {
                        supportActionBar?.let {
                            if (!it.isShowing) it.show()
                            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        }
                    }
                }
            }
        }
        orientationListener?.enable()
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

    private fun setupGestureDetector() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null || !cameraViewModel.isCameraEnabled.value) return false
                val sensitivity = binding.previewView.width.toFloat()
                val zoomFactor = 1f + distanceX / sensitivity
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
}
