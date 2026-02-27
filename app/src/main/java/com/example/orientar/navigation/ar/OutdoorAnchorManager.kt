package com.example.orientar.navigation.ar

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.ar.core.*
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.node.AnchorNode
import com.example.orientar.navigation.ar.GroundValidator
import com.example.orientar.navigation.util.FileLogger

/**
 * OutdoorAnchorManager - Optimized anchor creation for outdoor AR navigation.
 *
 * ================================================================================================
 * PROBLEM STATEMENT
 * ================================================================================================
 * Standard ARCore plane detection works poorly outdoors because:
 * 1. Ground surfaces (grass, gravel, concrete) have inconsistent textures
 * 2. Large open areas have insufficient depth variation for plane detection
 * 3. Detected "planes" might be at random heights (curbs, distant surfaces)
 * 4. Direct sunlight causes camera overexposure, reducing feature detection
 *
 * ================================================================================================
 * SOLUTION: MULTI-STRATEGY ANCHOR SYSTEM
 * ================================================================================================
 * This class implements a priority-based anchor creation strategy:
 *
 * 1. DEPTH POINT (Best for outdoor)
 *    - Uses ARCore Depth API (supported on 87%+ devices)
 *    - Works on non-planar and low-texture surfaces
 *    - Provides accurate ground distance via depth-from-motion algorithm
 *
 * 2. PLANE ANCHOR (Standard approach)
 *    - Traditional plane detection
 *    - Works well on flat, textured surfaces
 *    - Includes stabilization delay for better accuracy
 *
 * 3. CAMERA-RELATIVE ESTIMATION (Fallback)
 *    - Uses phone's known height from ground
 *    - User can calibrate for their height
 *    - Reliable when other methods fail
 *
 * ================================================================================================
 * USAGE
 * ================================================================================================
 * ```kotlin
 * // Initialize
 * val outdoorAnchorManager = OutdoorAnchorManager(context, arSceneView)
 *
 * // In onSessionUpdated callback:
 * if (outdoorAnchorManager.isReadyForAnchoring(session, frame)) {
 *     val anchorNode = outdoorAnchorManager.createBestAnchor(session, frame)
 *     if (anchorNode != null) {
 *         // Success - use the anchor
 *         arRenderer.setAnchorNode(anchorNode)
 *     }
 * }
 *
 * // User calibration (if spheres appear too high/low)
 * outdoorAnchorManager.adjustPhoneHeight(+0.1f) // Raise spheres
 * outdoorAnchorManager.adjustPhoneHeight(-0.1f) // Lower spheres
 * ```
 *
 * ================================================================================================
 */
