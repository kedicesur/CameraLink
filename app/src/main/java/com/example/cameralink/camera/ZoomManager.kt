package com.example.cameralink.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import kotlin.math.min

/**
 * ZoomManager handles all zoom‐related state and calculations.
 *
 * It maintains the current zoom value, computes the optical zoom capacity (from the available focal lengths),
 * and, when digital zoom is required, calculates the appropriate crop region.
 */
class ZoomManager {

    /**
     * The current overall zoom ratio (combining optical and digital components).
     * Always starts at 1x (no zoom).
     */
    private var currentZoom: Float = 1f

    /**
     * The optical zoom capacity computed from the available focal lengths.
     * For example, if the maximum focal length divided by the minimum focal length is 3,
     * then opticalZoomCapacity will be 3x.
     */
    private var opticalZoomCapacity: Float = 1f

    /**
     * Computes a crop region for a given digital zoom factor.
     *
     * When digitalZoomFactor equals 1, the entire sensor is used.
     * Otherwise, a region centered on the sensor is computed by dividing the sensor dimensions by the digital factor.
     *
     * @param digitalZoomFactor The factor by which digital zoom is applied.
     * @param characteristics The CameraCharacteristics (used to obtain the sensor active array size).
     * @return A Rect representing the crop region for digital zoom.
     */
    private fun getZoomCropRegion(digitalZoomFactor: Float, characteristics: CameraCharacteristics): Rect {
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val cropWidth = (sensorRect.width() / digitalZoomFactor).toInt()
        val cropHeight = (sensorRect.height() / digitalZoomFactor).toInt()
        val centerX = sensorRect.centerX()
        val centerY = sensorRect.centerY()
        val left = centerX - cropWidth / 2
        val top = centerY - cropHeight / 2
        Log.d("ZoomManager", "Crop region: left=$left, top=$top, right=${left + cropWidth}, bottom=${top + cropHeight}, width=$cropWidth, height=$cropHeight, DZF: $digitalZoomFactor")
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    /**
     * Initializes the zoom manager by computing the optical zoom capacity.
     * Must be called once the camera is opened and the characteristics are available.
     *
     * @param characteristics The CameraCharacteristics for the opened camera.
     */
    fun initializeZoom(characteristics: CameraCharacteristics) {
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        opticalZoomCapacity = if (focalLengths != null && focalLengths.isNotEmpty()) {
            focalLengths.maxOrNull()!! / focalLengths.minOrNull()!!
        } else {
            1f
        }
        Log.d("ZoomManager", "Optical zoom capacity: $opticalZoomCapacity")
        currentZoom = 1f
    }

    /**
     * Adjusts the zoom ratio based on a scale factor (for example, from a pinch gesture).
     *
     * The logic is:
     *  1. Multiply the current zoom by the scale factor.
     *  2. Clamp the value between 1 and the maximum digital zoom available (from the camera characteristics).
     *  3. Determine whether digital zoom is needed: if the requested zoom is within the optical range,
     *     no digital zoom (i.e. crop) is applied. Otherwise, compute a digital zoom factor.
     *
     * @param scaleFactor The factor by which to adjust the current zoom.
     * @param characteristics The CameraCharacteristics for retrieving the max digital zoom.
     * @return A Pair where:
     *         - first: the updated current zoom ratio.
     *         - second: a crop region to apply for digital zoom, or null if no digital crop is needed.
     */
    fun adjustZoom(scaleFactor: Float, characteristics: CameraCharacteristics): Pair<Float, Rect?> {
        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        // Compute new requested zoom ratio.
        currentZoom = (currentZoom * scaleFactor).coerceIn(1f, maxZoom)
        // Determine the portion that can be fulfilled optically.
        val effectiveOpticalZoom = min(currentZoom, opticalZoomCapacity)
        // Digital zoom is applied only when requested zoom exceeds optical capacity.
        val digitalZoomFactor = currentZoom / effectiveOpticalZoom
        return if (digitalZoomFactor > 1f) {
            Pair(currentZoom, getZoomCropRegion(digitalZoomFactor, characteristics))
        } else {
            Pair(currentZoom, null)
        }
    }
}
