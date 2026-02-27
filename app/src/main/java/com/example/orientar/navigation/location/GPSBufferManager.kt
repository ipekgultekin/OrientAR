package com.example.orientar.navigation.location

import android.location.Location
import android.util.Log
import com.example.orientar.navigation.logic.ArUtils
import com.example.orientar.navigation.util.FileLogger

/**
 * ================================================================================================
 * GPS QUALITY METRICS EXPLAINED
 * ================================================================================================
 *
 * HDOP (Horizontal Dilution of Precision):
 * - Measures satellite geometry quality for horizontal positioning
 * - Lower values = better geometry = more accurate position
 * - Ideal: < 1.0, Good: < 2.0, Acceptable: < 5.0, Poor: > 5.0
 *
 * Satellite Count:
 * - More satellites = more data points = better triangulation
 * - Minimum for 3D fix: 4 satellites
 * - Good outdoor: 8+ satellites
 * - Excellent: 12+ satellites
 *
 * Fix Quality (from NMEA GGA field 6):
 * - 0: Invalid/No fix
 * - 1: Standard GPS (autonomous)
 * - 2: DGPS (Differential GPS) - uses ground corrections
 * - 4: RTK Fixed - centimeter accuracy
 * - 5: RTK Float - decimeter accuracy
 *
 * ================================================================================================
 */
