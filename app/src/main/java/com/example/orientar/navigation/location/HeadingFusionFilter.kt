package com.example.orientar.navigation.location

import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.example.orientar.navigation.util.FileLogger

/**
 * HeadingFusionFilter - Gyroscope + Compass Sensor Fusion for Stable Heading
 *
 * ================================================================================================
 * PHASE 2: SENSOR FUSION - HEADING FUSION
 * ================================================================================================
 *
 * PROBLEM:
 * - Compass alone is noisy and affected by magnetic interference (metal, electronics)
 * - Gyroscope alone drifts over time (small errors accumulate)
 * - Both sensors have different strengths and weaknesses
 *
 * SOLUTION:
 * Complementary filter combines both sensors:
 * - Use gyroscope for SHORT-TERM changes (fast, accurate, no magnetic interference)
 * - Use compass for LONG-TERM reference (absolute heading, corrects gyro drift)
 *
 * FORMULA (Complementary Filter):
 *   heading = α × (heading + gyro_delta) + (1-α) × compass
 *   where α = 0.98 (trust gyro 98%, compass 2%)
 *
 * EXPECTED IMPROVEMENT: 30% more stable heading, less AR jitter
 *
 * ================================================================================================
 * WHY THIS WORKS
 * ================================================================================================
 *
 * GYROSCOPE:
 * ✅ Fast response (high frequency)
 * ✅ No magnetic interference
 * ✅ Smooth output
 * ❌ Drifts over time (bias)
 *
 * COMPASS:
 * ✅ Absolute heading (no drift)
 * ✅ Long-term accurate
 * ❌ Noisy (jittery)
 * ❌ Affected by metal, electronics
 * ❌ Slow response
 *
 * FUSION:
 * ✅ Fast response from gyro
 * ✅ No drift (compass corrects it)
 * ✅ Smooth output
 * ✅ Reduced magnetic interference
 *
 * ================================================================================================
 * REFERENCE: Complementary filter theory, IEEE sensor fusion papers
 * ================================================================================================
 */
class HeadingFusionFilter {
    companion object {

        // Adaptive smoothing parameters
        private const val SMOOTHING_ALPHA_MIN = 0.1f   // Heavy smoothing when still
        private const val SMOOTHING_ALPHA_MAX = 0.5f   // Light smoothing when rotating fast
        private const val ROTATION_RATE_THRESHOLD = 30f // Degrees per second for max alpha
        private const val TAG = "HeadingFusion"

        // ========================================================================================
        // FILTER TUNING PARAMETERS
        // ========================================================================================

        // Complementary filter alpha (gyro weight)
        // Higher = trust gyro more (smoother but may drift)
        // Lower = trust compass more (noisier but accurate)
        // 0.98 = 98% gyro, 2% compass (works well for walking speed)
        private const val ALPHA = 0.98f

        // Adaptive alpha range
        // When stationary, trust compass more (ALPHA_LOW)
        // When moving, trust gyro more (ALPHA_HIGH)
        private const val ALPHA_LOW = 0.90f   // Stationary - more compass
        private const val ALPHA_HIGH = 0.98f  // Moving - more gyro

        // Angular velocity threshold for motion detection (rad/s)
        private const val MOTION_THRESHOLD = 0.02f

        // Maximum allowed difference between gyro and compass before reset
        // If difference > this, compass is probably right (gyro drifted too much)
        private const val MAX_DIVERGENCE_DEG = 45.0f

        // Minimum time between updates (seconds)
        private const val MIN_UPDATE_INTERVAL = 0.001f  // 1ms

        // Gyro bias estimation
        private const val BIAS_ESTIMATION_ALPHA = 0.001f  // Very slow bias update
    }

    // ========================================================================================
    // STATE VARIABLES
    // ========================================================================================

    // Fused heading (output)
    private var fusedHeading: Float = 0f

    // Last sensor values
    private var lastGyroTimestamp: Long = 0
    private var lastCompassHeading: Float = 0f

    // Gyro bias estimation (to compensate for sensor drift)
    private var gyroBiasZ: Float = 0f

