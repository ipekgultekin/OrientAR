package com.example.orientar.navigation.rendering

import android.location.Location
import android.util.Log
import com.example.orientar.navigation.logic.ArUtils
import com.example.orientar.navigation.logic.Coordinate
import com.example.orientar.navigation.logic.Node
import com.google.android.filament.MaterialInstance
import com.google.ar.core.Anchor
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Color
import io.github.sceneview.math.Position
import io.github.sceneview.node.SphereNode
import kotlinx.coroutines.delay
// Phase 3: Performance optimized rendering
import com.example.orientar.navigation.ar.ARPerformanceConfig
import com.example.orientar.navigation.ar.SpherePositionCalculator
import com.example.orientar.navigation.ar.TerrainProfiler
import com.example.orientar.navigation.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ARRenderer - Manages AR scene rendering and route visualization.
 *
 * ================================================================================================
 * OVERVIEW
 * ================================================================================================
 * This class is responsible for:
 * 1. Managing the AR anchor (the origin point for all route spheres)
 * 2. Creating and caching materials for different sphere types
 * 3. Rendering the route as a series of colored spheres
 * 4. Cleaning up resources when done
 *
 * ================================================================================================
 * Y-AXIS POSITIONING STRATEGY (OUTDOOR OPTIMIZED)
 * ================================================================================================
 *
 * THE CHALLENGE:
 * In outdoor AR, plane detection is unreliable. The detected "ground" plane might be:
 * - A patch of concrete at the user's feet
 * - A distant sidewalk
 * - A table or bench
 * - Nothing at all (forcing instant placement)
 *
 * THE SOLUTION:
 * With OutdoorAnchorManager, anchors are placed at more reliable ground level.
 * We use dynamic height offset that:
 * - Base height: 1.2m above anchor (chest level)
 * - Distance boost: +0.5m max for distant spheres (visible over terrain)
 * - Milestone boost: +0.3m for milestone nodes (stand out from path)
 *
 * ================================================================================================
 */
