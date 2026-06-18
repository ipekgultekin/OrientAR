package com.example.orientar.navigation.logic

import com.example.orientar.navigation.util.FileLogger
import kotlin.math.abs

/**
 * Owns route-progress and node-visitation state. Extracted from the activity so
 * arrival-detection logic is JVM-testable; callback lambdas keep all UI dispatch
 * in the caller.
 *
 * [markVisitedNodes] runs on every [updateProgress] call regardless of whether
 * `lastClosestRouteIndex` advanced — gating on advancement previously suppressed
 * visitation once the index saturated at the last coordinate, so a user within
 * `arrivalThreshold` of the destination never triggered `onArrival`. The PROGRESS
 * log line stays rate-limited and still only emits on advance.
 *
 * Not thread-safe — the activity always calls [updateProgress] from the GPS-update
 * path on the main thread.
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
        // Index-search window around the last known position.
        private const val SEARCH_WINDOW_BEHIND = 50
        private const val SEARCH_WINDOW_AHEAD = 200
        // Minimum index advance before re-emitting the PROGRESS log line.
        private const val RERENDER_PROGRESS_DELTA = 12
    }

    private var lastClosestRouteIndex: Int = 0
    private var lastRenderProgressIndex: Int = 0
    private val visitedNodeIds = HashSet<Int>()

    /**
     * Update progress for the given user position. Primitive inputs (no Android
     * Location dependency) keep this JVM-testable.
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

        // markVisitedNodes runs on every update regardless of index advance — the
        // visitation check must not be gated on movement. PROGRESS log stays
        // rate-limited (delta >= 12) and still only fires on advance.
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
