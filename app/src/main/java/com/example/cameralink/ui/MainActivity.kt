package com.example.cameralink.ui

import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.os.*
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

private const val TAG = "MainActivity"
private const val DEFAULT_TEMPERATURE = 35f

interface TemperatureProvider {
    fun getDeviceTemperature(): Float
}

class DefaultTemperatureProvider(private val context: Context) : TemperatureProvider {
    override fun getDeviceTemperature(): Float {
        return try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            when (val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) {
                0, null -> DEFAULT_TEMPERATURE  // Default if unavailable
                else -> temp / 10f
            }
        } catch (e: SecurityException) {
            Log.w("TemperatureProvider", "Battery access denied", e)
            DEFAULT_TEMPERATURE
        } catch (e: Exception) {
            Log.e("TemperatureProvider", "Temperature read failed", e)
            DEFAULT_TEMPERATURE
        }
    }
}

object TemperatureProviderHolder {
    lateinit var temperatureProvider: TemperatureProvider
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val cameraViewModel: CameraViewModel by viewModels()
    private val cameraOperations by lazy {
        CameraOperations(this, cameraViewModel)
    }
    private var orientationListener: OrientationEventListener? = null
    private val permissionHandler by lazy { PermissionHandler(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TemperatureProviderHolder.temperatureProvider = DefaultTemperatureProvider(this@MainActivity)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraViewModel.setCameraOperations(cameraOperations)

        // Initially disable all UI interactions except Power button
        with(binding) {
            flipButton.isEnabled = false
            srtButton.isEnabled = false
        }

        lifecycleScope.launch {
            if (permissionHandler.handlePermissions()) {
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
        cameraViewModel.setCameraEnabled(false) // first sets wasCameraEnabled to isCameraEnabled.value
        if (cameraViewModel.isSrtActive.value) cameraViewModel.toggleSrtActive()
        orientationListener?.disable()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        if (cameraViewModel.wasCameraEnabled.value) cameraViewModel.setCameraEnabled(true) // first sets wasCameraEnabled to isCameraEnabled.value
        if (cameraViewModel.wasSrtActive.value) {
            Toast.makeText(this, "Resuming SRT streaming", Toast.LENGTH_SHORT).show()
        }
        orientationListener?.enable()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        permissionHandler.cleanup()
        cameraOperations.close()
        orientationListener?.disable()
        cameraViewModel.resetSrtActive()
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
                    cameraViewModel.srtToastMessage.collect { message ->
                        message?.let {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                        }
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
        val isSrtOn = cameraViewModel.isSrtActive.value
        with(binding) {
            powerButton.isActivated = isCameraOn
            flipButton.isEnabled = isCameraOn
            srtButton.isEnabled = isCameraOn
            srtButton.isActivated = isSrtOn
        }
    }
}