class ARRenderer(
    private val arView: ARSceneView,
    private val yawOffsetProvider: () -> Double
) {
    companion object {
        private const val TAG = "ARRenderer"

        // ========================================================================================
        // PHASE 3: Configuration now comes from ARPerformanceConfig
        // These are kept as fallbacks only
        // ========================================================================================

        private val ROUTE_Y_OFFSET: Float
            get() = ARPerformanceConfig.BASE_SPHERE_HEIGHT

        private val PATH_SPHERE_RADIUS: Float
            get() = ARPerformanceConfig.PATH_SPHERE_RADIUS

        private val NODE_SPHERE_RADIUS: Float
            get() = ARPerformanceConfig.MILESTONE_SPHERE_RADIUS

        private const val MILESTONE_Y_BOOST = 0.3f
        private const val RENDER_CHUNK_SIZE = 50
        private const val FRAME_DELAY_MS = 16L
    }
    /**
     * Enum representing the strategy used to create the current anchor.
     * Different strategies have different Y-axis characteristics.
     */
    enum class AnchorType {
        PLANE,           // Y = actual detected ground surface (most reliable)
        DEPTH_POINT,     // Y = depth-detected surface (reliable)
        CAMERA_RELATIVE, // Y = estimated ground based on phone height (less reliable)
        INSTANT,         // Y = approximate depth, may refine (least reliable)
        UNKNOWN          // Default/fallback
    }

    // Current anchor type - affects Y positioning strategy
    private var currentAnchorType: AnchorType = AnchorType.UNKNOWN

    // ========================================================================================
    // ANCHOR-CAMERA OFFSET COMPENSATION
    // ========================================================================================
    // When anchor is created via hit-test, it's often 1-3m in front of the camera.
    // We store this offset and compensate when placing spheres so that the route
    // aligns with the user's actual GPS position, not the anchor's position.
    // ========================================================================================
    private var anchorCameraOffsetX: Float = 0f  // Offset in AR X direction (right/left)
    private var anchorCameraOffsetZ: Float = 0f  // Offset in AR Z direction (forward/back)
    private var hasAnchorOffset: Boolean = false

    // ========================================================================================
    // PHASE 3: Background Position Calculator & Terrain Profiler
    // ========================================================================================
    private var terrainProfiler: TerrainProfiler? = null
    private var spherePositionCalculator: SpherePositionCalculator? = null
    private var usePhase3Rendering: Boolean = true  // Toggle for testing

    // Last render timestamp for throttling
    private var lastRenderTime: Long = 0
    private val minRenderIntervalMs: Long = (1000L / ARPerformanceConfig.MAX_RENDER_UPDATES_PER_SECOND)


    fun getPositionCalculator(): SpherePositionCalculator? = spherePositionCalculator
    /**
     * Initialize Phase 3 components.
     * Call this after ARRenderer is created.
     */
    fun initializePhase3Components() {
        terrainProfiler = TerrainProfiler()
        terrainProfiler?.let { profiler ->
            spherePositionCalculator = SpherePositionCalculator(profiler)
            FileLogger.ar("Phase 3 components initialized")
        } ?: run {
            FileLogger.e("AR", "Failed to create TerrainProfiler!")
        }

        // Set up callback for when position calculation completes
        spherePositionCalculator?.setOnCalculationComplete { result ->
            // This is called on background thread - need to post to main thread for rendering
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                applyCalculatedPositions(result)
            }
        }

        Log.d(TAG, "Phase 3 components initialized")
    }


    /**
     * Get the terrain profiler for external use.
     */
    fun getTerrainProfiler(): TerrainProfiler? = terrainProfiler

    /**
     * Check if Phase 3 rendering is enabled.
     */
    fun isPhase3Enabled(): Boolean = usePhase3Rendering && spherePositionCalculator != null
    /**
     * Apply pre-calculated sphere positions from background thread.
     * This runs on main thread and only creates/updates spheres.
     */
    private fun applyCalculatedPositions(result: SpherePositionCalculator.CalculationResult) {
        // FIX 2.2: Add logging for Phase 3 rendering
        FileLogger.ar("Phase3: Applying ${result.positions.size} positions (calc took ${result.calculationTimeMs}ms)")
        val parent = anchorNode ?: run {
            FileLogger.e("AR", "Phase3 FAILED: anchorNode is null")
            return
        }

        if (!hasAnchor()) {
            Log.w(TAG, "Cannot apply positions: No anchor")
            FileLogger.w("AR", "Phase3 FAILED: No anchor")
            return
        }

        Log.d(TAG, "Applying ${result.positions.size} calculated positions (took ${result.calculationTimeMs}ms)")

        // Clear existing spheres
        clearRoute()

        // Ensure materials are ready
        ensureMaterialsReady()

        var sphereCount = 0
        val startTime = System.currentTimeMillis()

        for (pos in result.positions) {
            // Check if we're spending too much time on main thread
            if (System.currentTimeMillis() - startTime > ARPerformanceConfig.MAX_MAIN_THREAD_TIME_MS * 2) {
                Log.w(TAG, "Main thread time exceeded, stopping at $sphereCount spheres")
                break
            }

            // Determine material based on index
            val material = if (pos.isMilestone) {
                matRed ?: matGrey  // Milestones in red (or gray fallback)
            } else {
                matGrey ?: matRed  // Path in gray (or red fallback)
            }

            material?.let { mat ->
                try {
                    val sphereNode = SphereNode(
                        engine = arView.engine,
                        radius = pos.radius,
                        materialInstance = mat
                    ).apply {
                        position = Position(pos.x, pos.y, pos.z)
                    }

                    parent.addChildNode(sphereNode)
                    sphereCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create sphere at index ${pos.index}: ${e.message}")
                }
            }
        }

        Log.d(TAG, "Applied $sphereCount spheres in ${System.currentTimeMillis() - startTime}ms")
        FileLogger.ar("Phase3 COMPLETE: $sphereCount spheres")
        FileLogger.perf("Phase3 apply", System.currentTimeMillis() - startTime, 100L)

    }

    /**
     * Request position calculation on background thread.
     * Results will be applied via callback.
     */
    fun requestPositionCalculation(
        anchorLocation: android.location.Location,
        routeCoords: List<Coordinate>,
        milestoneIndices: Set<Int>,
        anchorType: String
    ) {
        if (!isPhase3Enabled()) {
            Log.d(TAG, "Phase 3 not enabled, skipping background calculation")
            return
        }

        // Throttle render requests
        val now = System.currentTimeMillis()
        if (now - lastRenderTime < minRenderIntervalMs) {
            Log.d(TAG, "Throttling render request (${now - lastRenderTime}ms since last)")
            return
        }
        lastRenderTime = now

        spherePositionCalculator?.calculatePositionsAsync(
            anchorLocation = anchorLocation,
            routeCoords = routeCoords,
            milestoneIndices = milestoneIndices,
            yawOffset = yawOffsetProvider(),
            anchorType = anchorType
        )
    }

    /**
     * Request incremental position update for yaw changes.
     * Much faster than full recalculation.
     */
    fun requestIncrementalUpdate(newYawOffset: Double) {
        if (!isPhase3Enabled()) return

        spherePositionCalculator?.updatePositionsForYawChange(newYawOffset) { updatedPositions ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // Update existing sphere positions instead of recreating
                anchorNode?.let { parent ->
                    val sphereNodes = parent.childNodes.filterIsInstance<SphereNode>()

                    for ((index, sphereNode) in sphereNodes.withIndex()) {
                        if (index < updatedPositions.size) {
                            val newPos = updatedPositions[index]
                            sphereNode.position = Position(newPos.x, newPos.y, newPos.z)
                        }
                    }

                    Log.d(TAG, "Incremental update applied to ${sphereNodes.size} spheres")
                }
            }
        }
    }

    // ========================================================================================
    // ANCHOR MANAGEMENT
    // ========================================================================================
    private var anchorNode: AnchorNode? = null
    private var anchor: Anchor? = null

    fun setAnchor(newAnchor: Anchor) {
        Log.d(TAG, "Setting new anchor")
        FileLogger.anchor("Setting new anchor")

        clearAnchor()

        anchor = newAnchor
        anchorNode = AnchorNode(arView.engine, newAnchor).also { node ->
            arView.addChildNode(node)
            Log.d(TAG, "✅ Anchor node added to scene")
            Log.d(TAG, "   Anchor world position: ${node.worldPosition}")
            FileLogger.anchor("Anchor added: pos=(${String.format("%.2f", node.worldPosition.x)}, ${String.format("%.2f", node.worldPosition.y)}, ${String.format("%.2f", node.worldPosition.z)})")
        }
    }

    fun hasAnchor(): Boolean = anchorNode != null && anchor != null

    fun isAnchorTracking(): Boolean {
        return anchorNode?.anchor?.trackingState == TrackingState.TRACKING
    }
    // ========================================================================================
    // SEGMENTATION MODE
    // ========================================================================================
    // When segmentation is enabled, RouteSegmentManager handles route rendering.
    // ARRenderer's renderRoute() is disabled in this mode.
    // ========================================================================================
    private var segmentationEnabled: Boolean = false

    /**
     * Enable segmentation mode.
     * When enabled, renderRoute() will be a no-op (RouteSegmentManager handles rendering).
     */
    fun enableSegmentation() {
        segmentationEnabled = true
        Log.d(TAG, "Segmentation mode ENABLED - ARRenderer route rendering disabled")
    }

    /**
     * Disable segmentation mode.
     * When disabled, ARRenderer handles all route rendering (single-anchor mode).
     */
    fun disableSegmentation() {
        segmentationEnabled = false
        Log.d(TAG, "Segmentation mode DISABLED - ARRenderer route rendering enabled")
    }

    /**
     * Check if segmentation mode is enabled.
     */
    fun isSegmentationEnabled(): Boolean = segmentationEnabled

    /**
     * Sets an anchor node that was created externally (e.g., by OutdoorAnchorManager).
     *
     * IMPORTANT: This method takes OWNERSHIP of the anchor node. The external manager
     * should call clearAnchorReference() after this to prevent double-free on cleanup.
     *
     * ARRenderer will be responsible for:
     * - Managing the anchor node lifecycle
     * - Adding child nodes (spheres) for route rendering
     * - Destroying the anchor node in destroy()
     *
     * @param externalAnchorNode An AnchorNode created by an external manager
     */
    fun setAnchorNode(externalAnchorNode: AnchorNode) {
        Log.d(TAG, "Setting external anchor node (taking ownership)")

        // Clear any existing anchor first
        clearAnchor()

        // Take ownership of the external node
        anchorNode = externalAnchorNode
        anchor = externalAnchorNode.anchor

        // Note: We don't add the node to the scene here because the external manager
        // should have already done that. We just keep references for route rendering.

        // Verify the node is properly set up
        val nodeInScene = arView.childNodes.contains(externalAnchorNode)
        val anchorTracking = anchor?.trackingState?.name ?: "NULL"
        val worldPos = externalAnchorNode.worldPosition

        Log.d(TAG, "✅ External anchor node set (ownership transferred to ARRenderer)")
        Log.d(TAG, "   Node already in scene: $nodeInScene")
        Log.d(TAG, "   Anchor tracking state: $anchorTracking")
        Log.d(TAG, "   World position: (${String.format("%.2f", worldPos.x)}, ${String.format("%.2f", worldPos.y)}, ${String.format("%.2f", worldPos.z)})")

        if (!nodeInScene) {
            Log.w(TAG, "⚠️ WARNING: AnchorNode is not in scene! Spheres may not render correctly.")
        }

        if (anchorTracking != "TRACKING") {
            Log.w(TAG, "⚠️ WARNING: Anchor is not tracking! State: $anchorTracking")
        }
    }

    /**
     * Converts a strategy string to AnchorType enum.
     * Used when receiving anchor type from OutdoorAnchorManager.
     */
    fun setAnchorTypeFromString(strategy: String) {
        currentAnchorType = when (strategy.lowercase()) {
            "depth" -> AnchorType.DEPTH_POINT
            "plane" -> AnchorType.PLANE
            "camera" -> AnchorType.CAMERA_RELATIVE
            "instant" -> AnchorType.INSTANT
            else -> AnchorType.UNKNOWN
        }
        Log.d(TAG, "Anchor type set from string '$strategy' to: $currentAnchorType")
    }

    /**
     * Sets the offset between the camera position and anchor position at creation time.
     *
     * This compensates for the fact that hit-test anchors are placed 1-3m in front
     * of the camera, but GPS position is at the camera location.
     *
     * @param offsetX Offset in AR X direction (positive = anchor is to the right of camera)
     * @param offsetZ Offset in AR Z direction (positive = anchor is in front of camera)
     */
    fun setAnchorCameraOffset(offsetX: Float, offsetZ: Float) {
        anchorCameraOffsetX = offsetX
        anchorCameraOffsetZ = offsetZ
        hasAnchorOffset = true

        val distance = kotlin.math.sqrt(offsetX * offsetX + offsetZ * offsetZ)
        Log.d(TAG, "Anchor-camera offset set:")
        Log.d(TAG, "   X offset: ${"%.2f".format(offsetX)}m (${if (offsetX >= 0) "right" else "left"})")
        Log.d(TAG, "   Z offset: ${"%.2f".format(offsetZ)}m (${if (offsetZ >= 0) "forward" else "back"})")
        Log.d(TAG, "   Total distance: ${"%.2f".format(distance)}m")
    }

    /**
     * Clears the anchor-camera offset.
     * Call this when anchor is cleared or when using camera-relative anchor (no offset).
     */
    fun clearAnchorCameraOffset() {
        anchorCameraOffsetX = 0f
        anchorCameraOffsetZ = 0f
        hasAnchorOffset = false
        Log.d(TAG, "Anchor-camera offset cleared")
    }

    // ========================================================================================
    // MATERIAL MANAGEMENT
    // ========================================================================================
    private var matRed: MaterialInstance? = null
    private var matBlue: MaterialInstance? = null
    private var matGreen: MaterialInstance? = null
    private var matGrey: MaterialInstance? = null
    private var matGone: MaterialInstance? = null

    private fun ensureMaterialsReady() {
        if (materialsReady()) return

        try {
            destroyMaterials()

            matRed = arView.materialLoader.createColorInstance(Color(1f, 0f, 0f, 0.9f))
            matBlue = arView.materialLoader.createColorInstance(Color(0f, 0f, 1f, 1f))
            matGreen = arView.materialLoader.createColorInstance(Color(0f, 1f, 0f, 1f))
            matGrey = arView.materialLoader.createColorInstance(Color(1f, 1f, 1f, 0.6f))
            matGone = arView.materialLoader.createColorInstance(Color(0.5f, 0.5f, 0.5f, 0.3f))

            Log.d(TAG, "Materials initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create materials", e)
        }
    }

    private fun materialsReady(): Boolean =
        matRed != null && matBlue != null && matGreen != null && matGrey != null && matGone != null

    private fun destroyMaterials() {
        try {
            // Properly destroy Filament MaterialInstance objects
            matRed?.let {
                try {
                    arView.engine.destroyMaterialInstance(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying matRed: ${e.message}")
                }
            }
            matBlue?.let {
                try {
                    arView.engine.destroyMaterialInstance(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying matBlue: ${e.message}")
                }
            }
            matGreen?.let {
                try {
                    arView.engine.destroyMaterialInstance(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying matGreen: ${e.message}")
                }
            }
            matGrey?.let {
                try {
                    arView.engine.destroyMaterialInstance(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying matGrey: ${e.message}")
                }
            }
            matGone?.let {
                try {
                    arView.engine.destroyMaterialInstance(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying matGone: ${e.message}")
                }
            }
            Log.d(TAG, "Materials destroyed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in destroyMaterials: ${e.message}")
        } finally {
            matRed = null
            matBlue = null
            matGreen = null
            matGrey = null
            matGone = null
        }
    }

    // ========================================================================================
    // ROUTE RENDERING
    // ========================================================================================

    /**
     * Renders the navigation route as a series of AR spheres.
     *
     * RENDERING STRATEGY:
     * 1. PATH LINE: Small spheres (PATH_SPHERE_RADIUS) placed at regular intervals
     *    along the route. These form a "breadcrumb trail" for the user to follow.
     *
     * 2. MILESTONE NODES: Larger spheres (NODE_SPHERE_RADIUS) at key locations:
     *    - BLUE: Start node (where navigation begins)
     *    - GREEN: End node (destination)
     *    - RED: Unvisited waypoints (intersections along the route)
     *    - GREY (transparent): Visited waypoints (already passed)
     *
     * PERFORMANCE:
     * Rendering is done in chunks to prevent UI freezing. After every RENDER_CHUNK_SIZE
     * spheres, we yield for one frame (FRAME_DELAY_MS).
     *
     * @param anchorLocation GPS position where the anchor was placed
     * @param routeCoords List of GPS coordinates defining the route path
     * @param routeNodePath List of navigation nodes (waypoints) along the route
     * @param startNode The starting node
     * @param endNode The destination node
     * @param visitedNodeIds Set of node IDs the user has already visited
     */
    suspend fun renderRoute(
        anchorLocation: Location,
        routeCoords: List<Coordinate>,
        routeNodePath: List<Node>,
        startNode: Node,
        endNode: Node,
        visitedNodeIds: Set<Int>
    ) {
        val renderStartTime = System.currentTimeMillis()  // ADD THIS
        // Skip if segmentation is handling route rendering
        if (segmentationEnabled) {
            Log.d(TAG, "renderRoute() skipped - segmentation mode active")
            return
        }
        // Validate prerequisites
        if (!hasAnchor()) {
            Log.w(TAG, "❌ Cannot render: No anchor available")
            return
        }

        if (routeCoords.isEmpty()) {
            Log.w(TAG, "❌ Cannot render: No route coordinates")
            return
        }

        Log.d(TAG, "╔════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ STARTING ROUTE RENDERING")
        Log.d(TAG, "╠════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ Anchor: (${anchorLocation.latitude}, ${anchorLocation.longitude})")
        Log.d(TAG, "║ Route: ${routeCoords.size} coordinates, ${routeNodePath.size} nodes")
        Log.d(TAG, "║ Yaw offset: ${"%.1f".format(yawOffsetProvider())}°")
        Log.d(TAG, "║ Visited nodes: ${visitedNodeIds.size}")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════")
        FileLogger.ar("Render START: ${routeCoords.size} coords, ${routeNodePath.size} nodes, yaw=${String.format("%.1f", yawOffsetProvider())}°")

        // Ensure materials are ready
        ensureMaterialsReady()

        // Clear any existing route spheres
        clearRoute()

        var sphereCount = 0

        // ====================================================================
        // PHASE 1: Render path line (small breadcrumb spheres)
        // ====================================================================
        Log.d(TAG, "Phase 1: Rendering path line...")

        for (i in 0 until routeCoords.size - 1) {
            val p1 = routeCoords[i]
            val p2 = routeCoords[i + 1]

            val points = ArUtils.interpolate(p1.lat, p1.lng, p2.lat, p2.lng, 2.5)

            for (point in points) {
                // Skip if no materials available
                val safeMat = matGrey ?: matRed
                if (safeMat == null) {
                    Log.e(TAG, "No materials available - skipping path sphere")
                    continue
                }

                val (x, z) = ArUtils.convertGpsToArPosition(
                    userLoc = anchorLocation,
                    targetLat = point.first,
                    targetLng = point.second,
                    yawOffsetDeg = yawOffsetProvider()
                )

                placeSphere(x, z, safeMat, PATH_SPHERE_RADIUS, isMilestone = false)
                sphereCount++

                if (sphereCount % RENDER_CHUNK_SIZE == 0) {
                    delay(FRAME_DELAY_MS)
                }
            }
        }

        // Also render the last point of the route
        val lastCoord = routeCoords.lastOrNull()
        if (lastCoord != null) {
            val (x, z) = ArUtils.convertGpsToArPosition(
                userLoc = anchorLocation,
                targetLat = lastCoord.lat,
                targetLng = lastCoord.lng,
                yawOffsetDeg = yawOffsetProvider()
            )
            val safeMat = matGrey ?: matRed
            safeMat?.let { placeSphere(x, z, it, PATH_SPHERE_RADIUS, isMilestone = false) }
            sphereCount++
        }

        Log.d(TAG, "Phase 1 complete: $sphereCount path spheres")

        // ====================================================================
        // PHASE 2: Render milestone nodes (larger colored spheres)
        // ====================================================================
        Log.d(TAG, "Phase 2: Rendering milestone nodes...")

        for (node in routeNodePath) {
            val (x, z) = ArUtils.convertGpsToArPosition(
                userLoc = anchorLocation,
                targetLat = node.lat,
                targetLng = node.lng,
                yawOffsetDeg = yawOffsetProvider()
            )

            // Determine color based on node role
            val material = when {
                node.id == startNode.id -> matBlue    // Start: Blue
                node.id == endNode.id -> matGreen     // End: Green
                visitedNodeIds.contains(node.id) -> matGone  // Visited: Faded grey
                else -> matRed                        // Unvisited waypoint: Red
            }

            material?.let {
                placeSphere(x, z, it, NODE_SPHERE_RADIUS, isMilestone = true)

                val nodeType = when {
                    node.id == startNode.id -> "START"
                    node.id == endNode.id -> "END"
                    visitedNodeIds.contains(node.id) -> "VISITED"
                    else -> "WAYPOINT"
                }
                Log.d(TAG, "  Node ${node.id} [$nodeType]: ${node.name ?: "unnamed"} at (${"%.1f".format(x)}, ${"%.1f".format(z)})")
            }
        }

        Log.d(TAG, "╔════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ ROUTE RENDERING COMPLETE")
        Log.d(TAG, "║ Total: $sphereCount path spheres + ${routeNodePath.size} node spheres")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════")
        val renderTimeMs = System.currentTimeMillis() - renderStartTime
        FileLogger.ar("Render COMPLETE: $sphereCount path + ${routeNodePath.size} nodes")
        FileLogger.perf("Route rendering", renderTimeMs, 100L)
    }

    // ========================================================================================
    // SPHERE PLACEMENT
    // ========================================================================================

    /**
     * Places a sphere in the AR scene at the specified local coordinates.
     *
     * COORDINATE SYSTEM:
     * The position (x, y, z) is in LOCAL space relative to the anchor node.
     * - x: Right/Left (positive = right from anchor's perspective)
     * - y: Up/Down (positive = above the anchor)
     * - z: Forward/Back (negative = in front of anchor, positive = behind)
     *
     * Y-AXIS STRATEGY (OUTDOOR OPTIMIZED):
     * Base height: ROUTE_Y_OFFSET (1.2m) above anchor
     * Distance boost: Up to MAX_DISTANCE_HEIGHT_BOOST (0.5m) for distant spheres
     * Milestone boost: MILESTONE_Y_BOOST (0.3m) for milestone nodes
     *
     * @param x Horizontal position (local space, meters)
     * @param z Depth position (local space, meters)
     * @param material The material to apply to the sphere
     * @param radius The sphere radius in meters
     * @param isMilestone Whether this is a milestone node (gets extra height boost)
     */
    private fun placeSphere(
        x: Float,
        z: Float,
        material: MaterialInstance,
        radius: Float,
        isMilestone: Boolean = false
    ) {
        val parent = anchorNode ?: run {
            Log.w(TAG, "Cannot place sphere: No anchor node exists")
            return
        }

        try {
            // ============================================================================
            // ANCHOR-CAMERA OFFSET COMPENSATION
            // ============================================================================
            // The anchor may be 1-3m in front of where the camera (GPS) was.
            // We subtract the offset to align spheres with the GPS-based route.
            // ============================================================================
            val compensatedX = if (hasAnchorOffset) x - anchorCameraOffsetX else x
            val compensatedZ = if (hasAnchorOffset) z - anchorCameraOffsetZ else z

            if (hasAnchorOffset && anchorNode?.childNodes?.size ?: 0 < 3) {
                Log.d(TAG, "Offset compensation applied:")
                Log.d(TAG, "   Original: (${"%.2f".format(x)}, ${"%.2f".format(z)})")
                Log.d(TAG, "   Compensated: (${"%.2f".format(compensatedX)}, ${"%.2f".format(compensatedZ)})")
            }

            // Calculate distance from anchor for dynamic height boost (use compensated values)
            val distanceFromAnchor = kotlin.math.sqrt(compensatedX * compensatedX + compensatedZ * compensatedZ)

            // ============================================================================
            // ANCHOR-TYPE-AWARE Y CALCULATION
            // ============================================================================
            // Different anchor types have different Y characteristics:
            // - PLANE/DEPTH: Y=0 is actual ground → use standard offset
            // - CAMERA_RELATIVE: Y=0 is estimated ground → add safety margin
            // - INSTANT: Y=0 is approximate → add extra safety margin
            // ============================================================================

            // ============================================================================
            // PHASE 3: Improved Y calculation with terrain awareness
            // ============================================================================
            val baseY = when (currentAnchorType) {
                AnchorType.PLANE -> ARPerformanceConfig.BASE_SPHERE_HEIGHT
                AnchorType.DEPTH_POINT -> ARPerformanceConfig.BASE_SPHERE_HEIGHT
                AnchorType.CAMERA_RELATIVE -> ARPerformanceConfig.BASE_SPHERE_HEIGHT + 0.2f
                AnchorType.INSTANT -> ARPerformanceConfig.BASE_SPHERE_HEIGHT + 0.3f
                AnchorType.UNKNOWN -> ARPerformanceConfig.BASE_SPHERE_HEIGHT + 0.1f
            }

            // PHASE 3: Terrain adjustment is handled by SpherePositionCalculator
            // in the Phase 3 rendering path. This fallback path uses base height only
            // because GPS coordinates are not available in placeSphere().
            val terrainAdjustment = 0f

            // Milestone boost: milestone nodes get extra height to stand out
            val milestoneBoost = if (isMilestone) MILESTONE_Y_BOOST else 0f

            // Final Y position with clamping
            val finalY = (baseY + terrainAdjustment + milestoneBoost).coerceIn(
                ARPerformanceConfig.MIN_SPHERE_HEIGHT,
                ARPerformanceConfig.MAX_SPHERE_HEIGHT
            )

            // Log anchor type influence on first few spheres
            if (anchorNode?.childNodes?.size ?: 0 < 3) {
                Log.d(TAG, "Y calculation for ${currentAnchorType}: baseY=${"%.2f".format(baseY)}m (standard=${ROUTE_Y_OFFSET}m)")
            }

            // ============================================================================
            // PHASE 3: Distance-based sphere scaling for visibility
            // ============================================================================
            val scaledRadius = if (distanceFromAnchor > ARPerformanceConfig.SCALE_START_DISTANCE) {
                val t = ((distanceFromAnchor - ARPerformanceConfig.SCALE_START_DISTANCE) /
                        (ARPerformanceConfig.SCALE_MAX_DISTANCE - ARPerformanceConfig.SCALE_START_DISTANCE))
                    .coerceIn(0f, 1f)
                val scaleFactor = 1f + t * (ARPerformanceConfig.MAX_DISTANCE_SCALE - 1f)
                radius * scaleFactor
            } else {
                radius
            }

            val sphereNode = SphereNode(
                engine = arView.engine,
                radius = scaledRadius,
                materialInstance = material
            ).apply {
                position = Position(compensatedX, finalY, compensatedZ)

                // Debug logging for first few spheres to verify positioning
                if (parent.childNodes.size < 5) {
                    Log.d(TAG, "Sphere #${parent.childNodes.size + 1} placed:")
                    Log.d(TAG, "  Local position: (${"%.2f".format(x)}, ${"%.2f".format(finalY)}, ${"%.2f".format(z)})")
                    Log.d(TAG, "  Radius: ${"%.2f".format(radius)}m, Distance: ${"%.1f".format(distanceFromAnchor)}m")
                    Log.d(TAG, "  Type: ${if (isMilestone) "MILESTONE" else "PATH"}")
                }
            }

            parent.addChildNode(sphereNode)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create sphere at ($x, $z): ${e.message}")
        }
    }

    // ========================================================================================
    // SCENE CLEANUP
    // ========================================================================================

    /**
     * Clears all route spheres while keeping the anchor.
     * Used when re-rendering the route (e.g., to update visited nodes).
     *
     * THREAD SAFETY:
     * This function creates a copy of childNodes before iterating to avoid
     * ConcurrentModificationException if rendering happens on a different thread.
     */
    fun clearRoute() {
        val parent = anchorNode
        if (parent != null) {
            try {
                // Create a copy of the list to avoid concurrent modification
                val nodesToRemove = parent.childNodes.toList()
                val count = nodesToRemove.size

                nodesToRemove.forEach { node ->
                    try {
                        node.destroy()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to destroy child node: ${e.message}")
                    }
                }
                Log.d(TAG, "Route cleared: $count nodes removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing route: ${e.message}")
            }
        } else {
            // Fallback: clear any orphaned spheres from the root scene
            try {
                arView.childNodes.toList().forEach { node ->
                    if (node is SphereNode) {
                        node.destroy()
                    }
                }
                Log.w(TAG, "Cleared orphaned spheres from root scene")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing orphaned spheres: ${e.message}")
            }
        }
    }

    /**
     * Clears the anchor and all associated resources.
     * Used during re-anchoring or when leaving AR navigation.
     */
    fun clearAnchor() {
        Log.d(TAG, "Clearing anchor and all resources...")

        // Reset anchor type
        currentAnchorType = AnchorType.UNKNOWN

        // Clear anchor-camera offset
        clearAnchorCameraOffset()

        // Reset segmentation mode
        segmentationEnabled = false

        // First clear all route nodes
        clearRoute()

        // Then destroy the anchor node
        anchorNode?.let { node ->
            try {
                // Detach the anchor from ARCore tracking
                node.anchor?.detach()
                // Destroy the SceneView node
                node.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying anchor node: ${e.message}")
            }
        }
        anchorNode = null

        Log.d(TAG, "✅ Anchor cleared")
    }

    /**
     * Destroys the ARRenderer and releases all resources.
     * MUST be called when the Activity is destroyed to prevent memory leaks.
     */
    fun destroy() {
        Log.d(TAG, "Destroying ARRenderer...")

        // Clear all scene nodes
        clearAnchor()

        // Destroy cached materials
        destroyMaterials()
        // Phase 3 cleanup
        spherePositionCalculator?.shutdown()
        spherePositionCalculator = null
        terrainProfiler?.reset()
        terrainProfiler = null
        Log.d(TAG, "✅ ARRenderer destroyed")
    }

    // ========================================================================================
    // STATISTICS & DEBUGGING
    // ========================================================================================

    /**
     * Returns the number of spheres currently in the scene.
     */
    fun getSphereCount(): Int {
        return anchorNode?.childNodes?.count { it is SphereNode } ?: 0
    }

    /**
     * Returns the world position of the anchor, or null if no anchor exists.
     */
    @Suppress("unused")
    fun getAnchorWorldPosition(): Position? {
        return anchorNode?.worldPosition
    }

    /**
     * Checks if the anchor is still valid and tracking.
     * Returns false if anchor is null, detached, or not tracking.
     */
    @Suppress("unused")
    fun isAnchorValid(): Boolean {
        val anchor = anchorNode?.anchor ?: return false
        return anchor.trackingState == com.google.ar.core.TrackingState.TRACKING
    }

    /**
     * Provides detailed debug information about the current AR rendering state.
     */
    fun getDebugInfo(): String {
        val anchor = anchorNode
        val segmentStatus = if (segmentationEnabled) "ENABLED" else "DISABLED"
        return if (anchor != null) {
            val pos = anchor.worldPosition
            val anchorState = anchor.anchor?.trackingState?.name ?: "UNKNOWN"
            """
        |╔═══════════════════════════════════════
        |║ AR RENDERER DIAGNOSTICS
        |╠═══════════════════════════════════════
        |║ Segmentation Mode: $segmentStatus
        |║ Has Anchor: true
        |║ Anchor Position: (${String.format("%.2f", pos.x)}, ${String.format("%.2f", pos.y)}, ${String.format("%.2f", pos.z)})
        |║ Anchor Tracking: $anchorState
        |║ Sphere Count: ${getSphereCount()}
        |║ Materials Ready: ${materialsReady()}
        |║ Yaw Offset: ${String.format("%.1f", yawOffsetProvider())}°
        |║ Phase 3: ${if (isPhase3Enabled()) "ON" else "OFF"}
        |║ Terrain Adj: ${terrainProfiler?.getLatestEstimate()?.heightAdjustment?.let { "%.2f".format(it) } ?: "N/A"}m
        |╚═══════════════════════════════════════
        """.trimMargin()
        } else {
            """
        |╔═══════════════════════════════════════
        |║ AR RENDERER DIAGNOSTICS
        |╠═══════════════════════════════════════
        |║ Segmentation Mode: $segmentStatus
        |║ Has Anchor: false
        |║ Materials Ready: ${materialsReady()}
        |╚═══════════════════════════════════════
        """.trimMargin()
        }
    }
    // ========================================================================================
    // BEHIND-CAMERA DETECTION
    // ========================================================================================

    /**
     * Result of behind-camera analysis.
     *
     * @param totalSpheres Total number of spheres checked
     * @param spheresBehind Number of spheres behind the camera
     * @param spheresInFront Number of spheres in front of the camera
     * @param percentBehind Percentage of spheres behind camera (0-100)
     * @param isMostlyBehind True if more than 70% of spheres are behind camera
     * @param needsRecalibration True if situation suggests yaw offset is wrong
     */
    data class BehindCameraResult(
        val totalSpheres: Int,
        val spheresBehind: Int,
        val spheresInFront: Int,
        val percentBehind: Float,
        val isMostlyBehind: Boolean,
        val needsRecalibration: Boolean
    )

    /**
     * Analyzes whether spheres are positioned behind the camera.
     *
     * This helps detect yaw offset errors where the entire route is placed
     * in the wrong direction (e.g., behind the user instead of in front).
     *
     * HOW IT WORKS:
     * 1. Gets the camera's forward direction vector
     * 2. For each sphere, calculates vector from camera to sphere
     * 3. Uses dot product to determine if sphere is in front or behind
     *    - Positive dot product = in front of camera
     *    - Negative dot product = behind camera
     * 4. If >70% of spheres are behind, suggests recalibration
     *
     * @return BehindCameraResult with analysis details
     */
    fun analyzeBehindCamera(): BehindCameraResult {
        val anchor = anchorNode
        if (anchor == null) {
            Log.w(TAG, "Cannot analyze: no anchor")
            return BehindCameraResult(0, 0, 0, 0f, false, false)
        }

        val spheres = anchor.childNodes.filterIsInstance<SphereNode>()
        if (spheres.isEmpty()) {
            Log.w(TAG, "Cannot analyze: no spheres")
            return BehindCameraResult(0, 0, 0, 0f, false, false)
        }

        // Get camera position and forward direction
        val cameraPosition = arView.cameraNode.worldPosition
        val cameraForward = arView.cameraNode.forwardDirection

        var behindCount = 0
        var frontCount = 0

        // Check first N spheres (no need to check all for large routes)
        val spheresToCheck = spheres.take(50)

        for (sphere in spheresToCheck) {
            val spherePosition = sphere.worldPosition

            // Vector from camera to sphere
            val toSphereX = spherePosition.x - cameraPosition.x
            val toSphereY = spherePosition.y - cameraPosition.y
            val toSphereZ = spherePosition.z - cameraPosition.z

            // Dot product with camera forward direction
            // Positive = in front, Negative = behind
            val dotProduct = (toSphereX * cameraForward.x) +
                    (toSphereY * cameraForward.y) +
                    (toSphereZ * cameraForward.z)

            if (dotProduct < 0) {
                behindCount++
            } else {
                frontCount++
            }
        }

        val total = spheresToCheck.size
        val percentBehind = if (total > 0) (behindCount.toFloat() / total) * 100f else 0f
        val isMostlyBehind = percentBehind > 70f
        val needsRecalibration = percentBehind > 80f  // Strong indicator of yaw error

        Log.d(TAG, "Behind-camera analysis:")
        Log.d(TAG, "  Total checked: $total")
        Log.d(TAG, "  Behind: $behindCount (${String.format("%.1f", percentBehind)}%)")
        Log.d(TAG, "  In front: $frontCount")
        Log.d(TAG, "  Needs recalibration: $needsRecalibration")

        return BehindCameraResult(
            totalSpheres = total,
            spheresBehind = behindCount,
            spheresInFront = frontCount,
            percentBehind = percentBehind,
            isMostlyBehind = isMostlyBehind,
            needsRecalibration = needsRecalibration
        )
    }

}