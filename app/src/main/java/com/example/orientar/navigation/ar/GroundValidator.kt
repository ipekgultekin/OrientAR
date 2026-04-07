package com.example.orientar.navigation.ar

import com.google.ar.core.*
import kotlin.math.abs
import com.example.orientar.navigation.util.FileLogger

/**
 * GroundValidator - Multi-factor validation for ground surface detection.
 *
 * RUNS ON: Can run on any thread (stateless)
 * PROVIDES: Validation results with confidence scores
 *
 * VALIDATION CHECKS:
 * 1. Surface normal (must be roughly vertical)
 * 2. Plane size (must be large enough to be ground)
 * 3. Height reasonableness (must be below camera at expected distance)
 * 4. Tracking quality
 */
object GroundValidator {
    private const val TAG = "GroundValidator"

    // ========================================================================================
    // DATA STRUCTURES
    // ========================================================================================

    data class ValidationResult(
        val isValid: Boolean,
        val confidence: Float,        // 0.0 - 1.0
        val groundY: Float,           // Detected ground Y coordinate
        val failureReasons: List<String>,
        val anchorTypeRecommendation: String  // "Plane", "Depth", "Camera"
    )

    // ========================================================================================
    // PUBLIC API
    // ========================================================================================

    /**
     * Validate a hit result as potential ground surface.
     *
     * @param hitResult The ARCore hit result to validate
     * @param frame Current AR frame (for camera pose)
     * @return ValidationResult with details
     */
    fun validateGroundSurface(hitResult: HitResult, frame: Frame): ValidationResult {
        val failureReasons = mutableListOf<String>()
        var confidence = 1.0f

        val trackable = hitResult.trackable
        val hitPose = hitResult.hitPose
        val cameraPose = frame.camera.pose

        // ========================================================================
        // CHECK 1: Tracking State
        // ========================================================================
        if (trackable.trackingState != TrackingState.TRACKING) {
            failureReasons.add("Not tracking (state: ${trackable.trackingState})")
            return ValidationResult(
                isValid = false,
                confidence = 0f,
                groundY = hitPose.ty(),
                failureReasons = failureReasons,
                anchorTypeRecommendation = "Camera"
            )
        }

        // ========================================================================
        // CHECK 2: Surface Normal (for Planes)
        // ========================================================================
        if (trackable is Plane) {
            val normal = trackable.centerPose.yAxis
            val verticalComponent = normal[1]  // Y component of normal

            if (verticalComponent < ARPerformanceConfig.MIN_SURFACE_NORMAL_Y) {
                failureReasons.add("Surface not horizontal (normal.y = ${"%.2f".format(verticalComponent)})")
                confidence *= 0.5f
            }

            // Check plane type
            if (trackable.type != Plane.Type.HORIZONTAL_UPWARD_FACING) {
                failureReasons.add("Not horizontal upward facing (type: ${trackable.type})")
                confidence *= 0.3f
            }
        }
        // DepthPoint-specific validation (no surface normals/area like Planes)
        if (trackable is DepthPoint) {
            val depthDistance = hitResult.distance

            // Closer depth points are more reliable
            if (depthDistance <= 1.5f) {
                confidence *= 1.1f  // Bonus for close points
            } else if (depthDistance > 3.0f) {
                confidence *= (3.0f / depthDistance).coerceIn(0.5f, 0.9f)
                if (depthDistance > 4.0f) {
                    failureReasons.add("DepthPoint too distant (${String.format("%.1f", depthDistance)}m)")
                }
            }

            // Height validation for DepthPoints
            val heightBelowCameraDP = cameraPose.ty() - hitPose.ty()
            if (heightBelowCameraDP > 0.8f && heightBelowCameraDP < 1.8f) {
                confidence *= 1.1f  // Ideal ground height range
            }

            FileLogger.anchor("DepthPoint: dist=${String.format("%.1f", depthDistance)}m, heightBelow=${String.format("%.1f", heightBelowCameraDP)}m, conf=${String.format("%.2f", confidence)}")
        }

        // ========================================================================
        // CHECK 3: Plane Size (for Planes)
        // ========================================================================
        if (trackable is Plane) {
            val extentX = trackable.extentX
            val extentZ = trackable.extentZ
            val area = extentX * extentZ

            if (area < ARPerformanceConfig.MIN_GROUND_PLANE_AREA) {
                failureReasons.add("Surface too small (area = ${"%.1f".format(area)}m²)")
                confidence *= 0.6f
            }

            // Larger planes are more likely to be ground
            confidence *= when {
                area > 10f -> 1.0f
                area > 5f -> 0.9f
                area > 2f -> 0.8f
                else -> 0.6f
            }
        }

        // ========================================================================
        // CHECK 4: Height Reasonableness
        // ========================================================================
        val heightBelowCamera = cameraPose.ty() - hitPose.ty()

        if (heightBelowCamera < ARPerformanceConfig.MIN_GROUND_HEIGHT_BELOW_CAMERA) {
            failureReasons.add("Too close to camera height (diff = ${"%.2f".format(heightBelowCamera)}m)")
            confidence *= 0.4f
        }

        if (heightBelowCamera > ARPerformanceConfig.MAX_GROUND_HEIGHT_BELOW_CAMERA) {
            failureReasons.add("Too far below camera (diff = ${"%.2f".format(heightBelowCamera)}m)")
            confidence *= 0.4f
        }

        // Ideal height is 1.0-1.5m below camera
        val idealHeightDiff = abs(heightBelowCamera - 1.25f)
        confidence *= (1.0f - idealHeightDiff / 2.0f).coerceIn(0.5f, 1.0f)

        // ========================================================================
        // CHECK 5: Hit Distance
        // ========================================================================
        val hitDistance = hitResult.distance

        if (hitDistance > 5.0f) {
            // Far hits are less reliable
            confidence *= (5.0f / hitDistance).coerceIn(0.5f, 1.0f)
        }

        // ========================================================================
        // CHECK 6: Plane Stability (for Planes)
        // ========================================================================
        if (trackable is Plane) {
            // Subsumed planes are more stable (merged with other planes)
            if (trackable.subsumedBy != null) {
                confidence *= 1.1f  // Bonus for stable plane
            }

            // Check if hit is within plane polygon
            if (!trackable.isPoseInPolygon(hitPose)) {
                failureReasons.add("Hit pose outside plane polygon")
                confidence *= 0.7f
            }
        }

        // ========================================================================
        // FINAL DECISION
        // ========================================================================
        confidence = confidence.coerceIn(0f, 1f)

        val isValid = confidence >= 0.5f && failureReasons.size < 3

        val anchorType = when {
            trackable is DepthPoint -> "Depth"
            trackable is Plane && confidence >= 0.7f -> "Plane"
            trackable is Plane -> "Plane-LowConf"
            else -> "Camera"
        }

        if (failureReasons.isNotEmpty()) {
            FileLogger.anchor("Ground validation: valid=$isValid, conf=${"%.2f".format(confidence)}, reasons=$failureReasons")
        }

        return ValidationResult(
            isValid = isValid,
            confidence = confidence,
            groundY = hitPose.ty(),
            failureReasons = failureReasons,
            anchorTypeRecommendation = anchorType
        )
    }

    /**
     * Find the best ground hit from multiple hit results.
     *
     * @param hitResults List of ARCore hit results
     * @param frame Current AR frame
     * @return Best ValidationResult, or null if no valid ground found
     */
    fun findBestGroundHit(hitResults: List<HitResult>, frame: Frame): Pair<HitResult, ValidationResult>? {
        var bestHit: HitResult? = null
        var bestResult: ValidationResult? = null
        var bestScore = 0f

        for (hit in hitResults) {
            val result = validateGroundSurface(hit, frame)

            if (result.isValid && result.confidence > bestScore) {
                bestHit = hit
                bestResult = result
                bestScore = result.confidence
            }
        }

        return if (bestHit != null && bestResult != null) {
            Pair(bestHit, bestResult)
        } else {
            null
        }
    }
}