    // Adaptive alpha (changes based on motion)
    private var currentAlpha: Float = ALPHA

    // Filter state
    private var isInitialized: Boolean = false

    // Statistics
    private var totalUpdates: Int = 0
    private var compassCorrections: Int = 0
    private var gyroUpdates: Int = 0

    // Motion detection
    private var isMoving: Boolean = false
    private var stationaryCount: Int = 0

    // ========================================================================================
    // PUBLIC API
    // ========================================================================================

    /**
     * Calculate adaptive smoothing factor based on rotation rate.
     * Fast rotation = less smoothing (faster response)
     * Slow/no rotation = more smoothing (more stable)
     */
    private fun calculateAdaptiveAlpha(rotationRateDegPerSec: Float): Float {
        val normalizedRate = (abs(rotationRateDegPerSec) / ROTATION_RATE_THRESHOLD).coerceIn(0f, 1f)
        return SMOOTHING_ALPHA_MIN + normalizedRate * (SMOOTHING_ALPHA_MAX - SMOOTHING_ALPHA_MIN)
    }

    /**
     * Update the filter with new gyroscope data.
     * Call this from onSensorChanged for TYPE_GYROSCOPE.
     *
     * @param gyroZ Angular velocity around Z-axis (yaw) in rad/s
     * @param timestamp Sensor timestamp in nanoseconds
     */
    fun updateGyro(gyroZ: Float, timestamp: Long) {
        if (!isInitialized) {
            lastGyroTimestamp = timestamp
            return
        }

        // Calculate time delta
        val dtNanos = timestamp - lastGyroTimestamp
        if (dtNanos <= 0) return

        val dtSeconds = dtNanos / 1_000_000_000.0f

        // Skip if update is too fast or too slow
        if (dtSeconds < MIN_UPDATE_INTERVAL || dtSeconds > 1.0f) {
            lastGyroTimestamp = timestamp
            return
        }

        // Subtract estimated bias
        val correctedGyroZ = gyroZ - gyroBiasZ

        // Detect motion
        isMoving = abs(correctedGyroZ) > MOTION_THRESHOLD
        if (!isMoving) {
            stationaryCount++
            // Update bias estimate when stationary
            if (stationaryCount > 50) {
                gyroBiasZ += (gyroZ - gyroBiasZ) * BIAS_ESTIMATION_ALPHA
            }
        } else {
            stationaryCount = 0
        }

        // Adapt alpha based on motion
        currentAlpha = if (isMoving) ALPHA_HIGH else ALPHA_LOW

        // Integrate gyro to get heading change
        // Gyro gives rad/s, multiply by dt to get radians, convert to degrees
        val headingDelta = Math.toDegrees(correctedGyroZ.toDouble()).toFloat() * dtSeconds

        // Apply gyro update: heading = heading + gyro_delta
        val gyroHeading = normalizeAngle(fusedHeading + headingDelta)

        // Complementary filter: fused = α × gyro + (1-α) × compass
        // But we apply compass correction separately for smoother behavior
        fusedHeading = gyroHeading

        lastGyroTimestamp = timestamp
        gyroUpdates++
        totalUpdates++
    }

