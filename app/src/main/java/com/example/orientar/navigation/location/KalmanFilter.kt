package com.example.orientar.navigation.location

import android.location.Location
import kotlin.math.sqrt
import com.example.orientar.navigation.util.FileLogger

/**
 * GPS position smoothing using a Kalman filter.
 *
 * State: [lat, lng, vel_lat, vel_lng]
 * Predict: x' = x + v*dt,  P' = P + Q
 * Update:  K = P'/(P'+R),   x = x' + K*(z-x'),  P = (1-K)*P'
 *
 * WHERE:
 *   P = Process covariance (our uncertainty about the state)
 *   Q = Process noise (how much the state changes unexpectedly)
 *   R = Measurement noise (GPS accuracy squared)
 *   K = Kalman gain (0-1, how much to trust measurement vs prediction)
 *
 * ================================================================================================
 * REFERENCE: IEEE "Multi-sensor fusion using Kalman filter" (2015)
 * ================================================================================================
 */
class KalmanFilter {
    companion object {
        private const val TAG = "KalmanFilter"

        // ========================================================================================
        // FILTER TUNING PARAMETERS
        // ========================================================================================

        // Process noise - how much we expect position to change unexpectedly
        // Higher = more responsive to changes, but more noise passes through
        // Lower = smoother, but slower to respond to real movement
        // Unit: degrees² per second
        //private const val PROCESS_NOISE_LAT = 0.00001   // ~1m movement per second
        //private const val PROCESS_NOISE_LNG = 0.00001
        //private const val PROCESS_NOISE_VEL = 0.0001    // Velocity can change faster
//
        // Minimum variance (prevents filter from becoming too confident)
        private const val MIN_VARIANCE = 1e-11

        // Maximum allowed jump distance (meters) before rejecting as outlier
        private const val MAX_JUMP_DISTANCE = 50.0

        // Minimum time between updates (seconds)
        private const val MIN_TIME_DELTA = 0.1

        // Maximum time gap before resetting filter (seconds)
        private const val MAX_TIME_GAP = 10.0
    }

    // ========================================================================================
    // STATE VARIABLES
    // ========================================================================================

    // Filtered position
    private var filteredLat: Double = 0.0
    private var filteredLng: Double = 0.0

    // Estimated velocity (degrees per second)
    private var velocityLat: Double = 0.0
    private var velocityLng: Double = 0.0

    // Covariance (uncertainty) for each state variable
    private var varianceLat: Double = 1.0    // Start with high uncertainty
    private var varianceLng: Double = 1.0
    private var varianceVelLat: Double = 1.0
    private var varianceVelLng: Double = 1.0

    // Timestamp of last update
    private var lastUpdateTime: Long = 0

    // Filter state
    private var isInitialized: Boolean = false

    // Statistics
    private var totalUpdates: Int = 0
    private var outlierRejections: Int = 0

    // ========================================================================================
    // PUBLIC API
    // ========================================================================================

