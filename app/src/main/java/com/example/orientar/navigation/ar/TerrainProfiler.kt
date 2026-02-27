package com.example.orientar.navigation.ar

import android.location.Location
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import com.example.orientar.navigation.util.FileLogger

/**
 * TerrainProfiler - Learns terrain height profile for accurate sphere placement.
 *
 * RUNS ON: Background thread
 * PROVIDES: Height adjustments based on terrain slope
 *
 * HOW IT WORKS:
 * 1. Collects ground height samples when reliable detection occurs
 * 2. Builds a terrain model (height vs position)
 * 3. Interpolates expected ground height for any position
 * 4. Provides height adjustment to compensate for slopes
 */
class TerrainProfiler {
    companion object {
        private const val TAG = "TerrainProfiler"
    }

    // ========================================================================================
    // DATA STRUCTURES
    // ========================================================================================

    data class TerrainSample(
        val latitude: Double,
        val longitude: Double,
        val groundY: Float,           // AR Y coordinate of ground at this location
        val confidence: Float,        // 0.0 - 1.0, how reliable this sample is
        val anchorType: String,       // How ground was detected
        val timestamp: Long
    )

    data class TerrainEstimate(
        val expectedGroundY: Float,   // Estimated ground Y at query position
        val confidence: Float,        // How confident we are in this estimate
        val slopeAngle: Float,        // Terrain slope in degrees (+ = uphill, - = downhill)
        val heightAdjustment: Float   // Recommended adjustment to base height
    )

    // ========================================================================================
    // STATE (Thread-Safe)
    // ========================================================================================

    private val samples = ConcurrentLinkedQueue<TerrainSample>()
    private val isProcessing = AtomicBoolean(false)
    private val latestEstimate = AtomicReference<TerrainEstimate?>(null)

    // Reference point (anchor location)
    private var anchorLat: Double = 0.0
    private var anchorLng: Double = 0.0
    private var anchorGroundY: Float = 0f

    private var isInitialized: Boolean = false

    // ========================================================================================
    // PUBLIC API
    // ========================================================================================

    /**
     * Initialize with anchor location.
     * Call this when a new anchor is created.
     */
    fun initialize(anchorLocation: Location, groundY: Float) {
        anchorLat = anchorLocation.latitude
        anchorLng = anchorLocation.longitude
        anchorGroundY = groundY

        samples.clear()
        isInitialized = true

        // Add anchor as first sample (highest confidence)
        addSample(anchorLocation, groundY, 1.0f, "Anchor")

        Log.d(TAG, "Initialized at (${anchorLat}, ${anchorLng}), groundY=$groundY")
        FileLogger.terrain("Initialized: groundY=${String.format("%.2f", groundY)}")
    }

    /**
     * Add a terrain sample.
     * Call this when reliable ground detection occurs.
     */
    fun addSample(location: Location, groundY: Float, confidence: Float, anchorType: String) {
        val sample = TerrainSample(
            latitude = location.latitude,
            longitude = location.longitude,
            groundY = groundY,
            confidence = confidence.coerceIn(0f, 1f),
            anchorType = anchorType,
            timestamp = System.currentTimeMillis()
        )

        samples.add(sample)

        // Limit sample count
        while (samples.size > ARPerformanceConfig.MAX_TERRAIN_SAMPLES) {
            samples.poll()
        }

        Log.d(TAG, "Added sample: groundY=$groundY, confidence=$confidence, type=$anchorType")
        // Only log high-confidence samples to reduce noise
        if (confidence > 0.7f) {
            FileLogger.terrain("Sample: groundY=${String.format("%.2f", groundY)}, conf=${String.format("%.1f", confidence)}")
        }
    }

    /**
     * Estimate terrain at a given position.
     * This is the main query method - call from background thread.
     *
     * @param targetLat Target latitude
     * @param targetLng Target longitude
     * @param distanceFromAnchor Distance from anchor in meters
     * @param bearingFromAnchor Bearing from anchor in degrees
     * @return TerrainEstimate with height adjustment recommendation
     */
    fun estimateTerrain(
        targetLat: Double,
        targetLng: Double,
        distanceFromAnchor: Float,
        bearingFromAnchor: Float
    ): TerrainEstimate {
        // ============================================================================
        // BUG-001 FIX: Return safe defaults when not initialized
        // ============================================================================
        // PROBLEM: Previously returned TerrainEstimate(0f, 0f, 0f, 0f) which caused
        //          spheres to render at ground level (Y=0) instead of eye level.
        //
        // SOLUTION: Return a safe estimate with:
        //   - confidence = 0f to signal uninitialized state to callers
        //   - heightAdjustment = 0f because BASE_SPHERE_HEIGHT in ARPerformanceConfig
        //     already provides the correct default height (1.2m)
        //
        // CALLERS SHOULD: Check confidence > 0 before trusting heightAdjustment
        // ============================================================================
        if (!isInitialized) {
            Log.w(TAG, "estimateTerrain called before initialize() - returning safe defaults")
            FileLogger.w("TERRAIN", "estimateTerrain called before init!")
            return TerrainEstimate(
                expectedGroundY = 0f,
                confidence = 0f,  // Zero confidence signals uninitialized state
                slopeAngle = 0f,
                heightAdjustment = 0f  // No adjustment - BASE_SPHERE_HEIGHT handles default
            )
        }

        if (samples.isEmpty()) {
            // Initialized but no samples yet - use anchor as reference with low confidence
            return TerrainEstimate(
                expectedGroundY = anchorGroundY,
                confidence = 0.3f,  // Low confidence - only have anchor data
                slopeAngle = 0f,
                heightAdjustment = 0f
            )
        }

        // Remove old samples
        val now = System.currentTimeMillis()
        val validityMs = ARPerformanceConfig.TERRAIN_SAMPLE_VALIDITY_MS
        samples.removeIf { now - it.timestamp > validityMs }

        // Find nearby samples and interpolate
        val nearbySamples = findNearbySamples(targetLat, targetLng, maxDistance = 30.0)

        if (nearbySamples.isEmpty()) {
            // No nearby samples - use linear extrapolation from anchor
            return extrapolateFromAnchor(distanceFromAnchor, bearingFromAnchor)
        }

        // Weighted average of nearby samples
        return interpolateFromSamples(nearbySamples, targetLat, targetLng)
    }