    /**
     * Update the filter with new compass data.
     * Call this when compass bearing is updated.
     *
     * @param compassHeading Compass heading in degrees (0-360, true north)
     */
    fun updateCompass(compassHeading: Float) {
        val normalizedCompass = normalizeAngle(compassHeading)

        if (!isInitialized) {
            fusedHeading = normalizedCompass
            lastCompassHeading = normalizedCompass
            isInitialized = true
            Log.d(TAG, "Filter initialized with compass: ${normalizedCompass.toInt()}°")
            FileLogger.sensor("Heading filter initialized: ${normalizedCompass.toInt()}°")
            return
        }

        lastCompassHeading = normalizedCompass

        // Calculate difference between fused heading and compass
        var diff = normalizedCompass - fusedHeading

        // Handle wraparound (e.g., compass=10°, fused=350° -> diff should be +20°, not -340°)
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360

        // Check for large divergence (gyro may have drifted too much)
        if (abs(diff) > MAX_DIVERGENCE_DEG) {
            Log.w(TAG, "Large divergence detected: ${abs(diff).toInt()}° - trusting compass")
            FileLogger.w("SENSOR", "Heading divergence: ${abs(diff).toInt()}° - compass override")
            fusedHeading = normalizedCompass
            compassCorrections++
            return
        }

        // Apply complementary filter correction
        // heading = α × heading + (1-α) × compass
        // Equivalent to: heading = heading + (1-α) × (compass - heading)
        val correction = (1 - currentAlpha) * diff
        fusedHeading = normalizeAngle(fusedHeading + correction)

        compassCorrections++
        totalUpdates++

        // Log occasionally
        if (compassCorrections % 50 == 0) {
            if (abs(diff) > 15) {
                FileLogger.sensor("Large correction: ${diff.toInt()}°, fused=${fusedHeading.toInt()}°")
            }
            Log.d(TAG, "Compass correction: diff=${diff.toInt()}°, α=${String.format("%.2f", currentAlpha)}, fused=${fusedHeading.toInt()}°")
        }
    }

    /**
     * Get the current fused heading.
     *
     * @return Heading in degrees (0-360, true north)
     */
    fun getFusedHeading(): Float {
        return fusedHeading
    }

    /**
     * Get the raw compass heading (for comparison).
     */
    fun getCompassHeading(): Float {
        return lastCompassHeading
    }

    /**
     * Get the difference between fused and compass headings.
     * Useful for detecting magnetic interference.
     */
    fun getCompassDifference(): Float {
        var diff = fusedHeading - lastCompassHeading
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return diff
    }

    /**
     * Check if filter is initialized.
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Check if device is currently moving (rotating).
     */
    fun isMoving(): Boolean = isMoving

    /**
     * Reset the filter.
     */
    fun reset() {
        fusedHeading = 0f
        lastGyroTimestamp = 0
        lastCompassHeading = 0f
        gyroBiasZ = 0f
        currentAlpha = ALPHA
        isInitialized = false
        totalUpdates = 0
        compassCorrections = 0
        gyroUpdates = 0
        isMoving = false
        stationaryCount = 0

        Log.d(TAG, "Filter reset")
        FileLogger.sensor("Heading filter RESET")
    }

    /**
     * Force set the fused heading (e.g., after recalibration).
     */
    fun setHeading(heading: Float) {
        fusedHeading = normalizeAngle(heading)
        lastCompassHeading = fusedHeading
        Log.d(TAG, "Heading set to: ${fusedHeading.toInt()}°")
    }

    // ========================================================================================
    // UTILITY FUNCTIONS
    // ========================================================================================

    /**
     * Normalize angle to 0-360 range.
     */
    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0) normalized += 360f
        return normalized
    }

    // ========================================================================================
    // DIAGNOSTICS
    // ========================================================================================

    /**
     * Get diagnostic information.
     */
    fun getDiagnostics(): String {
        return """
            |╔═══════════════════════════════════════
            |║ HEADING FUSION DIAGNOSTICS
            |╠═══════════════════════════════════════
            |║ Initialized: $isInitialized
            |║ Fused Heading: ${fusedHeading.toInt()}°
            |║ Compass Heading: ${lastCompassHeading.toInt()}°
            |║ Difference: ${getCompassDifference().toInt()}°
            |╠═══════════════════════════════════════
            |║ FILTER STATE
            |║   Current Alpha: ${String.format("%.2f", currentAlpha)}
            |║   Is Moving: $isMoving
            |║   Gyro Bias Z: ${String.format("%.4f", gyroBiasZ)} rad/s
            |╠═══════════════════════════════════════
            |║ STATISTICS
            |║   Total Updates: $totalUpdates
            |║   Gyro Updates: $gyroUpdates
            |║   Compass Corrections: $compassCorrections
            |╚═══════════════════════════════════════
        """.trimMargin()
    }
}