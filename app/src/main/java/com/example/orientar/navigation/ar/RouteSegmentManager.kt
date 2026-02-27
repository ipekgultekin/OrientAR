package com.example.orientar.navigation.ar

import android.location.Location
import android.util.Log
import com.example.orientar.navigation.logic.ArUtils
import com.example.orientar.navigation.logic.Coordinate
import com.example.orientar.navigation.logic.Node
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.Plane
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.SphereNode
import com.example.orientar.navigation.util.FileLogger

/**
 * RouteSegmentManager - Manages multi-anchor route segmentation for AR navigation.
 *
 * THE PROBLEM THIS SOLVES:
 * ========================
 * With a single anchor, spheres far from the anchor (50-100m+) experience significant
 * positional drift due to ARCore tracking errors being amplified by distance:
 *   error ≈ distance × angular_drift
 *
 * Example: 2° drift at 50m = 1.75m visible error (very noticeable)
 *
 * THE SOLUTION:
 * =============
 * Split the route into segments (~10-15m each), each with its own anchor.
 * This keeps the maximum distance from any anchor small, limiting visible drift:
 *   Example: 2° drift at 15m = 0.52m visible error (acceptable)
 *
 * SEGMENT LIFECYCLE:
 * ==================
 * - Maintain 2-3 active segments at a time (previous, current, next)
 * - As user walks forward, add new segment ahead
 * - Remove old segments behind (beyond a threshold)
 * - No "world reset" - smooth incremental updates
 *
 * USAGE:
 * ======
 * 1. Initialize with route data: initialize(routeCoords, routeNodes)
 * 2. Call updateUserPosition(location) on each GPS update
 * 3. Manager automatically creates/destroys segments as needed
 * 4. Call destroy() when navigation ends
 */