    /**
     * Get the latest terrain estimate (thread-safe read).
     */
    fun getLatestEstimate(): TerrainEstimate? = latestEstimate.get()

    /**
     * Reset the profiler.
     */
    fun reset() {
        samples.clear()
        latestEstimate.set(null)
        isInitialized = false
        Log.d(TAG, "Reset")
        FileLogger.terrain("RESET")
    }

    // ========================================================================================
    // PRIVATE METHODS
    // ========================================================================================

    private fun findNearbySamples(lat: Double, lng: Double, maxDistance: Double): List<TerrainSample> {
        return samples.filter { sample ->
            val dist = haversineDistance(lat, lng, sample.latitude, sample.longitude)
            dist <= maxDistance
        }.sortedBy { sample ->
            haversineDistance(lat, lng, sample.latitude, sample.longitude)
        }.take(10)  // Use at most 10 nearest samples
    }

    private fun interpolateFromSamples(
        nearbySamples: List<TerrainSample>,
        targetLat: Double,
        targetLng: Double
    ): TerrainEstimate {
        if (nearbySamples.isEmpty()) {
            return TerrainEstimate(anchorGroundY, 0.5f, 0f, 0f)
        }

        // Inverse distance weighting (IDW) interpolation
        var weightedSum = 0f
        var weightSum = 0f
        var confidenceSum = 0f

        for (sample in nearbySamples) {
            val dist = haversineDistance(targetLat, targetLng, sample.latitude, sample.longitude)
                .coerceAtLeast(0.1)  // Avoid division by zero

            // Weight = confidence / distance²
            val weight = sample.confidence / (dist * dist).toFloat()

            weightedSum += sample.groundY * weight
            weightSum += weight
            confidenceSum += sample.confidence
        }

        val estimatedGroundY = if (weightSum > 0) weightedSum / weightSum else anchorGroundY
        val avgConfidence = confidenceSum / nearbySamples.size

        // Calculate slope from samples
        val slopeAngle = calculateSlopeAngle(nearbySamples)

        // Height adjustment based on difference from anchor
        val heightDiff = estimatedGroundY - anchorGroundY
        val heightAdjustment = -heightDiff  // If ground is lower, raise spheres (negative adjustment)

        return TerrainEstimate(
            expectedGroundY = estimatedGroundY,
            confidence = avgConfidence * 0.8f,  // Reduce confidence for interpolated values
            slopeAngle = slopeAngle,
            heightAdjustment = heightAdjustment.coerceIn(-1.0f, 1.0f)  // Limit adjustment
        )
    }

    private fun extrapolateFromAnchor(distance: Float, bearing: Float): TerrainEstimate {
        // Without samples, assume flat terrain
        // But reduce confidence based on distance
        val confidence = (1.0f - distance / 50f).coerceIn(0.2f, 0.8f)

        return TerrainEstimate(
            expectedGroundY = anchorGroundY,
            confidence = confidence,
            slopeAngle = 0f,
            heightAdjustment = 0f
        )
    }

    private fun calculateSlopeAngle(samples: List<TerrainSample>): Float {
        if (samples.size < 2) return 0f

        // Simple slope calculation using first and last sample
        val first = samples.first()
        val last = samples.last()

        val horizontalDist = haversineDistance(
            first.latitude, first.longitude,
            last.latitude, last.longitude
        ).toFloat()

        if (horizontalDist < 1f) return 0f

        val verticalDiff = last.groundY - first.groundY
        val slopeRad = atan2(verticalDiff, horizontalDist)

        return Math.toDegrees(slopeRad.toDouble()).toFloat()
    }

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // ========================================================================================
    // DIAGNOSTICS
    // ========================================================================================

    fun getDiagnostics(): String {
        return buildString {
            appendLine("=== Terrain Profiler ===")
            appendLine("Samples: ${samples.size}")
            appendLine("Anchor groundY: $anchorGroundY")
            latestEstimate.get()?.let { est ->
                appendLine("Latest estimate:")
                appendLine("  Ground Y: ${est.expectedGroundY}")
                appendLine("  Confidence: ${est.confidence}")
                appendLine("  Slope: ${est.slopeAngle}°")
                appendLine("  Height adj: ${est.heightAdjustment}")
            }
        }
    }
}