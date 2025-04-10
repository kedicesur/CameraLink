package com.example.cameralink.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PermissionHandler(private val activity: AppCompatActivity) {
    private companion object {
        const val PERMISSION_REQUEST_TIMEOUT = 30000L
    }

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var timeoutJob: kotlinx.coroutines.Job? = null

    suspend fun handlePermissions(): Boolean = suspendCoroutine { continuation ->
        timeoutJob?.cancel() // Cancel any existing timeout
        timeoutJob = activity.lifecycleScope.launch {
            delay(PERMISSION_REQUEST_TIMEOUT)
            continuation.resume(false)
            activity.finish()
        }

        requestPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            timeoutJob?.cancel()
            when {
                permissions[Manifest.permission.CAMERA] == true -> {
                    continuation.resume(true)
                }
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                    showSettingsDialog()
                    continuation.resume(false)
                }
                else -> {
                    showPermissionDeniedDialog()
                    continuation.resume(false)
                }
            }
        }

        when {
            hasCameraPermission() -> {
                Log.d("PermissionHandler", "Camera permission already granted")
                timeoutJob?.cancel() // Cancel timeout since we don't need to wait
                continuation.resume(true)
            }
            activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.d("PermissionHandler", "Showing permission rationale")
                showPermissionRationale {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                }
            }
            else -> {
                Log.d("PermissionHandler", "Requesting camera permission")
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun showSettingsDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage("Camera permission is required for this app. Please enable it in Settings.")
            .setPositiveButton("Settings") { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                })
            }
            .setNegativeButton("Cancel") { _, _ -> activity.finish() }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Permission Denied")
            .setMessage("Camera permission is required for this app to function. The app will now close.")
            .setPositiveButton("OK") { _, _ -> activity.finish() }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionRationale(onAccept: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Permission Needed")
            .setMessage("Camera permission is needed for core functionality")
            .setPositiveButton("OK") { _, _ -> onAccept() }
            .setNegativeButton("Cancel") { _, _ -> activity.finish() }
            .setCancelable(false)
            .show()
    }

    fun cleanup() {
        timeoutJob?.cancel()
    }
}