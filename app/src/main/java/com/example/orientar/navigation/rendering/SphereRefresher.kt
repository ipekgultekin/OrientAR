package com.example.orientar.navigation.rendering

import android.location.Location
import com.example.orientar.navigation.logic.ArUtils
import com.example.orientar.navigation.logic.Coordinate
import com.example.orientar.navigation.logic.Node
import com.example.orientar.navigation.util.FileLogger
import com.google.android.filament.MaterialInstance
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.SphereNode

/**
 * SphereRefresher — Periodic full-recreate rendering pipeline for AR navigation.
 *
 * Creates spheres into a temp list, attaches to anchor, validates, THEN destroys old.
 * If creation fails, old spheres remain visible (sphere persistence).
 */
class SphereRefresher(
    private val arView: ARSceneView,
    private val routeCoordinates: List<Coordinate>,
    private val routeNodePath: List<Node>,
    private val yawOffsetProvider: () -> Double,
    private val pathMaterial: MaterialInstance,
    private val milestoneMaterial: MaterialInstance
) {
    companion object {
        private const val TAG = "SphereRefresher"
        private const val INITIAL_RENDER_DISTANCE = 6.0
        private const val CONFIRMED_RENDER_DISTANCE = 15.0
        private const val BEHIND_RENDER_DISTANCE = 15.0   // was 5.0
        private const val ANCHOR_RECREATE_DISTANCE = 8.0   // reverted from 12.0; ARCore recommends ≤8m
        private const val MIN_REFRESH_INTERVAL = 1000L
        private const val INTERPOLATION_SPACING = 2.0
        private const val SPHERE_RADIUS = 0.25f
        private const val MILESTONE_RADIUS = 0.35f
        private const val CURVE_SCAN_CUTOFF_MULTIPLIER = 2.0 // Scan up to 2× renderDist for curve-back points
    }

    // State
    private var anchorNode: AnchorNode? = null
    private var anchorGps: Location? = null
    private var sphereNodes = mutableListOf<SphereNode>()
    private var interpolatedRoute: List<Pair<Double, Double>> = emptyList()
    private var interpolatedMilestones: Set<Int> = emptySet()
    private var lastAttemptTime = 0L
    var lastSuccessfulRefreshTime = 0L
        private set
    private var nearestRouteIndex = 0
    private var furthestReachedIndex = 0
    private var minimumAllowedIndex = 0
    private var totalRouteDistanceM = 0.0
    private var isInitialized = false
    private var currentRenderDistance = INITIAL_RENDER_DISTANCE
    private var renderDistanceFloor = INITIAL_RENDER_DISTANCE
    private var lastRefreshYaw = Double.NaN

    // Re-entrancy guard (Fix 2)
    @Volatile
    var isCurrentlyRefreshing = false
        private set

    // ========================================================================================
    // INITIALIZATION
    // ========================================================================================

    fun initialize() {
        val dedupedRoute = mutableListOf<Pair<Double, Double>>()
        for (coord in routeCoordinates) {
            val pair = Pair(coord.lat, coord.lng)
            if (dedupedRoute.isEmpty()) {
                dedupedRoute.add(pair)
            } else {
                val last = dedupedRoute.last()
                val dist = ArUtils.distanceMeters(last.first, last.second, pair.first, pair.second)
                if (dist > 1.0) {
                    dedupedRoute.add(pair)
                }
            }
        }
        val lastCoord = Pair(routeCoordinates.last().lat, routeCoordinates.last().lng)
        if (dedupedRoute.last() != lastCoord) {
            dedupedRoute.add(lastCoord)
        }
        FileLogger.d(TAG, "Route dedup: ${routeCoordinates.size} → ${dedupedRoute.size} coords")

        val interpRoute = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until dedupedRoute.size - 1) {
            val start = dedupedRoute[i]
            val end = dedupedRoute[i + 1]
            val points = ArUtils.interpolate(start.first, start.second, end.first, end.second, INTERPOLATION_SPACING)
            interpRoute.addAll(points)
        }
        interpRoute.add(dedupedRoute.last())
        interpolatedRoute = interpRoute

        val interpMilestones = mutableSetOf<Int>()
        val milestoneCoords = routeNodePath.map { Pair(it.lat, it.lng) }
        for (node in milestoneCoords) {
            var closestIdx = -1
            var closestDist = Double.MAX_VALUE
            for (i in interpolatedRoute.indices) {
                val dist = ArUtils.distanceMeters(
                    interpolatedRoute[i].first, interpolatedRoute[i].second,
                    node.first, node.second
                )
                if (dist < closestDist) { closestDist = dist; closestIdx = i }
            }
            if (closestIdx >= 0 && closestDist < 5.0) interpMilestones.add(closestIdx)
        }
        interpolatedMilestones = interpMilestones
        FileLogger.d(TAG, "Milestones: ${interpolatedMilestones.size} for ${interpolatedRoute.size} points " +
            "(indices: ${interpolatedMilestones.sorted()})")

        totalRouteDistanceM = 0.0
        for (i in 0 until interpolatedRoute.size - 1) {
            totalRouteDistanceM += ArUtils.distanceMeters(
                interpolatedRoute[i].first, interpolatedRoute[i].second,
                interpolatedRoute[i + 1].first, interpolatedRoute[i + 1].second
            )
        }

        isInitialized = true
        FileLogger.d(TAG, "Initialized: ${interpolatedRoute.size} points, " +
            "${totalRouteDistanceM.toInt()}m total, ${interpolatedMilestones.size} milestones")
    }

    // ========================================================================================
    // MAIN REFRESH — Create, Attach, Verify, THEN Destroy Old (Fix 1)
    // ========================================================================================

    fun refresh(userGps: Location, frame: Frame, session: Session) {
        // Re-entrancy guard (Fix 2)
        if (isCurrentlyRefreshing) {
            FileLogger.d("REFRESH_GUARD", "Skipped: another refresh in progress")
            return
        }
        isCurrentlyRefreshing = true
        try {
            doRefresh(userGps, frame, session)
        } finally {
            isCurrentlyRefreshing = false
        }
    }

    private fun doRefresh(userGps: Location, frame: Frame, session: Session) {
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            FileLogger.d("REFRESH_SKIP", "Skipped: camera not tracking (state=${frame.camera.trackingState})")
            return
        }
        if (!isInitialized) return

        val now = System.currentTimeMillis()
        lastAttemptTime = now

        // Fix 3: Rate limit from last SUCCESS, not last attempt
        if (now - lastSuccessfulRefreshTime < MIN_REFRESH_INTERVAL) return

        val userLat = userGps.latitude
        val userLng = userGps.longitude
        val yawOffset = yawOffsetProvider()
        val cameraY = frame.camera.pose.ty()

        // --- 1. Anchor management ---
        val currentAnchorGps = anchorGps
        val anchorDist = if (currentAnchorGps != null) {
            ArUtils.distanceMeters(currentAnchorGps.latitude, currentAnchorGps.longitude, userLat, userLng)
        } else {
            Double.MAX_VALUE
        }

        // --- 1b. Skip if heading barely changed and anchor close ---
        val yawChange = if (lastRefreshYaw.isNaN()) 999.0 else Math.abs(yawOffset - lastRefreshYaw)
        val normalizedYawChange = if (yawChange > 180) 360 - yawChange else yawChange
        if (normalizedYawChange < 2.0 && anchorDist < ANCHOR_RECREATE_DISTANCE && sphereNodes.isNotEmpty()) {
            updateProgressOnly(userLat, userLng)
            lastSuccessfulRefreshTime = now
            return
        }
        lastRefreshYaw = yawOffset

        // --- 2. Anchor recreation if needed ---
        if (anchorDist > ANCHOR_RECREATE_DISTANCE || anchorNode == null) {
            val cameraPose = frame.camera.pose
            val anchorPose = com.google.ar.core.Pose(
                floatArrayOf(cameraPose.tx(), cameraPose.ty() - 1.3f, cameraPose.tz()),
                floatArrayOf(0f, 0f, 0f, 1f)
            )
            try {
                val newAnchor = session.createAnchor(anchorPose)
                val newAnchorNode = AnchorNode(arView.engine, newAnchor).also { node ->
                    node.isEditable = false
                    node.visibleCameraTrackingStates = setOf(TrackingState.TRACKING, TrackingState.PAUSED)
                    node.visibleTrackingStates = setOf(TrackingState.TRACKING, TrackingState.PAUSED)
                    arView.addChildNode(node)
                }
                // Anchor created — destroy old anchor + spheres
                FileLogger.d("SPHERE_SWAP", "Anchor recreation: ${sphereNodes.size} spheres temporarily removed")
                destroyAnchor()
                anchorNode = newAnchorNode
                anchorGps = Location("refresh").apply {
                    latitude = userLat; longitude = userLng; accuracy = userGps.accuracy
                }
                FileLogger.d("ANCHOR_NEW", "Identity anchor at " +
                    "pos=(${String.format("%.1f", cameraPose.tx())}, ${String.format("%.1f", cameraPose.ty() - 1.3f)}, ${String.format("%.1f", cameraPose.tz())}), " +
                    "gps=(${String.format("%.6f", userLat)}, ${String.format("%.6f", userLng)})")
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to create anchor: ${e.message}")
                return  // Keep old spheres
            }
        }

        // --- 3. Progress tracking ---
        updateProgressOnly(userLat, userLng)
        FileLogger.d("PROGRESS", "Index: raw=${findNearestIndex(userLat, userLng)}, clamped=$nearestRouteIndex, " +
            "min=$minimumAllowedIndex, furthest=$furthestReachedIndex, total=${interpolatedRoute.size}")

        // --- 4. Create new spheres into temp list (Fix 1: create before destroy) ---
        val currentAnchorNode = anchorNode ?: return
        val anchorGpsLocal = anchorGps ?: return
        val anchorWorldY = currentAnchorNode.worldPosition.y
        val newSpheres = mutableListOf<SphereNode>()
        var milestoneCount = 0
        var totalPointsChecked = 0
        var pointsBeyondRange = 0
        val startIdx = (nearestRouteIndex - 8).coerceAtLeast(0)   // was 3; 8 indices = 16m > GPS error range

        for (idx in startIdx until interpolatedRoute.size) {
            totalPointsChecked++
            val point = interpolatedRoute[idx]
            val distFromUser = ArUtils.distanceMeters(userLat, userLng, point.first, point.second)
            if (idx < nearestRouteIndex && distFromUser > BEHIND_RENDER_DISTANCE) continue
            if (idx > nearestRouteIndex && distFromUser > currentRenderDistance) {
                pointsBeyondRange++
                // Don't break — route may curve back within range
                if (distFromUser > currentRenderDistance * CURVE_SCAN_CUTOFF_MULTIPLIER) break
                continue
            }

            val (arX, arZ) = ArUtils.convertGpsToArPosition(
                userLoc = anchorGpsLocal, targetLat = point.first,
                targetLng = point.second, yawOffsetDeg = yawOffset
            )
            val sphereY = cameraY - 0.5f - anchorWorldY
            val isMilestone = interpolatedMilestones.contains(idx)
            val radius = if (isMilestone) MILESTONE_RADIUS else SPHERE_RADIUS
            val material = if (isMilestone) milestoneMaterial else pathMaterial

            val sphere = SphereNode(
                engine = arView.engine, radius = radius, materialInstance = material
            ).apply {
                position = io.github.sceneview.math.Position(arX, sphereY, arZ)
            }
            newSpheres.add(sphere)
            if (isMilestone) milestoneCount++
        }

        // --- 5. Validate + Swap (Fix 1: verify before destroying old) ---
        val swapIsValid = anchorNode != null && newSpheres.isNotEmpty() &&
            newSpheres.all { !it.position.x.isNaN() && !it.position.z.isNaN() }

        if (swapIsValid) {
            // Attach new spheres
            for (sphere in newSpheres) {
                currentAnchorNode.addChildNode(sphere)
            }
            // Snapshot old spheres BEFORE clearing reference
            val previousSpheres = ArrayList(sphereNodes)
            // Update reference to new set
            sphereNodes = newSpheres
            // NOW destroy old spheres
            for (node in previousSpheres) {
                try { node.destroy() } catch (e: Exception) {
                    FileLogger.w(TAG, "Error destroying old sphere: ${e.message}")
                }
            }

            val refreshEnd = System.currentTimeMillis()
            val gap = if (lastSuccessfulRefreshTime > 0) refreshEnd - lastSuccessfulRefreshTime else 0
            if (gap > 2000) FileLogger.d("REFRESH_GAP", "Gap since last refresh: ${gap}ms")
            lastSuccessfulRefreshTime = refreshEnd

            FileLogger.d("SPHERE_SWAP", "Swapped: ${previousSpheres.size} old → ${newSpheres.size} new")
            FileLogger.d("REFRESH", "Complete: ${newSpheres.size} spheres ($milestoneCount milestones), " +
                "anchorDist=${if (anchorDist > 100000) "new" else "${anchorDist.toInt()}m"}, nearest=$nearestRouteIndex/${interpolatedRoute.size}, " +
                "yaw=${yawOffset.toInt()}°, renderDist=${String.format("%.0f", currentRenderDistance)}m")

            // Diagnostic: capture context for low-sphere cases
            if (newSpheres.size <= 2) {
                FileLogger.w("LOW_SPHERES", "Only ${newSpheres.size} spheres: " +
                    "renderDist=${String.format("%.1f", currentRenderDistance)}m, " +
                    "nearest=$nearestRouteIndex/${interpolatedRoute.size}, " +
                    "yaw=${yawOffset.toInt()}°, " +
                    "checked=$totalPointsChecked, beyondRange=$pointsBeyondRange")
            }
        } else {
            // Swap failed — destroy new spheres, keep old visible
            for (sphere in newSpheres) {
                try { sphere.destroy() } catch (e: Exception) { /* ignore */ }
            }
            FileLogger.w("SPHERE_SWAP", "Swap FAILED: anchor=${anchorNode != null}, " +
                "newCount=${newSpheres.size}, keeping ${sphereNodes.size} old spheres")
        }
    }

    // ========================================================================================
    // PER-FRAME Y UPDATE
    // ========================================================================================

    fun updateYPositions(smoothedCameraY: Float) {
        val currentAnchorNode = anchorNode ?: return
        val anchorWorldY = currentAnchorNode.worldPosition.y

        if (anchorWorldY.isNaN() || anchorWorldY.isInfinite() || Math.abs(anchorWorldY) > 500f) {
            FileLogger.w("Y_GUARD", "anchorWorldY=${String.format("%.1f", anchorWorldY)} out of bounds — skipping")
            return
        }
        if (smoothedCameraY.isNaN() || smoothedCameraY.isInfinite() || Math.abs(smoothedCameraY) > 500f) {
            FileLogger.w("Y_GUARD", "smoothedCameraY=${String.format("%.1f", smoothedCameraY)} out of bounds — skipping")
            return
        }

        val targetY = smoothedCameraY - 0.5f - anchorWorldY
        for (sphere in sphereNodes) {
            val currentPos = sphere.position
            if (currentPos.y != targetY) {
                sphere.position = io.github.sceneview.math.Position(currentPos.x, targetY, currentPos.z)
            }
        }
    }

    // ========================================================================================
    // PROGRESS & STATE QUERIES
    // ========================================================================================

    fun hasActiveSpheres(): Boolean = sphereNodes.isNotEmpty()
    fun getProgressPercent(): Int {
        if (interpolatedRoute.isEmpty()) return 0
        return ((furthestReachedIndex.toFloat() / interpolatedRoute.size) * 100).toInt().coerceIn(0, 100)
    }
    fun getRemainingDistanceMeters(): Double {
        if (interpolatedRoute.isEmpty() || nearestRouteIndex >= interpolatedRoute.size - 1) return 0.0
        var remaining = 0.0
        for (i in nearestRouteIndex until interpolatedRoute.size - 1) {
            remaining += ArUtils.distanceMeters(
                interpolatedRoute[i].first, interpolatedRoute[i].second,
                interpolatedRoute[i + 1].first, interpolatedRoute[i + 1].second
            )
        }
        return remaining
    }
    fun isArrived(): Boolean {
        if (interpolatedRoute.isEmpty()) return false
        return getRemainingDistanceMeters() < 10.0 && furthestReachedIndex > interpolatedRoute.size * 0.8
    }
    fun getNearestRouteIndex(): Int = nearestRouteIndex
    fun getTotalInterpolatedPoints(): Int = interpolatedRoute.size

    // Fix 5: Smooth render distance ramp
    fun updateHeadingConfidence(motionUpdateCount: Int) {
        val oldDistance = currentRenderDistance
        val progress = minOf(motionUpdateCount.toDouble(), 5.0) / 5.0
        currentRenderDistance = maxOf(
            INITIAL_RENDER_DISTANCE + (CONFIRMED_RENDER_DISTANCE - INITIAL_RENDER_DISTANCE) * progress,
            renderDistanceFloor
        )
        if (Math.abs(currentRenderDistance - oldDistance) > 0.5) {
            FileLogger.d("RENDER_DIST", "${String.format("%.0f", oldDistance)}m → ${String.format("%.0f", currentRenderDistance)}m (motionUpdates=$motionUpdateCount)")
        }
    }

    // ========================================================================================
    // CLEANUP
    // ========================================================================================

    fun clearAll() {
        destroyAnchor()
        nearestRouteIndex = 0; furthestReachedIndex = 0; minimumAllowedIndex = 0
        currentRenderDistance = INITIAL_RENDER_DISTANCE
        renderDistanceFloor = INITIAL_RENDER_DISTANCE  // full reset — no floor protection
        lastAttemptTime = 0L; lastSuccessfulRefreshTime = 0L; lastRefreshYaw = Double.NaN
        FileLogger.d(TAG, "Cleared all spheres and anchor")
    }

    fun clearForRecalibration() {
        destroyAnchor()
        lastAttemptTime = 0L; lastSuccessfulRefreshTime = 0L; lastRefreshYaw = Double.NaN
        // Partial reset: never drop below 10m on recalibration (was full reset to 6m)
        currentRenderDistance = maxOf(currentRenderDistance * 0.7, 10.0)
        renderDistanceFloor = currentRenderDistance  // remember partial reset intent
        FileLogger.d(TAG, "Cleared for recalibration (preserved progress: " +
            "nearest=$nearestRouteIndex, furthest=$furthestReachedIndex, min=$minimumAllowedIndex, " +
            "renderDist=${String.format("%.0f", currentRenderDistance)}m)")
    }

    fun destroy() {
        clearAll()
        interpolatedRoute = emptyList()
        interpolatedMilestones = emptySet()
        isInitialized = false
    }

    // ========================================================================================
    // PRIVATE HELPERS
    // ========================================================================================

    private fun updateProgressOnly(userLat: Double, userLng: Double) {
        val rawNearest = findNearestIndex(userLat, userLng)
        nearestRouteIndex = rawNearest.coerceAtLeast(minimumAllowedIndex)
        if (nearestRouteIndex > minimumAllowedIndex + 2) minimumAllowedIndex = nearestRouteIndex - 8
        if (nearestRouteIndex > furthestReachedIndex) furthestReachedIndex = nearestRouteIndex
    }

    private fun findNearestIndex(lat: Double, lng: Double): Int {
        val windowStart = (nearestRouteIndex - 8).coerceAtLeast(0)
        val windowEnd = (nearestRouteIndex + 15).coerceAtMost(interpolatedRoute.size - 1)
        var minDist = Double.MAX_VALUE
        var minIdx = nearestRouteIndex
        for (i in windowStart..windowEnd) {
            val d = ArUtils.distanceMeters(lat, lng, interpolatedRoute[i].first, interpolatedRoute[i].second)
            if (d < minDist) { minDist = d; minIdx = i }
        }
        if (minDist > 30.0) {
            for (i in interpolatedRoute.indices) {
                val d = ArUtils.distanceMeters(lat, lng, interpolatedRoute[i].first, interpolatedRoute[i].second)
                if (d < minDist) { minDist = d; minIdx = i }
            }
            FileLogger.d("PROGRESS", "Full scan fallback: nearest=$minIdx (${minDist.toInt()}m away)")
        }
        return minIdx
    }

    private fun destroySpheres() {
        for (node in sphereNodes) {
            try { node.destroy() } catch (e: Exception) {
                FileLogger.w(TAG, "Error destroying sphere: ${e.message}")
            }
        }
        sphereNodes.clear()
    }

    private fun destroyAnchor() {
        destroySpheres()
        anchorNode?.let { node ->
            try { node.anchor?.detach(); node.destroy() } catch (e: Exception) {
                FileLogger.w(TAG, "Error destroying anchor: ${e.message}")
            }
        }
        anchorNode = null; anchorGps = null
    }
}
