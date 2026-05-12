package com.example.orientar.navigation.logic

import com.example.orientar.navigation.util.FileLogger
import kotlin.math.abs

/**
 * RouteProgressTracker — owns route-progress and node-visitation state.
 *
 * Extracted from ArNavigationActivity (SCRUM-120 Step 2) to enable JVM unit
 * testing of the arrival-detection logic. Constructor copies references to
 * the route data and accepts callback lambdas for arrival / checkpoint
 * side-effects, keeping all UI dispatch in the caller.
 *
 * SCRUM-120 FIX (Step 3):
 *   [markVisitedNodes] now runs on EVERY [updateProgress] call, regardless
 *   of whether `lastClosestRouteIndex` advanced. Previously the gate
 *   `if (bestIdx != lastClosestRouteIndex)` suppressed the visitation check
 *   once the index saturated at `routeCoords.size - 1`, meaning the user
 *   could be within `arrivalThreshold` of the destination but never trigger
 *   `onArrival`. Field reproduction: walk5_noshi_a2_extended.log (980m route,
 *   ~+938s onwards) and the May-2026 Prep → Rectory 167.5m walk both showed
 *   index saturation without arrival.
 *
 *   The PROGRESS log line is still rate-limited and still only emits on
 *   index advance — only the visitation check was de-gated.
 *
 * Threading: not thread-safe. The Activity always calls [updateProgress]
 * from the GPS-update path on the main thread, so this matches the
 * pre-extraction guarantees.
 */
class RouteProgressTracker(
    private val routeCoords: List<Coordinate>,
    private val routeNodePath: List<Node>,
    private val selectedEndNode: Node?,
    private val arrivalThreshold: Float = 5.0f,
    private val onArrival: (Node) -> Unit,
    private val onCheckpoint: (Node) -> Unit
) {
    companion object {
        private const val TAG = "RouteProgressTracker"
        // Search-window sizing — mirrors the pre-extraction values at
        // ArNavigationActivity.kt:1716-1717.
        private const val SEARCH_WINDOW_BEHIND = 50
        private const val SEARCH_WINDOW_AHEAD = 200
        // Rate-limit for the PROGRESS log line — mirrors the constant
        // previously at ArNavigationActivity.kt:196 (RERENDER_PROGRESS_DELTA = 12).
        private const val RERENDER_PROGRESS_DELTA = 12
    }

    private var lastClosestRouteIndex: Int = 0
    private var lastRenderProgressIndex: Int = 0
    private val visitedNodeIds = HashSet<Int>()

    /**
     * Update progress for the given user position.
     *
     * Primitive-input entry point (no Android Location dependency) — follows
     * the pattern established by CoordinateAligner.addAlignmentSample.
     */
    fun updateProgress(userLat: Double, userLng: Double) {
        if (routeCoords.isEmpty()) return

        var bestIdx = lastClosestRouteIndex
        var bestDist = Double.MAX_VALUE

        val startIdx = (lastClosestRouteIndex - SEARCH_WINDOW_BEHIND).coerceAtLeast(0)
        val endIdx = (lastClosestRouteIndex + SEARCH_WINDOW_AHEAD).coerceAtMost(routeCoords.size - 1)

        for (i in startIdx..endIdx) {
            val p = routeCoords[i]
            val d = ArUtils.distanceMeters(userLat, userLng, p.lat, p.lng)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }

        // SCRUM-120 fix: markVisitedNodes runs on every update regardless of
        // index advance. The PROGRESS log stays rate-limited (delta >= 12) and
        // still only fires on advance; only the visitation check was de-gated.
        val indexAdvanced = (bestIdx != lastClosestRouteIndex)
        if (indexAdvanced) {
            lastClosestRouteIndex = bestIdx
        }
        markVisitedNodes(userLat, userLng)
        if (indexAdvanced && abs(lastClosestRouteIndex - lastRenderProgressIndex) >= RERENDER_PROGRESS_DELTA) {
            lastRenderProgressIndex = lastClosestRouteIndex
            FileLogger.d("PROGRESS", "Progress updated: nearest=$lastClosestRouteIndex, visited=${visitedNodeIds.size}")
        }
    }

    private fun markVisitedNodes(userLat: Double, userLng: Double) {
        for (node in routeNodePath) {
            if (visitedNodeIds.contains(node.id)) continue

            val distance = ArUtils.distanceMeters(userLat, userLng, node.lat, node.lng)

            if (distance < arrivalThreshold) {
                visitedNodeIds.add(node.id)
                val isDestination = (node.id == selectedEndNode?.id)
                if (isDestination) {
                    onArrival(node)
                } else {
                    onCheckpoint(node)
                }
            }
        }
    }

    /** Current nearest-route index. Returns 0 before any updateProgress call. */
    fun getCurrentIndex(): Int = lastClosestRouteIndex

    /** Set of node IDs that have been within arrivalThreshold. Snapshot copy. */
    fun getVisitedNodeIds(): Set<Int> = visitedNodeIds.toSet()
}