    /**
     * Process a new GPS location through the Kalman filter.
     *
     * @param location Raw GPS location from Android
     * @return Filtered location with smoothed position
     */
    fun filter(location: Location): Location {
        val currentTime = location.time

        // First measurement - initialize filter
        if (!isInitialized) {
            initialize(location)
            return location.copy()
        }

        // Calculate time delta
        val timeDeltaMs = currentTime - lastUpdateTime
        val timeDeltaSec = timeDeltaMs / 1000.0

        // Check for time gap (GPS was off for a while)
        if (timeDeltaSec > MAX_TIME_GAP) {
            FileLogger.gps("Kalman: large time gap (${timeDeltaSec}s) - resetting")
            initialize(location)
            return location.copy()
        }

        // Skip if update is too fast (prevents numerical issues)
        if (timeDeltaSec < MIN_TIME_DELTA) {
            return createFilteredLocation(location)
        }

        // Check for outlier (impossible jump)
        val jumpDistance = calculateDistance(
            filteredLat, filteredLng,
            location.latitude, location.longitude
        )

        if (jumpDistance > MAX_JUMP_DISTANCE) {
            outlierRejections++
            FileLogger.gps("Kalman: outlier rejected (${jumpDistance.toInt()}m jump)")

            // Return previous filtered position instead of the outlier
            return createFilteredLocation(location)
        }

        // ============================================================================
        // PREDICT STEP
        // ============================================================================
        // Predict new position based on velocity
        val predictedLat = filteredLat + velocityLat * timeDeltaSec
        val predictedLng = filteredLng + velocityLng * timeDeltaSec

        // Increase uncertainty — ADAPTIVE based on movement speed
        // Standing still: very low noise (trust filter prediction, reject GPS jitter)
        // Walking: moderate noise (position changes predictably)
        // Running: higher noise (more unpredictable movement)
        val speedMs = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val baseProcessNoise = when {
            speedMs < 0.3  -> 1e-12   // Standing still
            speedMs < 2.0  -> 1e-10   // Walking
            speedMs < 5.0  -> 1e-9    // Fast walking / jogging
            else           -> 1e-8    // Running
        }
        val processNoiseLat = baseProcessNoise * timeDeltaSec
        val processNoiseLng = baseProcessNoise * timeDeltaSec
        val processNoiseVel = baseProcessNoise * 10.0 * timeDeltaSec

        val predictedVarianceLat = varianceLat + processNoiseLat
        val predictedVarianceLng = varianceLng + processNoiseLng
        val predictedVarianceVelLat = varianceVelLat + processNoiseVel
        val predictedVarianceVelLng = varianceVelLng + processNoiseVel


        // ============================================================================
        // UPDATE STEP
        // ============================================================================
        // Measurement noise from GPS accuracy
        // Convert accuracy (meters) to degrees (approximately)
        val accuracyDegrees = location.accuracy / 111000.0  // ~111km per degree
        val measurementNoise = accuracyDegrees * accuracyDegrees

        // Calculate Kalman gain (how much to trust measurement vs prediction)
        // K = P / (P + R)
        // When P is high (uncertain), K is high (trust measurement more)
        // When R is high (noisy GPS), K is low (trust prediction more)
        val kalmanGainLat = predictedVarianceLat / (predictedVarianceLat + measurementNoise)
        val kalmanGainLng = predictedVarianceLng / (predictedVarianceLng + measurementNoise)

        // Update position: x = x_predicted + K * (measurement - x_predicted)
        val previousFilteredLat = filteredLat
        val previousFilteredLng = filteredLng
        filteredLat = predictedLat + kalmanGainLat * (location.latitude - predictedLat)
        filteredLng = predictedLng + kalmanGainLng * (location.longitude - predictedLng)

        // Update velocity estimate based on actual position change
        // Uses the difference between current and previous filtered positions
        if (timeDeltaSec > 0) {
            val newVelLat = (filteredLat - previousFilteredLat) / timeDeltaSec
            val newVelLng = (filteredLng - previousFilteredLng) / timeDeltaSec

            // Smooth velocity update via Kalman gain
            val velGainLat = predictedVarianceVelLat / (predictedVarianceVelLat + measurementNoise)
            val velGainLng = predictedVarianceVelLng / (predictedVarianceVelLng + measurementNoise)

            velocityLat = velocityLat + velGainLat * (newVelLat - velocityLat)
            velocityLng = velocityLng + velGainLng * (newVelLng - velocityLng)
        }

        // Update covariance: P = (1 - K) * P_predicted
        varianceLat = ((1 - kalmanGainLat) * predictedVarianceLat).coerceAtLeast(MIN_VARIANCE)
        varianceLng = ((1 - kalmanGainLng) * predictedVarianceLng).coerceAtLeast(MIN_VARIANCE)
        varianceVelLat = ((1 - kalmanGainLat) * predictedVarianceVelLat).coerceAtLeast(MIN_VARIANCE)
        varianceVelLng = ((1 - kalmanGainLng) * predictedVarianceVelLng).coerceAtLeast(MIN_VARIANCE)

        // Update timestamp
        lastUpdateTime = currentTime
        totalUpdates++

        // Log occasionally for debugging
        if (totalUpdates % 10 == 0) {
            FileLogger.gps("Kalman #$totalUpdates: K=(${String.format("%.3f", kalmanGainLat)}, ${String.format("%.3f", kalmanGainLng)})")
        }

        return createFilteredLocation(location)
    }

