package com.example.orientar.navigation.ar

import android.location.Location
import com.example.orientar.navigation.logic.ArUtils
import com.example.orientar.navigation.logic.Coordinate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import com.example.orientar.navigation.util.FileLogger

/**
 * SpherePositionCalculator - Calculates sphere positions on background thread.
 *
 * RUNS ON: Dedicated background thread
 * PROVIDES: Pre-calculated sphere positions to main thread
 *
 * FEATURES:
 * 1. Background position calculation (no main thread blocking)
 * 2. Distance-based scaling (larger spheres at distance)
 * 3. Terrain-aware height adjustment
 * 4. Position caching for incremental updates
 */
class SpherePositionCalculator(
    private val terrainProfiler: TerrainProfiler
) {
    companion object {
        private const val TAG = "SphereCalc"

        // Render distance cutoff from ARPerformanceConfig.MAX_RENDER_DISTANCE
    }

    // ========================================================================================
    // DATA STRUCTURES
    // ========================================================================================

    data class SpherePosition(
        val index: Int,               // Route coordinate index
        val x: Float,                 // AR X position
        val y: Float,                 // AR Y position (height)
        val z: Float,                 // AR Z position
        val radius: Float,            // Sphere radius (scaled by distance)
        val isMilestone: Boolean,
        val distanceFromAnchor: Float,
        val terrainConfidence: Float
    )

    data class CalculationResult(
        val positions: List<SpherePosition>,
        val calculationTimeMs: Long,
        val yawOffset: Double,
        val anchorLocation: Location
    )

    // ========================================================================================
    // STATE
    // ========================================================================================

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SphereCalculator").apply {
            priority = Thread.NORM_PRIORITY - 1  // Slightly lower priority
        }
    }

    private val positionCache = ConcurrentHashMap<Int, SpherePosition>()
    private val isCalculating = AtomicBoolean(false)
    private var lastYawOffset: Double = 0.0
    private var lastAnchorLocation: Location? = null

    // Callback for when calculation completes
    private var onCalculationComplete: ((CalculationResult) -> Unit)? = null
    // Error callback for calculation failures
    private var onCalculationError: ((String) -> Unit)? = null

    /**
     * Set callback for calculation errors.
     * Called on background thread - use runOnUiThread for UI updates.
     */
    fun setOnCalculationError(callback: (String) -> Unit) {
        onCalculationError = callback
    }

    // ========================================================================================
    // PUBLIC API
    // ========================================================================================

    /**
     * Set callback for calculation completion.
     * Called on background thread - use runOnUiThread to update UI.
     */
    fun setOnCalculationComplete(callback: (CalculationResult) -> Unit) {
        onCalculationComplete = callback
    }

    /**
     * Calculate all sphere positions asynchronously.
     * Results delivered via callback.
     */
    fun calculatePositionsAsync(
        anchorLocation: Location,
        routeCoords: List<Coordinate>,
        milestoneIndices: Set<Int>,
        yawOffset: Double,
        anchorType: String
    ) {
        if (isCalculating.get()) {
            FileLogger.d(TAG, "Calculation already in progress, skipping")
            FileLogger.ar("Position calc skipped: already in progress")
            return
        }

        isCalculating.set(true)

        executor.execute {
            try {
                val startTime = System.currentTimeMillis()

                val positions = calculatePositions(
                    anchorLocation, routeCoords, milestoneIndices, yawOffset, anchorType
                )

                val elapsed = System.currentTimeMillis() - startTime

                val result = CalculationResult(
                    positions = positions,
                    calculationTimeMs = elapsed,
                    yawOffset = yawOffset,
                    anchorLocation = anchorLocation
                )

                lastYawOffset = yawOffset
                lastAnchorLocation = anchorLocation

                FileLogger.d(TAG, "Calculated ${positions.size} positions in ${elapsed}ms")
                FileLogger.perf("Position calculation", elapsed, 50L)  // Warn if > 50ms

                onCalculationComplete?.invoke(result)

            } catch (e: Exception) {
                FileLogger.e(TAG, "Calculation failed: ${e.message}")
                FileLogger.e("AR", "Position calculation FAILED: ${e.message}")
                onCalculationError?.invoke(e.message ?: "Unknown error")  // ADD THIS
            } finally {
                isCalculating.set(false)
            }
        }
    }

    /**
     * Update positions incrementally for small yaw changes.
     * Much faster than full recalculation.
     */
    fun updatePositionsForYawChange(
        newYawOffset: Double,
        callback: (List<SpherePosition>) -> Unit
    ) {
        val yawDelta = newYawOffset - lastYawOffset

        // If change is too small, skip
        if (abs(yawDelta) < ARPerformanceConfig.MIN_ANGLE_CHANGE_FOR_RENDER) {
            return
        }

        // If change is too large, need full recalculation
        if (abs(yawDelta) > 30.0) {
            FileLogger.d(TAG, "Yaw change too large ($yawDelta°), need full recalc")
            FileLogger.ar("Yaw change ${String.format("%.1f", yawDelta)}° - full recalc needed")
            return
        }

        executor.execute {
            try {
                val yawDeltaRad = Math.toRadians(yawDelta)
                val cosYaw = cos(yawDeltaRad).toFloat()
                val sinYaw = sin(yawDeltaRad).toFloat()

                val updatedPositions = positionCache.values.map { pos ->
                    // Rotate X and Z around Y axis
                    val newX = pos.x * cosYaw - pos.z * sinYaw
                    val newZ = pos.x * sinYaw + pos.z * cosYaw

                    pos.copy(x = newX, z = newZ)
                }

                // Update cache
                updatedPositions.forEach { positionCache[it.index] = it }

                lastYawOffset = newYawOffset

                callback(updatedPositions)

            } catch (e: Exception) {
                FileLogger.e(TAG, "Incremental update failed: ${e.message}")
            }
        }
    }

    /**
     * Get cached position for a specific index.
     */
    fun getCachedPosition(index: Int): SpherePosition? = positionCache[index]

    /**
     * Clear all cached positions.
     */
    fun clearCache() {
        positionCache.clear()
        lastYawOffset = 0.0
        lastAnchorLocation = null
    }

    /**
     * Shutdown the executor.
     */
    fun shutdown() {
        executor.shutdown()
    }

    // ========================================================================================
    // PRIVATE METHODS
    // ========================================================================================

    private fun calculatePositions(
        anchorLocation: Location,
        routeCoords: List<Coordinate>,
        milestoneIndices: Set<Int>,
        yawOffset: Double,
        anchorType: String
    ): List<SpherePosition> {
        val positions = mutableListOf<SpherePosition>()

        positionCache.clear()

        for ((index, coord) in routeCoords.withIndex()) {
            // Check distance before calculating full position
            val distance = ArUtils.distanceMeters(
                anchorLocation.latitude, anchorLocation.longitude,
                coord.lat, coord.lng
            )

            // Skip points that are too far to render meaningfully
            // BUG-006 FIX: Use centralized constant
            if (distance > ARPerformanceConfig.MAX_RENDER_DISTANCE) {
                continue  // Skip, don't compress
            }

            val position = calculateSinglePosition(
                index = index,
                coord = coord,
                anchorLocation = anchorLocation,
                yawOffset = yawOffset,
                isMilestone = milestoneIndices.contains(index),
                anchorType = anchorType
            )

            positions.add(position)
            positionCache[index] = position
        }

        return positions
    }

    private fun calculateSinglePosition(
        index: Int,
        coord: Coordinate,
        anchorLocation: Location,
        yawOffset: Double,
        isMilestone: Boolean,
        anchorType: String
    ): SpherePosition {
        // ========================================================================
        // STEP 1: Calculate X, Z position (horizontal)
        // ========================================================================
        val (x, z) = ArUtils.convertGpsToArPosition(
            userLoc = anchorLocation,
            targetLat = coord.lat,
            targetLng = coord.lng,
            yawOffsetDeg = yawOffset
        )

        val distanceFromAnchor = sqrt(x * x + z * z)
        val bearingFromAnchor = Math.toDegrees(atan2(x.toDouble(), -z.toDouble())).toFloat()

        // ========================================================================
        // STEP 2: Calculate Y position (height) with terrain adjustment
        // ========================================================================
        val terrainEstimate = if (ARPerformanceConfig.ENABLE_TERRAIN_PROFILING) {
            terrainProfiler.estimateTerrain(
                coord.lat, coord.lng, distanceFromAnchor, bearingFromAnchor
            )
        } else {
            TerrainProfiler.TerrainEstimate(0f, 0.5f, 0f, 0f)
        }

        // Base height depends on anchor type
        val baseHeight = when (anchorType.lowercase()) {
            "plane" -> ARPerformanceConfig.BASE_SPHERE_HEIGHT
            "depth" -> ARPerformanceConfig.BASE_SPHERE_HEIGHT
            "camera" -> ARPerformanceConfig.BASE_SPHERE_HEIGHT + 0.2f
            "instant" -> ARPerformanceConfig.BASE_SPHERE_HEIGHT + 0.3f
            else -> ARPerformanceConfig.BASE_SPHERE_HEIGHT + 0.1f
        }

        // Apply terrain adjustment
        val terrainAdjustedHeight = baseHeight + terrainEstimate.heightAdjustment

        // Milestone boost
        val milestoneBoost = if (isMilestone) 0.3f else 0f

        // Final Y with clamping
        val finalY = (terrainAdjustedHeight + milestoneBoost).coerceIn(
            ARPerformanceConfig.MIN_SPHERE_HEIGHT,
            ARPerformanceConfig.MAX_SPHERE_HEIGHT
        )

        // ========================================================================
        // STEP 3: Calculate radius (distance-based scaling for visibility)
        // ========================================================================
        val baseRadius = if (isMilestone) {
            ARPerformanceConfig.MILESTONE_SPHERE_RADIUS
        } else {
            ARPerformanceConfig.PATH_SPHERE_RADIUS
        }

        val scaledRadius = calculateScaledRadius(baseRadius, distanceFromAnchor)

        return SpherePosition(
            index = index,
            x = x,
            y = finalY,
            z = z,
            radius = scaledRadius,
            isMilestone = isMilestone,
            distanceFromAnchor = distanceFromAnchor,
            terrainConfidence = terrainEstimate.confidence
        )
    }

    /**
     * Calculate scaled radius based on distance.
     * Farther spheres are larger to maintain visual size.
     */
    private fun calculateScaledRadius(baseRadius: Float, distance: Float): Float {
        if (distance <= ARPerformanceConfig.SCALE_START_DISTANCE) {
            return baseRadius
        }

        // Linear interpolation between start distance and max distance
        val t = ((distance - ARPerformanceConfig.SCALE_START_DISTANCE) /
                (ARPerformanceConfig.SCALE_MAX_DISTANCE - ARPerformanceConfig.SCALE_START_DISTANCE))
            .coerceIn(0f, 1f)

        val scaleFactor = 1f + t * (ARPerformanceConfig.MAX_DISTANCE_SCALE - 1f)

        return baseRadius * scaleFactor
    }
}