class OutdoorAnchorManager(
    private val context: Context,
    private val arSceneView: ARSceneView
) {
    companion object {
        private const val TAG = "OutdoorAnchor"
        private const val PREFS_NAME = "outdoor_anchor_prefs"
        private const val KEY_PHONE_HEIGHT = "phone_height_from_ground"

        // Default phone height when user holds it for AR navigation (meters)
        // Average: 1.2m (chest level) to 1.5m (eye level)
        private const val DEFAULT_PHONE_HEIGHT = 1.3f

        // Min/max bounds for phone height calibration
        private const val MIN_PHONE_HEIGHT = 0.8f
        private const val MAX_PHONE_HEIGHT = 2.0f

        // Stabilization: minimum time before creating anchor (milliseconds)
        // ARCore needs time to build its world model from visual features
        private const val MIN_STABILIZATION_TIME_MS = 3000L

        // Stabilization: minimum number of tracking planes before anchor creation
        // Having detected planes indicates ARCore has a good understanding of the environment
        private const val MIN_PLANES_FOR_STABILITY = 1

        // Maximum distance for anchor creation from camera (meters)
        private const val MAX_ANCHOR_DISTANCE = 5.0f

        // Downward ray cast angle from camera (degrees from horizontal)
        // 45° looks at ground about 1-2 meters ahead
        private const val DOWNWARD_RAY_ANGLE = 45.0f
    }

    // ========================================================================================
    // STATE
    // ========================================================================================

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var sessionStartTime: Long = 0L
    private var isSessionStarted: Boolean = false
    private var lastCreatedAnchor: AnchorNode? = null

    // Depth API availability (checked once per session)
    private var isDepthSupported: Boolean? = null

    // Current phone height (can be calibrated by user)
    private var phoneHeightFromGround: Float = prefs.getFloat(KEY_PHONE_HEIGHT, DEFAULT_PHONE_HEIGHT)

    // Anchor creation statistics (for debugging)
    private var depthAnchorCount = 0
    private var planeAnchorCount = 0
    private var cameraRelativeAnchorCount = 0

    private var lastUsedAnchorType: String = "None"

    // Trackable caching for performance
    private var cachedPlaneCount: Int = 0
    private var lastTrackableQueryTime: Long = 0
    private val TRACKABLE_QUERY_INTERVAL_MS = 200L

    // ========================================================================================
    // Ground Validation
    // ========================================================================================
    private var lastValidationResult: GroundValidator.ValidationResult? = null
    private var lastGroundY: Float = 0f
    private var lastGroundConfidence: Float = 0f

    /**
     * Get the last detected ground Y coordinate.
     * Used by TerrainProfiler to learn terrain.
     */
    fun getLastGroundY(): Float = lastGroundY

    /**
     * Get the confidence of last ground detection.
     */
    fun getLastGroundConfidence(): Float = lastGroundConfidence

    /**
     * Get the last validation result details.
     */
    fun getLastValidationResult(): GroundValidator.ValidationResult? = lastValidationResult

    // ========================================================================================
    // SESSION LIFECYCLE
    // ========================================================================================

    /**
     * Call this when AR session starts/resumes.
     * Resets timing for stabilization delay.
     */
    fun onSessionStarted() {
        sessionStartTime = System.currentTimeMillis()
        isSessionStarted = true
        isDepthSupported = null  // Will be checked on first frame

        Log.d(TAG, "Session started, stabilization timer reset")
    }

    /**
     * Call this when AR session pauses/stops.
     */
    fun onSessionPaused() {
        isSessionStarted = false
        Log.d(TAG, "Session paused")
    }

    /**
     * Returns whether the AR session has been started.
     * Use this instead of parsing getDiagnostics() string.
     */
    fun isSessionStarted(): Boolean = isSessionStarted

    // ========================================================================================
    // READINESS CHECK
    // ========================================================================================

    /**
     * Get plane count with caching to avoid expensive queries every frame.
     */
    private fun getCachedPlaneCount(frame: Frame): Int {
        val now = System.currentTimeMillis()

        if (now - lastTrackableQueryTime > TRACKABLE_QUERY_INTERVAL_MS) {
            cachedPlaneCount = frame.getUpdatedTrackables(Plane::class.java)
                .count { it.trackingState == TrackingState.TRACKING }
            lastTrackableQueryTime = now
        }

        return cachedPlaneCount
    }

    /**
     * Checks if ARCore is ready for reliable anchor creation.
     *
     * Conditions checked:
     * 1. Camera is actively tracking
     * 2. Minimum stabilization time has passed (or planes detected)
     * 3. Session is in a good state
     *
     * @param session The ARCore session
     * @param frame The current AR frame
     * @return true if ready to create anchor, false otherwise
     */
    fun isReadyForAnchoring(session: Session, frame: Frame): Boolean {
        // Must be tracking
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return false
        }

        // Check stabilization
        val timeSinceStart = System.currentTimeMillis() - sessionStartTime
        val hasMinTime = timeSinceStart >= MIN_STABILIZATION_TIME_MS

        // Check for detected planes (indicates good environment understanding)
        // Using cached query to avoid expensive getAllTrackables every frame
        val trackingPlaneCount = getCachedPlaneCount(frame)
        val hasPlanes = trackingPlaneCount >= MIN_PLANES_FOR_STABILITY

        // Ready if: (minimum time passed) OR (planes detected AND some time passed)
        val isReady = hasMinTime || (hasPlanes && timeSinceStart >= 1500L)

        if (!isReady && timeSinceStart % 1000 < 100) {  // Log once per second
            Log.d(TAG, "Stabilizing: ${timeSinceStart}ms, planes=$trackingPlaneCount, ready=$isReady")
        }

        return isReady
    }

    /**
     * Returns stabilization progress as percentage (0-100).
     * Useful for showing progress UI to user.
     */
    fun getStabilizationProgress(): Int {
        if (!isSessionStarted) return 0

        val elapsed = System.currentTimeMillis() - sessionStartTime
        val progress = (elapsed * 100 / MIN_STABILIZATION_TIME_MS).toInt()
        return progress.coerceIn(0, 100)
    }

    // ========================================================================================
    // ANCHOR CREATION - MAIN ENTRY POINT
    // ========================================================================================

    /**
     * Creates the best possible anchor for outdoor AR navigation.
     *
     * Tries strategies in order of preference:
     * 1. DepthPoint (best for outdoor - uses depth-from-motion)
     * 2. Plane detection (standard approach)
     * 3. Camera-relative estimation (fallback)
     *
     * @param session The ARCore session
     * @param frame The current AR frame
     * @return AnchorNode if successful, null otherwise
     */
    fun createBestAnchor(session: Session, frame: Frame): AnchorNode? {
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Log.w(TAG, "Cannot create anchor - camera not tracking")
            FileLogger.anchor("BLOCKED: camera not tracking")
            return null
        }

        // Check Depth API support (once per session)
        if (isDepthSupported == null) {
            isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            Log.d(TAG, "Depth API supported: $isDepthSupported")
        }

        // Strategy 1: Try DepthPoint anchor (best for outdoor)
        if (isDepthSupported == true) {
            val depthAnchor = tryCreateDepthPointAnchor(session, frame)
            if (depthAnchor != null) {
                depthAnchorCount++
                lastUsedAnchorType = "Depth"  // Track actual last used type
                Log.d(TAG, "✅ Created DepthPoint anchor (#$depthAnchorCount)")
                FileLogger.anchor("Created: DepthPoint #$depthAnchorCount, groundY=${String.format("%.2f", lastGroundY)}")
                lastCreatedAnchor = depthAnchor
                return depthAnchor
            }
        }

        // Strategy 2: Try Plane anchor
        val planeAnchor = tryCreatePlaneAnchor(session, frame)
        if (planeAnchor != null) {
            planeAnchorCount++
            lastUsedAnchorType = "Plane"  // Track actual last used type
            Log.d(TAG, "✅ Created Plane anchor (#$planeAnchorCount)")
            FileLogger.anchor("Created: Plane #$planeAnchorCount, groundY=${String.format("%.2f", lastGroundY)}")
            lastCreatedAnchor = planeAnchor
            return planeAnchor
        }

        // Strategy 3: Camera-relative estimation (fallback)
        val cameraAnchor = tryCreateCameraRelativeAnchor(session, frame)
        if (cameraAnchor != null) {
            cameraRelativeAnchorCount++
            lastUsedAnchorType = "Camera"  // Track actual last used type
            Log.d(TAG, "✅ Created camera-relative anchor (#$cameraRelativeAnchorCount)")
            FileLogger.anchor("Created: CameraRelative #$cameraRelativeAnchorCount")
            lastCreatedAnchor = cameraAnchor
            return cameraAnchor
        }

        Log.w(TAG, "All anchor creation strategies failed")
        FileLogger.e("ANCHOR", "All strategies FAILED")
        return null
    }

    // ========================================================================================
    // STRATEGY 1: DEPTH POINT ANCHOR
    // ========================================================================================

    private fun tryCreateDepthPointAnchor(session: Session, frame: Frame): AnchorNode? {
        val width = arSceneView.width.toFloat()
        val height = arSceneView.height.toFloat()

        if (width <= 0 || height <= 0) return null

        // Test points focused on lower screen (where ground is visible)
        val testPoints = listOf(
            Pair(width * 0.5f, height * 0.70f),  // Lower center - most likely ground
            Pair(width * 0.5f, height * 0.85f),  // Very low - close ground
            Pair(width * 0.5f, height * 0.55f),  // Mid-low
            Pair(width * 0.3f, height * 0.70f),  // Lower left
            Pair(width * 0.7f, height * 0.70f)   // Lower right
        )

        for ((index, point) in testPoints.withIndex()) {
            val (x, y) = point

            try {
                val hitResults = frame.hitTest(x, y)

                // Find DepthPoint hit results
                val depthHits = hitResults.filter { hit ->
                    hit.trackable is DepthPoint &&
                            hit.trackable.trackingState == TrackingState.TRACKING &&
                            hit.distance <= MAX_ANCHOR_DISTANCE
                }

                // ========================================================================
                // PHASE 3: Use GroundValidator to find best ground hit
                // ========================================================================
                for (depthHit in depthHits) {
                    val validation = GroundValidator.validateGroundSurface(depthHit, frame)

                    if (validation.isValid && validation.confidence >= 0.5f) {
                        val anchor = depthHit.createAnchorOrNull()

                        if (anchor != null && anchor.trackingState == TrackingState.TRACKING) {
                            val anchorNode = AnchorNode(arSceneView.engine, anchor)
                            arSceneView.addChildNode(anchorNode)

                            // Store validation result for terrain profiling
                            lastValidationResult = validation
                            lastGroundY = validation.groundY
                            lastGroundConfidence = validation.confidence

                            val pose = anchor.pose
                            Log.d(TAG, "DepthPoint anchor at point #${index + 1}: " +
                                    "pos=(${pose.tx()}, ${pose.ty()}, ${pose.tz()}), " +
                                    "distance=${depthHit.distance}m, " +
                                    "confidence=${validation.confidence}")

                            return anchorNode
                        } else {
                            anchor?.detach()
                        }
                    } else {
                        Log.d(TAG, "DepthPoint rejected at point #${index + 1}: " +
                                "confidence=${validation.confidence}, reasons=${validation.failureReasons}")
                        FileLogger.anchor("DepthPoint rejected #${index + 1}: confidence too low")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DepthPoint hit test failed at point #${index + 1}: ${e.message}")
            }
        }

        return null
    }

    // ========================================================================================
    // STRATEGY 2: PLANE ANCHOR
    // ========================================================================================

    /**
     * Creates an anchor on a detected horizontal plane.
     *
     * This is the standard ARCore approach but with improvements:
     * - Prioritizes HORIZONTAL_UPWARD_FACING planes (ground)
     * - Tests multiple screen points to find ground plane
     * - Validates plane pose is within polygon
     *
     * @param session The ARCore session
     * @param frame The current AR frame
     * @return AnchorNode if successful, null otherwise
     */
    private fun tryCreatePlaneAnchor(session: Session, frame: Frame): AnchorNode? {
        val width = arSceneView.width.toFloat()
        val height = arSceneView.height.toFloat()

        if (width <= 0 || height <= 0) return null

        // Test points in priority order (center first, then lower screen)
        val testPoints = listOf(
            Pair(width * 0.5f, height * 0.5f),   // Center
            Pair(width * 0.5f, height * 0.70f),  // Lower center
            Pair(width * 0.3f, height * 0.5f),   // Left
            Pair(width * 0.7f, height * 0.5f),   // Right
            Pair(width * 0.5f, height * 0.85f),  // Very low
            Pair(width * 0.3f, height * 0.70f),  // Lower left
            Pair(width * 0.7f, height * 0.70f)   // Lower right
        )

        for ((index, point) in testPoints.withIndex()) {
            val (x, y) = point

            try {
                val hitResults = frame.hitTest(x, y)

                // ========================================================================
                // PHASE 3: Use GroundValidator to find best plane hit
                // ========================================================================
                val planeHits = hitResults.filter { hit ->
                    val trackable = hit.trackable
                    trackable is Plane &&
                            trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                            trackable.trackingState == TrackingState.TRACKING
                }

                // Find best validated ground hit
                val bestHit = GroundValidator.findBestGroundHit(planeHits, frame)

                if (bestHit != null) {
                    val (hit, validation) = bestHit
                    val anchor = hit.createAnchorOrNull()

                    if (anchor != null && anchor.trackingState == TrackingState.TRACKING) {
                        val anchorNode = AnchorNode(arSceneView.engine, anchor)
                        arSceneView.addChildNode(anchorNode)

                        // Store validation result for terrain profiling
                        lastValidationResult = validation
                        lastGroundY = validation.groundY
                        lastGroundConfidence = validation.confidence

                        val pose = anchor.pose
                        Log.d(TAG, "Plane anchor at point #${index + 1}: " +
                                "pos=(${pose.tx()}, ${pose.ty()}, ${pose.tz()}), " +
                                "confidence=${validation.confidence}")

                        return anchorNode
                    } else {
                        anchor?.detach()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Plane hit test failed at point #${index + 1}: ${e.message}")
            }
        }

        return null
    }

    // ========================================================================================
    // STRATEGY 3: CAMERA-RELATIVE ANCHOR
    // ========================================================================================

    /**
     * Creates an anchor at estimated ground level using camera pose.
     *
     * This is the fallback strategy when DepthPoint and Plane detection fail.
     * It uses the known phone height from ground (calibratable by user) to
     * estimate where the ground is relative to the camera.
     *
     * ASSUMPTIONS:
     * - User holds phone at chest/eye level (~1.3m from ground)
     * - User is standing on relatively flat ground
     * - Phone height can be calibrated if spheres appear too high/low
     *
     * @param session The ARCore session
     * @param frame The current AR frame
     * @return AnchorNode if successful, null otherwise
     */
    private fun tryCreateCameraRelativeAnchor(session: Session, frame: Frame): AnchorNode? {
        try {
            val cameraPose = frame.camera.pose

            // Camera position
            val cameraX = cameraPose.tx()
            val cameraY = cameraPose.ty()
            val cameraZ = cameraPose.tz()

            // Estimate ground Y position
            // Camera Y - phone height = ground Y
            val groundY = cameraY - phoneHeightFromGround

            // Get camera forward direction for placement
            val forward = cameraPose.zAxis  // Negative Z is forward in OpenGL convention

            // Place anchor 1 meter in front of camera, at estimated ground level
            val anchorX = cameraX - forward[0] * 1.0f
            val anchorZ = cameraZ - forward[2] * 1.0f

            // Create pose with identity rotation (upright)
            val anchorPose = Pose(
                floatArrayOf(anchorX, groundY, anchorZ),
                floatArrayOf(0f, 0f, 0f, 1f)  // Identity quaternion
            )

            val anchor = session.createAnchor(anchorPose)

            if (anchor.trackingState == TrackingState.TRACKING) {
                val anchorNode = AnchorNode(arSceneView.engine, anchor)
                arSceneView.addChildNode(anchorNode)

                Log.d(TAG, "Camera-relative anchor created: " +
                        "cameraY=$cameraY, phoneHeight=$phoneHeightFromGround, " +
                        "groundY=$groundY, pos=($anchorX, $groundY, $anchorZ)")

                return anchorNode
            } else {
                anchor.detach()
                Log.w(TAG, "Camera-relative anchor created but not tracking")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera-relative anchor creation failed: ${e.message}")
        }

        return null
    }

    // ========================================================================================
    // USER CALIBRATION
    // ========================================================================================

    /**
     * Adjusts the estimated phone height from ground.
     * Call this when user indicates spheres are too high or too low.
     *
     * @param adjustment Positive to raise spheres, negative to lower them (meters)
     */
    @Suppress("unused")
    fun adjustPhoneHeight(adjustment: Float) {
        val oldHeight = phoneHeightFromGround
        phoneHeightFromGround = (phoneHeightFromGround + adjustment)
            .coerceIn(MIN_PHONE_HEIGHT, MAX_PHONE_HEIGHT)

        // Save to preferences
        prefs.edit().putFloat(KEY_PHONE_HEIGHT, phoneHeightFromGround).apply()

        Log.d(TAG, "Phone height adjusted: $oldHeight -> $phoneHeightFromGround")
        FileLogger.anchor("Height calibration: ${String.format("%.2f", oldHeight)} → ${String.format("%.2f", phoneHeightFromGround)}m")
    }

    /**
     * Gets the current phone height setting.
     */
    fun getPhoneHeight(): Float = phoneHeightFromGround

    /**
     * Resets phone height to default value.
     */
    fun resetPhoneHeight() {
        phoneHeightFromGround = DEFAULT_PHONE_HEIGHT
        prefs.edit().putFloat(KEY_PHONE_HEIGHT, DEFAULT_PHONE_HEIGHT).apply()
        Log.d(TAG, "Phone height reset to default: $DEFAULT_PHONE_HEIGHT")
    }

    // ========================================================================================
    // DIAGNOSTICS
    // ========================================================================================

    /**
     * Returns diagnostic information for debugging.
     */
    fun getDiagnostics(): String {
        return buildString {
            appendLine("=== OutdoorAnchorManager Diagnostics ===")
            appendLine("Session started: $isSessionStarted")
            appendLine("Depth supported: $isDepthSupported")
            appendLine("Phone height: ${phoneHeightFromGround}m")
            // Phase 3: Ground validation info
            appendLine("Last ground Y: ${lastGroundY}m")
            appendLine("Ground confidence: ${"%.2f".format(lastGroundConfidence)}")
            lastValidationResult?.let {
                if (it.failureReasons.isNotEmpty()) {
                    appendLine("Validation warnings: ${it.failureReasons.joinToString(", ")}")
                }
            }
            appendLine("Stabilization: ${getStabilizationProgress()}%")
            appendLine("Anchors created:")
            appendLine("  - DepthPoint: $depthAnchorCount")
            appendLine("  - Plane: $planeAnchorCount")
            appendLine("  - Camera-relative: $cameraRelativeAnchorCount")
            appendLine("Last used type: $lastUsedAnchorType")
            appendLine("Most used type: ${getMostUsedAnchorType()}")
            appendLine("Last anchor position: ${lastCreatedAnchor?.worldPosition}")
        }
    }

    /**
     * Returns the type of the LAST created anchor (not the most common type).
     * This is the actual strategy used for the current anchor.
     */
    fun getLastAnchorType(): String {
        return lastUsedAnchorType
    }

    /**
     * Returns the MOST COMMONLY used anchor type (for statistics/debugging).
     * Use getLastAnchorType() to get the actual current anchor type.
     */
    fun getMostUsedAnchorType(): String {
        return when {
            depthAnchorCount > planeAnchorCount && depthAnchorCount > cameraRelativeAnchorCount -> "Depth"
            planeAnchorCount > cameraRelativeAnchorCount -> "Plane"
            cameraRelativeAnchorCount > 0 -> "Camera"
            else -> "None"
        }
    }

    // ========================================================================================
    // OWNERSHIP TRANSFER
    // ========================================================================================

    /**
     * Clears the internal anchor reference after ownership has been transferred to ARRenderer.
     *
     * CRITICAL: This MUST be called after arRenderer.setAnchorNode() to prevent double-free.
     * After this call, ARRenderer owns the anchor and is responsible for its cleanup.
     * OutdoorAnchorManager will NOT attempt to destroy the anchor in its destroy() method.
     */
    fun clearAnchorReference() {
        Log.d(TAG, "Anchor reference cleared - ownership transferred to ARRenderer")
        lastCreatedAnchor = null
    }


    // ========================================================================================
    // CLEANUP
    // ========================================================================================

    /**
     * Cleans up resources. Call when AR session ends.
     *
     * NOTE: If the anchor was transferred to ARRenderer via clearAnchorReference(),
     * lastCreatedAnchor will be null and no cleanup is performed here.
     * ARRenderer handles cleanup for transferred anchors.
     */
    fun destroy() {
        // Only destroy if we still own the anchor (wasn't transferred to ARRenderer)
        lastCreatedAnchor?.let { node ->
            Log.d(TAG, "Destroying owned anchor (ownership was NOT transferred)")
            try {
                node.anchor?.detach()
                node.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying anchor: ${e.message}")
            }
        }
        lastCreatedAnchor = null

        // Reset all state for potential reuse
        isSessionStarted = false
        sessionStartTime = 0L
        isDepthSupported = null
        depthAnchorCount = 0
        planeAnchorCount = 0
        cameraRelativeAnchorCount = 0
        lastUsedAnchorType = "None"

        Log.d(TAG, "OutdoorAnchorManager destroyed (state reset)")
        FileLogger.anchor("Manager destroyed")
    }
}