    /**
     * Initialize the filter with first measurement.
     */
    private fun initialize(location: Location) {
        filteredLat = location.latitude
        filteredLng = location.longitude
        velocityLat = 0.0
        velocityLng = 0.0

        // Initial variance based on GPS accuracy
        val accuracyDegrees = location.accuracy / 111000.0
        varianceLat = accuracyDegrees * accuracyDegrees
        varianceLng = accuracyDegrees * accuracyDegrees
        varianceVelLat = 1.0
        varianceVelLng = 1.0

        lastUpdateTime = location.time
        isInitialized = true
        totalUpdates = 1

        FileLogger.gps("Kalman initialized at (${location.latitude}, ${location.longitude})")
    }

    /**
     * Create a new Location object with filtered position.
     */
    private fun createFilteredLocation(originalLocation: Location): Location {
        return Location(originalLocation).apply {
            latitude = filteredLat
            longitude = filteredLng
            // Keep original accuracy, altitude, bearing, speed, etc.
        }
    }

    /**
     * Calculate distance between two points in meters (Haversine formula).
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * Copy a Location object.
     */
    private fun Location.copy(): Location {
        return Location(this)
    }

    // ========================================================================================
    // PUBLIC QUERIES
    // ========================================================================================

    /**
     * Get current filtered position as Location.
     */
    fun getFilteredLocation(): Location? {
        if (!isInitialized) return null

        return Location("KalmanFilter").apply {
            latitude = filteredLat
            longitude = filteredLng
            time = lastUpdateTime
        }
    }

    /**
     * Get current estimated velocity in m/s.
     */
    fun getVelocity(): Pair<Double, Double> {
        // Convert degrees/second to m/s (approximately)
        val velLatMs = velocityLat * 111000.0
        val velLngMs = velocityLng * 111000.0 * Math.cos(Math.toRadians(filteredLat))
        return Pair(velLatMs, velLngMs)
    }

    /**
     * Get current Kalman gain (for diagnostics).
     * Returns approximate gain based on current variance.
     */
    fun getApproximateGain(): Double {
        val avgVariance = (varianceLat + varianceLng) / 2
        val typicalMeasurementNoise = (5.0 / 111000.0).let { it * it } // Assume 5m accuracy
        return avgVariance / (avgVariance + typicalMeasurementNoise)
    }

    /**
     * Check if filter is initialized.
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Reset the filter.
     */
    fun reset() {
        isInitialized = false
        filteredLat = 0.0
        filteredLng = 0.0
        velocityLat = 0.0
        velocityLng = 0.0
        varianceLat = 1.0
        varianceLng = 1.0
        varianceVelLat = 1.0
        varianceVelLng = 1.0
        lastUpdateTime = 0
        totalUpdates = 0
        outlierRejections = 0

        FileLogger.gps("Kalman filter reset")
    }

    /**
     * Get diagnostic information.
     */
    fun getDiagnostics(): String {
        if (!isInitialized) {
            return "KalmanFilter: Not initialized"
        }

        val (velLatMs, velLngMs) = getVelocity()
        val speedMs = sqrt(velLatMs * velLatMs + velLngMs * velLngMs)

        return """
            |╔═══════════════════════════════════════
            |║ KALMAN FILTER DIAGNOSTICS
            |╠═══════════════════════════════════════
            |║ Initialized: $isInitialized
            |║ Total Updates: $totalUpdates
            |║ Outliers Rejected: $outlierRejections
            |╠═══════════════════════════════════════
            |║ POSITION
            |║   Lat: ${String.format("%.6f", filteredLat)}
            |║   Lng: ${String.format("%.6f", filteredLng)}
            |╠═══════════════════════════════════════
            |║ VELOCITY
            |║   Speed: ${String.format("%.2f", speedMs)} m/s
            |║   (${String.format("%.1f", speedMs * 3.6)} km/h)
            |╠═══════════════════════════════════════
            |║ UNCERTAINTY
            |║   Variance Lat: ${String.format("%.2e", varianceLat)}
            |║   Variance Lng: ${String.format("%.2e", varianceLng)}
            |║   Approx Gain: ${String.format("%.3f", getApproximateGain())}
            |╚═══════════════════════════════════════
        """.trimMargin()
    }
}