class RouteSegmentManager(
    private val arView: ARSceneView,
    private val yawOffsetProvider: () -> Double
) {
    companion object {
        private const val TAG = "SegmentManager"

        // Segment configuration
        private const val SEGMENT_LENGTH_METERS = 12.0f      // Each segment covers ~12m of route was (12.0f)
        private const val SEGMENT_POINT_COUNT = 8            // Alternative: segment by point count
        private const val MAX_ACTIVE_SEGMENTS = 4            // Keep max 4 segments active (was 4 )
        private const val SEGMENT_LOOKAHEAD = 2              // Create 2 segments ahead
        private const val SEGMENT_LOOKBEHIND = 1             // Keep 1 segment behind
        private const val OFF_ROUTE_THRESHOLD = 15.0  // User is off-route if > 15m from nearest route point

        // Sphere rendering settings (matching ARRenderer)
        private const val PATH_SPHERE_RADIUS = 0.20f
        private const val NODE_SPHERE_RADIUS = 0.45f
        private const val ROUTE_Y_OFFSET = 1.2f

        // Anchor creation settings
        private const val ANCHOR_DISTANCE_FROM_CAMERA = 2.0f // Place anchor 2m in front

        // Distance-based segment activation (replaces lookahead)
        private const val SEGMENT_ACTIVATION_DISTANCE = 8.0f  // Create segment when user is within 8m of its center
        private const val SEGMENT_DEACTIVATION_DISTANCE = 25.0f  // Remove segment when user is > 25m from its center
    }

    // ========================================================================================
    // DATA STRUCTURES
    // ========================================================================================

    /**
     * Represents a single route segment with its own anchor and spheres.
     */
    data class RouteSegment(
        val segmentId: Int,
        val anchorNode: AnchorNode,
        val anchorGpsLocation: Location,       // GPS position where anchor was created
        val startRouteIndex: Int,              // First route coordinate index (inclusive)
        val endRouteIndex: Int,                // Last route coordinate index (exclusive)
        val sphereNodes: MutableList<SphereNode> = mutableListOf(),
        var isRendered: Boolean = false,
        val creationTime: Long = System.currentTimeMillis()
    )

    /**
     * Segment boundary - defines which route points belong to which segment.
     */
    data class SegmentBoundary(
        val segmentId: Int,
        val startIndex: Int,
        val endIndex: Int,
        val centerGps: Location                // Center point of this segment (for anchor placement)
    )

    // ========================================================================================
    // STATE
    // ========================================================================================

    // Route data
    private var routeCoords: List<Coordinate> = emptyList()
    private var routeNodePath: List<Node> = emptyList()
    private var startNode: Node? = null
    private var endNode: Node? = null
    private var visitedNodeIds: Set<Int> = emptySet()

    // Segment tracking
    private val activeSegments = mutableMapOf<Int, RouteSegment>()
    private val segmentBoundaries = mutableListOf<SegmentBoundary>()
    private var currentSegmentId: Int = -1
    private var totalSegmentCount: Int = 0

    private var lastUserLocation: Location? = null // Last known user location

    // Materials (shared across all segments)
    private var pathMaterial: com.google.android.filament.MaterialInstance? = null
    private var milestoneMaterial: com.google.android.filament.MaterialInstance? = null
    private var visitedMaterial: com.google.android.filament.MaterialInstance? = null
    private var materialsReady: Boolean = false

    // Initialization state
    private var isInitialized: Boolean = false

    // ========================================================================================
    // INITIALIZATION
    // ========================================================================================

    /**
     * Initialize the segment manager with route data.
     * Call this once when navigation starts.
     */
    fun initialize(
        routeCoords: List<Coordinate>,
        routeNodePath: List<Node>,
        startNode: Node?,
        endNode: Node?,
        visitedNodeIds: Set<Int> = emptySet()
    ) {
        Log.d(TAG, "╔════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ INITIALIZING SEGMENT MANAGER")
        Log.d(TAG, "╠════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ Route points: ${routeCoords.size}")
        Log.d(TAG, "║ Route nodes: ${routeNodePath.size}")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════")

        this.routeCoords = routeCoords
        this.routeNodePath = routeNodePath
        this.startNode = startNode
        this.endNode = endNode
        this.visitedNodeIds = visitedNodeIds

        // Calculate segment boundaries
        calculateSegmentBoundaries()

        // Initialize materials
        initializeMaterials()

        isInitialized = true

        Log.d(TAG, "Initialization complete: ${segmentBoundaries.size} segments planned")
    }

    /**
     * Update visited node IDs (for coloring).
     */
    fun updateVisitedNodes(visitedIds: Set<Int>) {
        this.visitedNodeIds = visitedIds
    }

    // ========================================================================================
    // SEGMENT BOUNDARY CALCULATION
    // ========================================================================================

    /**
     * Divides the route into segments based on distance.
     * Each segment covers approximately SEGMENT_LENGTH_METERS of route distance.
     */
    private fun calculateSegmentBoundaries() {
        segmentBoundaries.clear()

        if (routeCoords.size < 2) {
            Log.w(TAG, "Route too short for segmentation")
            return
        }

        var segmentId = 0
        var segmentStartIndex = 0
        var accumulatedDistance = 0.0

        for (i in 1 until routeCoords.size) {
            val prev = routeCoords[i - 1]
            val curr = routeCoords[i]

            val distance = ArUtils.distanceMeters(prev.lat, prev.lng, curr.lat, curr.lng)
            accumulatedDistance += distance

            // Check if we should end this segment
            val isLastPoint = (i == routeCoords.size - 1)
            val segmentFull = accumulatedDistance >= SEGMENT_LENGTH_METERS

            if (segmentFull || isLastPoint) {
                // Calculate center point of this segment
                val centerIndex = (segmentStartIndex + i) / 2
                val centerCoord = routeCoords[centerIndex]
                val centerLocation = Location("segment_center").apply {
                    latitude = centerCoord.lat
                    longitude = centerCoord.lng
                }

                // OVERLAP FIX: endIndex includes one extra point for continuity
                // This ensures the last point of this segment is also the first of next
                val endIndex = if (isLastPoint) {
                    routeCoords.size  // Include all remaining points
                } else {
                    i + 2  // Include one extra point for overlap (exclusive, so +2)
                }.coerceAtMost(routeCoords.size)

                val boundary = SegmentBoundary(
                    segmentId = segmentId,
                    startIndex = segmentStartIndex,
                    endIndex = endIndex,
                    centerGps = centerLocation
                )

                segmentBoundaries.add(boundary)

                Log.d(TAG, "Segment $segmentId: points $segmentStartIndex-${endIndex - 1}, " +
                        "distance: ${"%.1f".format(accumulatedDistance)}m, overlap: ${if (isLastPoint) "no" else "yes"}")
                FileLogger.segment("Created segment $segmentId: points $segmentStartIndex-${endIndex-1}, ${String.format("%.1f", accumulatedDistance)}m")


                // Start next segment - overlap by starting at current point
                segmentId++
                segmentStartIndex = i  // Next segment starts at current point (shared with previous)

                // ============================================================================
                // BUG-004 FIX: Reset accumulator at segment boundary for precision
                // ============================================================================
                // PROBLEM: Floating-point accumulation over many iterations causes drift.
                //          0.1 + 0.1 + 0.1 + ... (1000 times) ≠ 100.0 exactly
                //
                // SOLUTION: Reset accumulator at each segment boundary.
                //           This is sufficient for routes up to several km (typical campus use).
                // ============================================================================
                accumulatedDistance = 0.0
            }
        }

        totalSegmentCount = segmentBoundaries.size
        Log.d(TAG, "Route divided into $totalSegmentCount segments (with overlap for continuity)")
    }

    // ========================================================================================
    // MATERIAL MANAGEMENT
    // ========================================================================================

    private fun initializeMaterials() {
        try {
            val materialLoader = arView.materialLoader

            // Path spheres - blue
            pathMaterial = materialLoader.createColorInstance(
                color = io.github.sceneview.math.Color(0.0f, 0.5f, 1.0f, 1.0f)
            )

            // Milestone spheres - yellow
            milestoneMaterial = materialLoader.createColorInstance(
                color = io.github.sceneview.math.Color(1.0f, 0.9f, 0.0f, 1.0f)
            )

            // Visited spheres - green
            visitedMaterial = materialLoader.createColorInstance(
                color = io.github.sceneview.math.Color(0.0f, 0.8f, 0.2f, 1.0f)
            )

            materialsReady = true
            Log.d(TAG, "Materials initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize materials: ${e.message}")
            materialsReady = false
        }
    }

    // ========================================================================================
    // USER POSITION TRACKING & SEGMENT LIFECYCLE
    // ========================================================================================

    /**
     * Update the user's current position.
     * This triggers segment creation/destruction as needed.
     *
     * @param userLocation Current GPS location
     * @param session ARCore session for anchor creation
     * @param frame Current AR frame
     * @return true if segments were updated
     */
    fun updateUserPosition(
        userLocation: Location,
        session: Session,
        frame: Frame
    ): Boolean {
        if (!isInitialized || segmentBoundaries.isEmpty()) {
            return false
        }
        lastUserLocation = userLocation

        // Find which segment the user is in
        val newSegmentId = findUserSegment(userLocation)

        if (newSegmentId < 0) {
            Log.w(TAG, "User not on route")
            FileLogger.segment("User off-route: ${findMinDistanceToRoute(userLocation).toInt()}m from route")
            return false
        }

        val segmentChanged = (newSegmentId != currentSegmentId)
        currentSegmentId = newSegmentId

        if (segmentChanged) {
            Log.d(TAG, "User entered segment $currentSegmentId")
        }

        // Ensure required segments are active
        val updated = ensureActiveSegments(session, frame, userLocation)

        return updated || segmentChanged
    }
    /**
     * ========================================================================
     * FIX 1.2: Force create initial segment regardless of user position
     * ========================================================================
     * PROBLEM: updateUserPosition() returns false if user is > 15m from route,
     *          which prevents any segments from being created initially.
     *
     * SOLUTION: This method bypasses the off-route check and always creates
     *           the first segment at the user's current location.
     *
     * WHEN TO USE: Call once when anchor is first created and segmentation
     *              is enabled, to ensure user sees spheres immediately.
     * ========================================================================
     */
    fun forceCreateInitialSegment(
        userLocation: Location,
        session: Session,
        frame: Frame
    ): Boolean {
        if (!isInitialized || segmentBoundaries.isEmpty()) {
            Log.w(TAG, "Cannot force create segment - not initialized")
            FileLogger.e("SEGMENT", "forceCreate failed: not initialized")
            return false
        }

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Log.w(TAG, "Cannot force create segment - camera not tracking")
            FileLogger.w("SEGMENT", "forceCreate delayed: camera not tracking")
            return false
        }

        lastUserLocation = userLocation

        // Find closest segment to user (ignore off-route check)
        var closestSegmentId = 0  // Default to first segment
        var closestDistance = Double.MAX_VALUE

        for (boundary in segmentBoundaries) {
            val distance = ArUtils.distanceMeters(
                userLocation.latitude, userLocation.longitude,
                boundary.centerGps.latitude, boundary.centerGps.longitude
            )

            if (distance < closestDistance) {
                closestDistance = distance
                closestSegmentId = boundary.segmentId
            }
        }

        currentSegmentId = closestSegmentId

        Log.d(TAG, "Force creating initial segment $currentSegmentId (user ${closestDistance.toInt()}m from center)")
        FileLogger.segment("Force creating segment $currentSegmentId at ${closestDistance.toInt()}m distance")

        // Create segments around the user's position
        return ensureActiveSegments(session, frame, userLocation)
    }

    /**
     * Find which segment the user is currently in based on GPS position.
     * Returns -1 if user is off-route (beyond OFF_ROUTE_THRESHOLD from any route point).
     */
    private fun findUserSegment(userLocation: Location): Int {
        // First check if user is on-route at all
        val minDistanceToRoute = findMinDistanceToRoute(userLocation)

        if (minDistanceToRoute > OFF_ROUTE_THRESHOLD) {
            Log.w(TAG, "User is off-route: ${minDistanceToRoute.toInt()}m from nearest route point")
            return -1  // Off-route
        }

        // Find closest segment center
        var closestSegmentId = -1
        var closestDistance = Double.MAX_VALUE

        for (boundary in segmentBoundaries) {
            val distance = ArUtils.distanceMeters(
                userLocation.latitude, userLocation.longitude,
                boundary.centerGps.latitude, boundary.centerGps.longitude
            )

            if (distance < closestDistance) {
                closestDistance = distance
                closestSegmentId = boundary.segmentId
            }
        }

        return closestSegmentId
    }

    /**
     * Find the minimum distance from user to any point on the route.
     */
    private fun findMinDistanceToRoute(userLocation: Location): Double {
        if (routeCoords.isEmpty()) return Double.MAX_VALUE

        return routeCoords.minOf { coord ->
            ArUtils.distanceMeters(
                userLocation.latitude, userLocation.longitude,
                coord.lat, coord.lng
            )
        }
    }

    /**
     * Ensure the correct segments are active (created and rendered).
     * Creates segments ahead of user, removes segments far behind.
     */
    private fun ensureActiveSegments(
        session: Session,
        frame: Frame,
        userLocation: Location
    ): Boolean {
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Log.d(TAG, "Skipping segment update - camera not tracking")
            FileLogger.segment("Update skipped: camera not tracking")
            return false
        }

        var updated = false

        // Calculate which segments should be active
        val targetSegments = calculateTargetSegments()

        // Remove segments that should no longer be active
        val segmentsToRemove = activeSegments.keys.filter { it !in targetSegments }
        for (segmentId in segmentsToRemove) {
            removeSegment(segmentId)
            updated = true
        }

        // Create segments that should be active but aren't
        for (segmentId in targetSegments) {
            if (segmentId !in activeSegments) {
                val created = createSegment(segmentId, session, frame, userLocation)
                if (created) {
                    updated = true
                }
            }
        }
        if (updated) {
            FileLogger.segment("Segments updated: ${activeSegments.size} active")
        }
        return updated
    }

    /**
     * Calculate which segments should be active based on DISTANCE to user.
     * This replaces the old lookahead-based approach which caused anchor/GPS mismatch.
     */
    private fun calculateTargetSegments(): Set<Int> {
        val targets = mutableSetOf<Int>()

        // Get current user location from the most recent update
        val userLat = lastUserLocation?.latitude ?: return targets
        val userLng = lastUserLocation?.longitude ?: return targets

        // Check each segment's distance to user
        for (boundary in segmentBoundaries) {
            val distanceToSegment = ArUtils.distanceMeters(
                userLat, userLng,
                boundary.centerGps.latitude, boundary.centerGps.longitude
            )

            // Activate segments within activation distance
            if (distanceToSegment <= SEGMENT_ACTIVATION_DISTANCE) {
                targets.add(boundary.segmentId)
            }
            // Keep already-active segments until they're beyond deactivation distance
            else if (boundary.segmentId in activeSegments &&
                distanceToSegment <= SEGMENT_DEACTIVATION_DISTANCE) {
                targets.add(boundary.segmentId)
            }
        }

        // Always include current segment
        if (currentSegmentId >= 0) {
            targets.add(currentSegmentId)
        }

        // Limit to max active segments (prioritize closest)
        return if (targets.size > MAX_ACTIVE_SEGMENTS) {
            targets.sortedBy { segmentId ->
                val boundary = segmentBoundaries.getOrNull(segmentId)
                boundary?.let {
                    ArUtils.distanceMeters(userLat, userLng, it.centerGps.latitude, it.centerGps.longitude)
                } ?: Double.MAX_VALUE
            }.take(MAX_ACTIVE_SEGMENTS).toSet()
        } else {
            targets
        }
    }

    // ========================================================================================
    // SEGMENT CREATION
    // ========================================================================================

    /**
     * Create a new segment with its own anchor and spheres.
     */
    private fun createSegment(
        segmentId: Int,
        session: Session,
        frame: Frame,
        userLocation: Location
    ): Boolean {
        val boundary = segmentBoundaries.getOrNull(segmentId) ?: run {
            Log.e(TAG, "Invalid segment ID: $segmentId")
            return false
        }

        Log.d(TAG, "Creating segment $segmentId (points ${boundary.startIndex}-${boundary.endIndex})")

        // Create anchor for this segment
        val anchor = createAnchorForSegment(session, frame, boundary) ?: run {
            Log.w(TAG, "Failed to create anchor for segment $segmentId")
            return false
        }

        // Create anchor node
        val anchorNode = AnchorNode(arView.engine, anchor).also { node ->
            node.isEditable = false
            arView.addChildNode(node)
        }

        // Create segment object
        // CRITICAL: Use userLocation (GPS at anchor creation time), NOT boundary.centerGps
        // This ensures AR anchor position corresponds to GPS origin for correct mapping
        val segment = RouteSegment(
            segmentId = segmentId,
            anchorNode = anchorNode,
            anchorGpsLocation = userLocation,
            startRouteIndex = boundary.startIndex,
            endRouteIndex = boundary.endIndex
        )

        // Render spheres for this segment
        renderSegmentSpheres(segment)

        // Store segment
        activeSegments[segmentId] = segment

        Log.d(TAG, "✅ Segment $segmentId created with ${segment.sphereNodes.size} spheres")
        FileLogger.segment("Activated segment $segmentId: ${segment.sphereNodes.size} spheres")

        return true
    }

    /**
     * Create an anchor at the segment's center position.
     */
    private fun createAnchorForSegment(
        session: Session,
        frame: Frame,
        boundary: SegmentBoundary
    ): Anchor? {
        return try {
            // Try hit-test first for better ground placement
            val anchor = tryHitTestAnchor(frame)

            if (anchor != null) {
                Log.d(TAG, "Segment anchor created via hit-test")
                return anchor
            }

            // Fallback: Create anchor using camera pose with height estimation
            val cameraPose = frame.camera.pose

            // Use instant placement as fallback (more reliable than fixed offset)
            val anchorPose = com.google.ar.core.Pose(
                floatArrayOf(
                    cameraPose.tx(),
                    cameraPose.ty() - 1.3f,  // Estimated ground level
                    cameraPose.tz() - ANCHOR_DISTANCE_FROM_CAMERA
                ),
                cameraPose.rotationQuaternion
            )

            Log.d(TAG, "Segment anchor created via camera fallback")
            session.createAnchor(anchorPose)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating anchor: ${e.message}")
            null
        }
    }

    /**
     * Try to create an anchor using hit-test for accurate ground placement.
     */
    private fun tryHitTestAnchor(frame: Frame): Anchor? {
        return try {
            // Get screen center for hit-test using actual view dimensions
            val centerX = arView.width / 2f
            val centerY = arView.height * 0.6f  // Lower part of screen (more likely to hit ground)

            // Perform hit-test
            val hitResults = frame.hitTest(centerX, centerY)

            for (hit in hitResults) {
                val trackable = hit.trackable

                // Check for plane hit (best for ground)
                if (trackable is com.google.ar.core.Plane &&
                    trackable.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    trackable.trackingState == TrackingState.TRACKING) {

                    return hit.createAnchor()
                }

                // Check for depth point hit (good for outdoor)
                if (trackable is com.google.ar.core.DepthPoint &&
                    trackable.trackingState == TrackingState.TRACKING) {

                    return hit.createAnchor()
                }
            }

            null  // No suitable hit found

        } catch (e: Exception) {
            Log.w(TAG, "Hit-test failed: ${e.message}")
            null
        }
    }

    /**
     * Render all spheres for a segment.
     */
    private fun renderSegmentSpheres(segment: RouteSegment) {
        if (!materialsReady) {
            Log.e(TAG, "Materials not ready - cannot render spheres")
            return
        }

        val yawOffset = yawOffsetProvider()

        // Get route points for this segment
        val segmentCoords = routeCoords.subList(
            segment.startRouteIndex,
            minOf(segment.endRouteIndex, routeCoords.size)
        )

        if (segmentCoords.isEmpty()) {
            Log.w(TAG, "No coordinates for segment ${segment.segmentId}")
            return
        }

        // Render interpolated path spheres between consecutive coordinates
        for (i in 0 until segmentCoords.size - 1) {
            val p1 = segmentCoords[i]
            val p2 = segmentCoords[i + 1]

            // Interpolate points every 2.5 meters (matching ARRenderer)
            val interpolatedPoints = ArUtils.interpolate(p1.lat, p1.lng, p2.lat, p2.lng, 2.5)

            for (point in interpolatedPoints) {
                // Convert GPS to AR position relative to segment's anchor GPS
                val (x, z) = ArUtils.convertGpsToArPosition(
                    userLoc = segment.anchorGpsLocation,
                    targetLat = point.first,
                    targetLng = point.second,
                    yawOffsetDeg = yawOffset
                )

                // Check if this point is a milestone (near a node)
                val isMilestone = routeNodePath.any { node ->
                    ArUtils.distanceMeters(point.first, point.second, node.lat, node.lng) < 3.0
                }

                // Check if visited
                val isVisited = routeNodePath.any { node ->
                    val nearPoint = ArUtils.distanceMeters(point.first, point.second, node.lat, node.lng) < 3.0
                    nearPoint && node.id in visitedNodeIds
                }

                // Determine material and size
                val material = when {
                    isVisited -> visitedMaterial
                    isMilestone -> milestoneMaterial
                    else -> pathMaterial
                } ?: continue

                val radius = if (isMilestone) NODE_SPHERE_RADIUS else PATH_SPHERE_RADIUS

                // Place sphere
                placeSphereInSegment(segment, x, z, material, radius, segment.startRouteIndex + i)
            }
        }

        // Also render the last point
        val lastCoord = segmentCoords.last()
        val (lastX, lastZ) = ArUtils.convertGpsToArPosition(
            userLoc = segment.anchorGpsLocation,
            targetLat = lastCoord.lat,
            targetLng = lastCoord.lng,
            yawOffsetDeg = yawOffset
        )

        val isLastMilestone = routeNodePath.any { node ->
            ArUtils.distanceMeters(lastCoord.lat, lastCoord.lng, node.lat, node.lng) < 3.0
        }

        val isLastVisited = routeNodePath.any { node ->
            val nearPoint = ArUtils.distanceMeters(lastCoord.lat, lastCoord.lng, node.lat, node.lng) < 3.0
            nearPoint && node.id in visitedNodeIds
        }

        val lastMaterial = when {
            isLastVisited -> visitedMaterial
            isLastMilestone -> milestoneMaterial
            else -> pathMaterial
        }

        if (lastMaterial != null) {
            val lastRadius = if (isLastMilestone) NODE_SPHERE_RADIUS else PATH_SPHERE_RADIUS
            placeSphereInSegment(segment, lastX, lastZ, lastMaterial, lastRadius, segment.endRouteIndex - 1)
        }

        segment.isRendered = true
        Log.d(TAG, "Segment ${segment.segmentId} rendered with ${segment.sphereNodes.size} spheres (interpolated)")
    }

    /**
     * Place a single sphere in a segment.
     */
    private fun placeSphereInSegment(
        segment: RouteSegment,
        x: Float,
        z: Float,
        material: com.google.android.filament.MaterialInstance,
        radius: Float,
        globalIndex: Int
    ) {
        try {
            // Calculate Y with distance-based boost
            val distanceFromAnchor = kotlin.math.sqrt(x * x + z * z)
            val distanceBoost = (distanceFromAnchor / 20.0f).coerceIn(0f, 0.5f)
            val finalY = ROUTE_Y_OFFSET + distanceBoost

            val sphereNode = SphereNode(
                engine = arView.engine,
                radius = radius,
                materialInstance = material
            ).apply {
                position = io.github.sceneview.math.Position(x, finalY, z)
            }

            segment.anchorNode.addChildNode(sphereNode)
            segment.sphereNodes.add(sphereNode)

        } catch (e: Exception) {
            Log.e(TAG, "Error placing sphere: ${e.message}")
        }
    }

    // ========================================================================================
    // SEGMENT REMOVAL
    // ========================================================================================

    /**
     * Remove a segment and destroy its resources.
     */
    private fun removeSegment(segmentId: Int) {
        val segment = activeSegments.remove(segmentId) ?: return

        Log.d(TAG, "Removing segment $segmentId")

        try {
            // Destroy all sphere nodes
            for (sphereNode in segment.sphereNodes) {
                try {
                    sphereNode.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying sphere: ${e.message}")
                }
            }
            segment.sphereNodes.clear()

            // Detach and destroy anchor
            try {
                segment.anchorNode.anchor?.detach()
                segment.anchorNode.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying anchor: ${e.message}")
            }

            Log.d(TAG, "✅ Segment $segmentId removed")
            FileLogger.segment("Removed segment $segmentId")

        } catch (e: Exception) {
            Log.e(TAG, "Error removing segment $segmentId: ${e.message}")
            FileLogger.segment("Error removing segment $segmentId: ${e.message}")
        }
    }

    // ========================================================================================
    // PUBLIC QUERIES
    // ========================================================================================

    /**
     * Get total number of active segments.
     */
    fun getActiveSegmentCount(): Int = activeSegments.size

    /**
     * Get total number of rendered spheres across all segments.
     */
    fun getTotalSphereCount(): Int = activeSegments.values.sumOf { it.sphereNodes.size }

    /**
     * Get current segment ID.
     */
    fun getCurrentSegmentId(): Int = currentSegmentId

    /**
     * Get total planned segments.
     */
    fun getTotalSegmentCount(): Int = totalSegmentCount

    /**
     * Check if a specific segment is active.
     */
    fun isSegmentActive(segmentId: Int): Boolean = segmentId in activeSegments

    /**
     * Get diagnostic information.
     */
    fun getDiagnostics(): String {
        return buildString {
            appendLine("=== Segment Manager ===")
            appendLine("Initialized: $isInitialized")
            appendLine("Total segments: $totalSegmentCount")
            appendLine("Active segments: ${activeSegments.size}")
            appendLine("Current segment: $currentSegmentId")
            appendLine("Total spheres: ${getTotalSphereCount()}")
            appendLine()
            appendLine("Active segment IDs: ${activeSegments.keys.sorted()}")
            for ((id, segment) in activeSegments.toSortedMap()) {
                appendLine("  Segment $id: ${segment.sphereNodes.size} spheres, " +
                        "points ${segment.startRouteIndex}-${segment.endRouteIndex}")
            }
        }
    }

    // ========================================================================================
    // CLEANUP
    // ========================================================================================

    /**
     * Clear all segments without destroying the manager.
     */
    fun clearAllSegments() {
        Log.d(TAG, "Clearing all segments")

        for (segmentId in activeSegments.keys.toList()) {
            removeSegment(segmentId)
        }

        currentSegmentId = -1
    }

    /**
     * Full cleanup - call when navigation ends.
     */
    fun destroy() {
        Log.d(TAG, "╔════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ DESTROYING SEGMENT MANAGER")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════")

        // Clear all segments
        clearAllSegments()

            try {
            pathMaterial?.let { arView.engine.destroyMaterialInstance(it) }
            milestoneMaterial?.let { arView.engine.destroyMaterialInstance(it) }
            visitedMaterial?.let { arView.engine.destroyMaterialInstance(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying materials: ${e.message}")
        }
        // Destroy materials
        try {
            pathMaterial = null
            milestoneMaterial = null
            visitedMaterial = null
            materialsReady = false
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying materials: ${e.message}")
        }

        // Clear route data
        routeCoords = emptyList()
        routeNodePath = emptyList()
        segmentBoundaries.clear()

        isInitialized = false

        Log.d(TAG, "Segment manager destroyed")
    }
}