class GPSBufferManager(
    private val requiredSampleCount: Int = 8,
    private val maxAccuracyThreshold: Float = 10.0f,
    private val maxScatterDistance: Float = 5.0f
) {
    companion object {
        private const val TAG = "GPSBufferManager"
        private const val MAX_BUFFER_SIZE = 20 // Prevent infinite accumulation

        // ========================================================================================
        // PHASE 1: NMEA QUALITY THRESHOLDS
        // ========================================================================================

        // HDOP thresholds (Horizontal Dilution of Precision)
        // Lower HDOP = better satellite geometry = more accurate horizontal position
        private const val HDOP_EXCELLENT = 1.0f    // Ideal conditions
        private const val HDOP_GOOD = 2.0f         // Good for navigation
        private const val HDOP_ACCEPTABLE = 5.0f   // Acceptable for AR
        private const val HDOP_MAX = 8.0f          // Reject above this

        // Satellite count thresholds
        private const val SATELLITES_EXCELLENT = 10  // Excellent tracking
        private const val SATELLITES_GOOD = 7        // Good tracking
        private const val SATELLITES_MIN = 4         // Minimum for 3D fix

        // Fix quality values (NMEA GGA field 6)
        private const val FIX_INVALID = 0
        private const val FIX_GPS = 1        // Standard GPS
        private const val FIX_DGPS = 2       // Differential GPS
        private const val FIX_RTK_FIXED = 4  // RTK Fixed (best)
        private const val FIX_RTK_FLOAT = 5  // RTK Float
    }

    // ========================================================================================
    // DATA CLASS FOR ENHANCED GPS SAMPLE
    // ========================================================================================

    /**
     * Enhanced GPS sample with NMEA quality metadata.
     */
    data class EnhancedSample(
        val location: Location,
        val hdop: Float,           // Horizontal Dilution of Precision
        val satelliteCount: Int,   // Number of satellites used
        val fixQuality: Int,       // NMEA fix quality indicator
        val qualityScore: Float,   // Combined quality score (0-1, higher = better)
        val timestamp: Long = System.currentTimeMillis()
    )

    private val buffer = ArrayList<Location>()
    private val enhancedBuffer = ArrayList<EnhancedSample>()  // NEW: Enhanced samples

    // ============================================================================
// BUG-003 FIX (Part 1): Use @Volatile for thread-safe state access
// ============================================================================
// @Volatile ensures that reads and writes to this field are visible to all
// threads immediately. This prevents stale reads in multi-threaded scenarios.
// ============================================================================
    @Volatile
    private var state = State.COLLECTING

    // Statistics for debugging
    private var totalSamplesReceived = 0
    private var samplesRejectedByAccuracy = 0
    private var samplesRejectedByHdop = 0
    private var samplesRejectedBySatellites = 0

    enum class State {
        COLLECTING,     // Still gathering samples
        READY,          // Have enough good samples
        REJECTED        // Samples rejected due to scatter/accuracy
    }

    /**
     * Adds a new GPS sample to the buffer with enhanced NMEA filtering.
     * Returns the current state after processing.
     *
     * PHASE 1 ENHANCEMENT:
     * Now extracts and uses HDOP, satellite count, and fix quality from Location extras.
     * These are populated by Android's FusedLocationProvider from NMEA data.
     */
    fun addSample(location: Location): State {
        totalSamplesReceived++

        // ============================================================================
        // STEP 1: Basic accuracy filter (original)
        // ============================================================================
        if (location.accuracy > maxAccuracyThreshold) {
            samplesRejectedByAccuracy++
            Log.d(TAG, "Sample rejected: accuracy ${location.accuracy}m exceeds threshold")
            FileLogger.gps("Sample REJECTED: accuracy ${location.accuracy}m > ${maxAccuracyThreshold}m")
            return state
        }

        // ============================================================================
        // STEP 2: Extract NMEA quality data from Location extras
        // ============================================================================
        val extras = location.extras

        // HDOP (Horizontal Dilution of Precision)
        // Android provides this in extras when available from NMEA
        val hdop = extras?.getFloat("hdop", -1f)
            ?: extras?.getFloat("horizontalAccuracy", -1f)  // Alternative key
            ?: -1f

        // Satellite count used in fix
        val satelliteCount = extras?.getInt("satellites", -1)
            ?: extras?.getInt("satellitesUsed", -1)
            ?: extras?.getInt("numSatellites", -1)
            ?: -1

        // Fix quality (from NMEA GGA sentence)
        val fixQuality = extras?.getInt("fixQuality", FIX_GPS)
            ?: extras?.getInt("gpsFixQuality", FIX_GPS)
            ?: FIX_GPS  // Default to standard GPS if not available

        // ============================================================================
        // STEP 3: HDOP filtering (NEW - Phase 1)
        // ============================================================================
        if (hdop > 0 && hdop > HDOP_MAX) {
            samplesRejectedByHdop++
            Log.d(TAG, "Sample rejected: HDOP ${hdop} exceeds max ($HDOP_MAX)")
            FileLogger.gps("Sample REJECTED: HDOP $hdop > $HDOP_MAX")
            return state
        }

        // ============================================================================
        // STEP 4: Satellite count filtering (NEW - Phase 1)
        // ============================================================================
        if (satelliteCount > 0 && satelliteCount < SATELLITES_MIN) {
            samplesRejectedBySatellites++
            Log.d(TAG, "Sample rejected: Only $satelliteCount satellites (min: $SATELLITES_MIN)")
            FileLogger.gps("Sample REJECTED: satellites $satelliteCount < $SATELLITES_MIN")
            return state
        }

        // ============================================================================
        // STEP 5: Calculate combined quality score (NEW - Phase 1)
        // ============================================================================
        val qualityScore = calculateQualityScore(location.accuracy, hdop, satelliteCount, fixQuality)

        // ============================================================================
        // STEP 6: Create enhanced sample and add to buffers
        // ============================================================================
        val enhancedSample = EnhancedSample(
            location = location,
            hdop = if (hdop > 0) hdop else 99f,  // Unknown HDOP stored as 99
            satelliteCount = if (satelliteCount > 0) satelliteCount else 0,
            fixQuality = fixQuality,
            qualityScore = qualityScore
        )

        buffer.add(location)
        enhancedBuffer.add(enhancedSample)

        Log.d(TAG, "✅ Sample added: ${buffer.size}/$requiredSampleCount")
        Log.d(TAG, "   Accuracy: ${location.accuracy}m, HDOP: ${if (hdop > 0) hdop else "N/A"}, " +
                "Sats: ${if (satelliteCount > 0) satelliteCount else "N/A"}, " +
                "Fix: ${getFixQualityName(fixQuality)}, Score: ${"%.2f".format(qualityScore)}")
        FileLogger.gps("Sample accepted: acc=${location.accuracy}m, hdop=$hdop, sats=$satelliteCount, score=${"%.2f".format(qualityScore)}")

        // Prevent buffer overflow
        if (buffer.size > MAX_BUFFER_SIZE) {
            Log.w(TAG, "Buffer overflow - removing oldest sample")
            buffer.removeAt(0)
            enhancedBuffer.removeAt(0)
        }

        // Check if we have enough samples
        if (buffer.size >= requiredSampleCount) {
            return evaluateBuffer()
        }

        val newState = State.COLLECTING
        state = newState
        return newState
    }

    /**
     * Calculates a combined quality score (0.0 to 1.0, higher = better).
     *
     * FORMULA:
     * score = (accuracyScore * 0.4) + (hdopScore * 0.3) + (satelliteScore * 0.2) + (fixScore * 0.1)
     *
     * This weights accuracy highest (40%), then HDOP (30%), satellites (20%), and fix type (10%).
     */
    private fun calculateQualityScore(
        accuracy: Float,
        hdop: Float,
        satelliteCount: Int,
        fixQuality: Int
    ): Float {
        // Accuracy score: 0-1 (10m = 0, 0m = 1)
        val accuracyScore = (1.0f - (accuracy / maxAccuracyThreshold)).coerceIn(0f, 1f)

        // HDOP score: 0-1 (8 = 0, 1 = 1)
        val hdopScore = if (hdop > 0) {
            (1.0f - ((hdop - 1f) / (HDOP_MAX - 1f))).coerceIn(0f, 1f)
        } else {
            0.5f  // Unknown HDOP gets neutral score
        }

        // Satellite score: 0-1 (4 = 0, 12 = 1)
        val satelliteScore = if (satelliteCount > 0) {
            ((satelliteCount - SATELLITES_MIN).toFloat() / (SATELLITES_EXCELLENT - SATELLITES_MIN)).coerceIn(0f, 1f)
        } else {
            0.5f  // Unknown satellite count gets neutral score
        }

        // Fix quality score
        val fixScore = when (fixQuality) {
            FIX_RTK_FIXED -> 1.0f
            FIX_RTK_FLOAT -> 0.9f
            FIX_DGPS -> 0.7f
            FIX_GPS -> 0.5f
            else -> 0.0f
        }

        // Weighted combination
        return (accuracyScore * 0.4f) + (hdopScore * 0.3f) + (satelliteScore * 0.2f) + (fixScore * 0.1f)
    }

    /**
     * Returns human-readable fix quality name.
     */
    private fun getFixQualityName(fixQuality: Int): String {
        return when (fixQuality) {
            FIX_INVALID -> "Invalid"
            FIX_GPS -> "GPS"
            FIX_DGPS -> "DGPS"
            FIX_RTK_FIXED -> "RTK-Fixed"
            FIX_RTK_FLOAT -> "RTK-Float"
            else -> "Unknown($fixQuality)"
        }
    }

    /**
     * Evaluates the buffer to determine if samples are suitable for anchor placement.
     */
    private fun evaluateBuffer(): State {
        if (buffer.size < requiredSampleCount) {
            state = State.COLLECTING
            return state
        }

        // Take the most recent N samples for evaluation
        val recentSamples = buffer.takeLast(requiredSampleCount)

        // Check cluster tightness
        if (!isClusterTight(recentSamples)) {
            Log.w(TAG, "Cluster not tight - scatter > ${maxScatterDistance}m")

            // Remove oldest sample and try again
            if (buffer.size > requiredSampleCount) {
                buffer.removeAt(0)
                enhancedBuffer.removeAt(0)
                return evaluateBuffer() // Recursive check
            }
            FileLogger.gps("Buffer REJECTED: scatter > ${maxScatterDistance}m")
            state = State.REJECTED
            return state
        }

        // Cluster is tight and we have enough samples
        state = State.READY
        Log.d(TAG, "✅ Buffer ready - ${recentSamples.size} samples with tight cluster")
        FileLogger.gps("Buffer READY: ${recentSamples.size} samples, cluster tight")
        return state
    }

    /**
     * Checks if all samples in the buffer form a tight cluster.
     * Returns true if all samples are within maxScatterDistance of the weighted center.
     */
    private fun isClusterTight(samples: List<Location>): Boolean {
        if (samples.isEmpty()) return false

        val center = calculateWeightedAverage(samples)

        for (loc in samples) {
            val distance = ArUtils.distanceMeters(
                center.latitude, center.longitude,
                loc.latitude, loc.longitude
            )

            if (distance > maxScatterDistance) {
                Log.d(TAG, "Outlier detected: ${distance.toInt()}m from center")
                return false
            }
        }

        return true
    }

    /**
     * Calculates a weighted average of all samples in the buffer.
     *
     * PHASE 1 ENHANCEMENT:
     * Now uses quality score for weighting instead of just accuracy.
     * This incorporates HDOP, satellite count, and fix quality into the average.
     */
    fun calculateWeightedAverage(): Location? {
        if (buffer.isEmpty()) return null

        val samples = if (buffer.size > requiredSampleCount) {
            buffer.takeLast(requiredSampleCount)
        } else {
            buffer
        }

        val enhancedSamples = if (enhancedBuffer.size > requiredSampleCount) {
            enhancedBuffer.takeLast(requiredSampleCount)
        } else {
            enhancedBuffer
        }

        return calculateWeightedAverageEnhanced(samples, enhancedSamples)
    }

    /**
     * Internal weighted average calculation for a specific sample list.
     * Uses enhanced quality scores when available.
     */
    private fun calculateWeightedAverage(samples: List<Location>): Location {
        var totalLat = 0.0
        var totalLng = 0.0
        var totalAlt = 0.0
        var totalWeight = 0.0
        var avgAcc = 0.0
        var altitudeCount = 0

        for (loc in samples) {
            // Weight inversely proportional to accuracy squared
            val safeAccuracy = loc.accuracy.coerceAtLeast(0.5f)
            val weight = 1.0 / (safeAccuracy  * safeAccuracy )

            totalLat += loc.latitude * weight
            totalLng += loc.longitude * weight
            totalWeight += weight
            avgAcc += loc.accuracy

            // Handle altitude separately (not all samples may have it)
            if (loc.hasAltitude()) {
                totalAlt += loc.altitude * weight
                altitudeCount++
            }
        }

        val result = Location("WeightedAverage")
        result.latitude = totalLat / totalWeight
        result.longitude = totalLng / totalWeight
        result.accuracy = (avgAcc / samples.size).toFloat()

        // Set altitude if we have any altitude data
        if (altitudeCount > 0) {
            result.altitude = totalAlt / totalWeight
        }

        Log.d(TAG, "Weighted average: ${samples.size} samples, accuracy: ${result.accuracy}m, altitude: ${if (result.hasAltitude()) "${"%.1f".format(result.altitude)}m" else "N/A"}")

        return result
    }

    /**
     * PHASE 1 ENHANCEMENT: Enhanced weighted average using quality scores.
     *
     * Weight formula: w = qualityScore² / accuracy²
     * This combines the original accuracy weighting with the new quality score,
     * giving much higher weight to samples with good HDOP, many satellites, and better fix types.
     */
    private fun calculateWeightedAverageEnhanced(
        samples: List<Location>,
        enhancedSamples: List<EnhancedSample>
    ): Location {
        var totalLat = 0.0
        var totalLng = 0.0
        var totalAlt = 0.0
        var totalWeight = 0.0
        var avgAcc = 0.0
        var altitudeCount = 0

        for (i in samples.indices) {
            val loc = samples[i]
            val enhanced = enhancedSamples.getOrNull(i)

            // Enhanced weight: quality score² / accuracy²
            // Quality score ranges 0-1, so squaring it emphasizes good samples
            val qualityMultiplier = enhanced?.let { (it.qualityScore * it.qualityScore).coerceAtLeast(0.1f) } ?: 1f
            val weight = qualityMultiplier / (loc.accuracy * loc.accuracy)

            totalLat += loc.latitude * weight
            totalLng += loc.longitude * weight
            totalWeight += weight
            avgAcc += loc.accuracy

            // Handle altitude separately
            if (loc.hasAltitude()) {
                totalAlt += loc.altitude * weight
                altitudeCount++
            }
        }

        val result = Location("EnhancedWeightedAverage")
        result.latitude = totalLat / totalWeight
        result.longitude = totalLng / totalWeight
        result.accuracy = (avgAcc / samples.size).toFloat()

        // Set altitude if we have any altitude data
        if (altitudeCount > 0) {
            result.altitude = totalAlt / totalWeight
        }

        // Calculate average quality score for logging
        val avgQuality = enhancedSamples.map { it.qualityScore }.average()

        Log.d(TAG, "Enhanced weighted average: ${samples.size} samples")
        Log.d(TAG, "   Result accuracy: ${result.accuracy}m")
        Log.d(TAG, "   Avg quality score: ${"%.2f".format(avgQuality)}")
        Log.d(TAG, "   Altitude: ${if (result.hasAltitude()) "${"%.1f".format(result.altitude)}m" else "N/A"}")

        return result
    }

    /**
     * Gets the current state of the buffer.
     */
    fun getState(): State = state

    /**
     * Gets the number of samples currently in the buffer.
     */
    fun getSampleCount(): Int = buffer.size

    /**
     * Gets the progress as a percentage (0-100).
     */
    fun getProgress(): Int {
        return ((buffer.size.toFloat() / requiredSampleCount.toFloat()) * 100).toInt().coerceAtMost(100)
    }

    /**
     * Gets the last sample added (for debugging).
     */
    fun getLastSample(): Location? = buffer.lastOrNull()

    /**
     * Gets the last enhanced sample (for debugging).
     */
    fun getLastEnhancedSample(): EnhancedSample? = enhancedBuffer.lastOrNull()

    /**
     * Gets the average quality score of current samples.
     */
    fun getAverageQualityScore(): Float {
        if (enhancedBuffer.isEmpty()) return 0f
        return enhancedBuffer.map { it.qualityScore }.average().toFloat()
    }

    /**
     * Clears all samples and resets state.
     */
    fun reset() {
        buffer.clear()
        enhancedBuffer.clear()
        state = State.COLLECTING
        totalSamplesReceived = 0
        samplesRejectedByAccuracy = 0
        samplesRejectedByHdop = 0
        samplesRejectedBySatellites = 0
        Log.d(TAG, "Buffer reset")
    }

    /**
     * Gets diagnostic information about the buffer state.
     * PHASE 1 ENHANCEMENT: Now includes NMEA quality statistics.
     */
    fun getDiagnostics(): String {
        if (buffer.isEmpty()) {
            return "Buffer: Empty"
        }

        val avgAccuracy = buffer.map { it.accuracy }.average()
        val minAccuracy = buffer.minOfOrNull { it.accuracy } ?: 0f
        val maxAccuracy = buffer.maxOfOrNull { it.accuracy } ?: 0f

        // Enhanced statistics
        val avgQuality = if (enhancedBuffer.isNotEmpty()) {
            enhancedBuffer.map { it.qualityScore }.average()
        } else 0.0

        val avgHdop = enhancedBuffer.filter { it.hdop < 99f }.map { it.hdop }.average().let {
            if (it.isNaN()) "N/A" else "%.1f".format(it)
        }

        val avgSatellites = enhancedBuffer.filter { it.satelliteCount > 0 }.map { it.satelliteCount }.average().let {
            if (it.isNaN()) "N/A" else "%.0f".format(it)
        }

        return """
            |╔═══════════════════════════════════════
            |║ GPS BUFFER DIAGNOSTICS (Phase 1)
            |╠═══════════════════════════════════════
            |║ Samples: ${buffer.size}/$requiredSampleCount
            |║ State: $state
            |║ Progress: ${getProgress()}%
            |╠═══════════════════════════════════════
            |║ ACCURACY
            |║   Avg: ${"%.1f".format(avgAccuracy)}m
            |║   Range: ${"%.1f".format(minAccuracy)}m - ${"%.1f".format(maxAccuracy)}m
            |╠═══════════════════════════════════════
            |║ NMEA QUALITY (Phase 1)
            |║   Avg HDOP: $avgHdop
            |║   Avg Satellites: $avgSatellites
            |║   Avg Quality Score: ${"%.2f".format(avgQuality)}
            |╠═══════════════════════════════════════
            |║ REJECTION STATS
            |║   Total received: $totalSamplesReceived
            |║   Rejected (accuracy): $samplesRejectedByAccuracy
            |║   Rejected (HDOP): $samplesRejectedByHdop
            |║   Rejected (satellites): $samplesRejectedBySatellites
            |╚═══════════════════════════════════════
        """.trimMargin()
    }

    /**
     * Forces the buffer to evaluate and return the best location even if not ideal.
     * Use this only when user manually forces start.
     */
    fun forceGetBestLocation(): Location? {
        return if (buffer.isNotEmpty()) {
            calculateWeightedAverage()
        } else {
            null
        }
    }
}