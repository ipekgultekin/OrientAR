package com.example.orientar.navigation.logic

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.PriorityQueue
import kotlin.math.*
import com.example.orientar.navigation.util.FileLogger

// --- Data Models ---
data class Node(
    val id: Int,
    val lat: Double,
    val lng: Double,
    val type: String,
    val name: String? = null,
    var neighbors: MutableList<Edge> = mutableListOf()
)

data class Edge(
    val targetNodeId: Int,
    val distance: Double,
    val pathGeometry: List<Coordinate>
)

data class Coordinate(val lat: Double, val lng: Double)

class CampusGraph(private val context: Context) {

    val nodes = HashMap<Int, Node>()
    val destinations = ArrayList<Node>()

    // Counters (for debugging purposes)
    var totalFeatureCount = 0
    var intersectionCount = 0
    var pathCount = 0
    var connectedPathCount = 0

    // Since path endpoints do not exactly match node coordinates,
    // We snap to the nearest node within this tolerance.
    private val SNAP_TOLERANCE_METERS = 1.0

    init {
        loadGraphFromAssets()
        logGraphHealthReport() // Generate report immediately after loading
    }

    private fun loadGraphFromAssets() {
        try {
            val jsonString = context.assets.open("Map/map.geojson").bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val features = json.getJSONArray("features")

            totalFeatureCount = features.length()

            // We generate our own internal node IDs (0..N-1) instead of using GeoJSON IDs
            // to avoid potential conflicts or inconsistencies.
            var autoNodeId = 0

            // ------------------------------------------------------------
            // 1) Read Points -> Create Nodes
            // ------------------------------------------------------------
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")

                if (geometry.getString("type") == "Point") {
                    val props = feature.getJSONObject("properties")

                    val id = autoNodeId++ // ✅ Bizim internal node id'miz

                    val coords = geometry.getJSONArray("coordinates")
                    val lng = coords.getDouble(0)
                    val lat = coords.getDouble(1)

                    val type = props.optString("Type", "Intersection")

                    // IMPORTANT: One destination in the source file has a trailing space ("Name ").
                    // We try "Name" first, then fallback to "Name " to ensure data integrity.
                    val name = props.optString("Name", props.optString("Name ", null))

                    val newNode = Node(id, lat, lng, type, name)
                    nodes[id] = newNode

                    if (type == "Destination") {
                        destinations.add(newNode)
                    } else {
                        intersectionCount++
                    }
                }
            }

            // ------------------------------------------------------------
            // 2) Read LineStrings -> Connect Edges
            // ------------------------------------------------------------
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")

                if (geometry.getString("type") == "LineString") {
                    pathCount++

                    val coordsArray = geometry.getJSONArray("coordinates")
                    val pathCoords = mutableListOf<Coordinate>()

                    for (j in 0 until coordsArray.length()) {
                        val p = coordsArray.getJSONArray(j)
                        // GeoJSON uses [lng, lat], we map it to Coordinate(lat, lng)
                        pathCoords.add(Coordinate(p.getDouble(1), p.getDouble(0)))
                    }

                    // Snap the first and last points of the path to the nearest node.
                    val startNodeId = findClosestNode(pathCoords.first())
                    val endNodeId = findClosestNode(pathCoords.last())

                    // Add edge only if both ends are found and they are distinct nodes.
                    if (startNodeId != -1 && endNodeId != -1 && startNodeId != endNodeId) {
                        val dist = calculatePathLength(pathCoords)

                        // Bidirectional graph: Add A->B and B->A
                        nodes[startNodeId]?.neighbors?.add(
                            Edge(endNodeId, dist, pathCoords)
                        )

                        // Reverse path geometry when traversing in the opposite direction
                        nodes[endNodeId]?.neighbors?.add(
                            Edge(startNodeId, dist, pathCoords.reversed())
                        )

                        connectedPathCount++
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("GraphTest", "ERROR: ${e.message}")
        }
    }

    // ============================================================
    // OPTIMIZED A* ALGORITHM (Between any nodes, including Destination -> Destination)
    // ============================================================

    /**
     * Useful for logic/debugging: Returns the chain of Nodes.
     * Not sufficient for rendering the full path geometry alone.
     */
    fun findNodePath(startNodeId: Int, targetNodeId: Int): List<Node> {
        val result = runAStar(startNodeId, targetNodeId) ?: return emptyList()
        val cameFromNode = result.first

        // If target was not reached, return empty
        if (!cameFromNode.containsKey(targetNodeId) && targetNodeId != startNodeId) return emptyList()

        // Reconstruct node chain backwards
        val nodeChain = mutableListOf<Node>()
        var curr = targetNodeId
        nodeChain.add(nodes[curr] ?: return emptyList())

        while (curr != startNodeId) {
            val parent = cameFromNode[curr] ?: return emptyList()
            curr = parent
            nodeChain.add(nodes[curr] ?: return emptyList())
        }

        return nodeChain.reversed()
    }

    /**
     * Best for rendering: Returns the detailed list of Coordinates (Polyline / AR route).
     * Uses 'cameFromEdge' to ensure the exact path geometry is selected.
     */
    fun findPath(startNodeId: Int, targetNodeId: Int): List<Coordinate> {
        val (cameFromNode, cameFromEdge) = runAStar(startNodeId, targetNodeId) ?: return emptyList()
        return reconstructPathWithEdges(startNodeId, targetNodeId, cameFromNode, cameFromEdge)
    }

    /**
     * Core A* Implementation:
     * Returns both the node chain (cameFromNode) and the specific edge used (cameFromEdge).
     * Storing the edge is critical to avoid selecting the wrong geometry in multi-edge scenarios.
     */
    private fun runAStar(startNodeId: Int, targetNodeId: Int): Pair<Map<Int, Int>, Map<Int, Edge>>? {
        val start = nodes[startNodeId] ?: return null
        val goal = nodes[targetNodeId] ?: return null
        // Log A* start
        FileLogger.route("A* START: node $startNodeId → node $targetNodeId", forceLog = true)

        val openSet = PriorityQueue<PathNode>()
        val cameFromNode = HashMap<Int, Int>() // child -> parent
        val cameFromEdge = HashMap<Int, Edge>() // child -> edge used to reach child

        val gScore = HashMap<Int, Double>()
        val closedSet = HashSet<Int>() // Set of finalized nodes

        gScore[startNodeId] = 0.0
        val h0 = haversine(start.lat, start.lng, goal.lat, goal.lng)
        openSet.add(PathNode(startNodeId, h0))

        while (openSet.isNotEmpty()) {
            val current = openSet.poll()
            val currentId = current.id

            // STALE CHECK:
            // PriorityQueue may contain duplicate entries for the same node.
            // If the popped entry is worse than what we already found, skip it.
            val currentG = gScore[currentId] ?: Double.MAX_VALUE
            val currentNode = nodes[currentId] ?: continue
            val bestPossibleF = currentG + haversine(currentNode.lat, currentNode.lng, goal.lat, goal.lng)

            if (current.fScore > bestPossibleF + 1e-6) continue

            // Target reached
            if (currentId == targetNodeId) {
                FileLogger.route("A* SUCCESS: found path to node $targetNodeId", forceLog = true)
                return Pair(cameFromNode, cameFromEdge)
            }

            // Closed set: Do not expand a finalized node again
            if (currentId in closedSet) continue
            closedSet.add(currentId)

            for (edge in currentNode.neighbors) {
                val neighborId = edge.targetNodeId
                if (neighborId in closedSet) continue

                val tentativeG = currentG + edge.distance
                val oldG = gScore.getOrDefault(neighborId, Double.MAX_VALUE)

                // Update if a better path is found
                if (tentativeG < oldG) {
                    cameFromNode[neighborId] = currentId

                    // CRITICAL: Store the specific edge used to reach the neighbor.
                    // This solves the "wrong edge selection" bug during reconstruction.
                    cameFromEdge[neighborId] = edge

                    gScore[neighborId] = tentativeG

                    val neighborNode = nodes[neighborId] ?: continue
                    val h = haversine(neighborNode.lat, neighborNode.lng, goal.lat, goal.lng)
                    openSet.add(PathNode(neighborId, tentativeG + h))
                }
            }
        }
        FileLogger.e("ROUTE", "A* FAILED: No path from node $startNodeId to node $targetNodeId")
        return null // Target not reachable
    }

    /**
     * Reconstructs the full path geometry using the stored edges.
     * Ensures the route line follows the exact path taken without gaps.
     */
    private fun reconstructPathWithEdges(
        startNodeId: Int,
        targetNodeId: Int,
        cameFromNode: Map<Int, Int>,
        cameFromEdge: Map<Int, Edge>
    ): List<Coordinate> {

        // 1) Extract the chain of node IDs
        val nodeChain = mutableListOf<Int>()
        var curr = targetNodeId
        nodeChain.add(curr)

        while (curr != startNodeId) {
            val parent = cameFromNode[curr] ?: return emptyList()
            curr = parent
            nodeChain.add(curr)
        }
        nodeChain.reverse()

        // 2) Append geometry of each edge sequentially
        val fullPath = mutableListOf<Coordinate>()
        for (i in 1 until nodeChain.size) {
            val child = nodeChain[i]
            val edgeUsed = cameFromEdge[child] ?: continue

            // Prevent adding duplicate coordinates on top of each other.
            // This reduces visual jitter in rendering.
            if (edgeUsed.pathGeometry.isEmpty()) continue

            if (fullPath.isEmpty()) {
                fullPath.addAll(edgeUsed.pathGeometry)
            } else {
                val last = fullPath.last()
                val next = edgeUsed.pathGeometry

                // Skip the first point of the new segment if it matches the last point of the previous segment
                val startIndex = if (almostSame(last, next.first())) 1 else 0
                for (k in startIndex until next.size) {
                    fullPath.add(next[k])
                }
            }
        }
        FileLogger.route("Path reconstructed: ${fullPath.size} coordinates", forceLog = true)
        return fullPath
    }

    /**
     * Checks if two coordinates are effectively the same using epsilon comparison.
     */
    private fun almostSame(a: Coordinate, b: Coordinate, eps: Double = 1e-10): Boolean {
        return abs(a.lat - b.lat) < eps && abs(a.lng - b.lng) < eps
    }

    /**
     * Finds the nearest node to snap path endpoints.
     * Currently performs an O(N) scan. Can be optimized with Grid/KD-Tree if the map grows.
     */
    fun findClosestNode(coord: Coordinate): Int {
        // Gradual tolerance list (meters)
        // If 1m is not enough, expand search radius sequentially. 1 - 2 - 3 - 5
        val tolerances = doubleArrayOf(
            SNAP_TOLERANCE_METERS, // 1.0
            2.0,
            3.0,
            5.0
        )

        for (tol in tolerances) {
            var closestId = -1
            var minDistance = tol

            for ((id, node) in nodes) {
                val dist = haversine(coord.lat, coord.lng, node.lat, node.lng)
                if (dist < minDistance) {
                    minDistance = dist
                    closestId = id
                }
            }

            if (closestId != -1) {
                return closestId
            }
        }

        return -1
    }


    private fun calculatePathLength(path: List<Coordinate>): Double {
        var dist = 0.0
        for (i in 0 until path.size - 1) {
            dist += haversine(path[i].lat, path[i].lng, path[i + 1].lat, path[i + 1].lng)
        }
        return dist
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    data class PathNode(val id: Int, val fScore: Double) : Comparable<PathNode> {
        override fun compareTo(other: PathNode): Int = this.fScore.compareTo(other.fScore)
    }

    // ============================================================
    // DEBUG / GRAPH HEALTH REPORT
    // ============================================================
    private fun logGraphHealthReport() {
        // Count destinations
        val destinationCount = destinations.size

        // Count isolated nodes (nodes with no neighbors)
        val isolated = nodes.values.count { it.neighbors.isEmpty() }

        // Multi-edge check: How many pairs exist with more than one edge between them?
        // This is important because 'reconstructPath' must select the correct edge geometry.
        var multiEdgePairs = 0
        var totalPairsWithDuplicates = 0

        for ((_, node) in nodes) {
            val counts = HashMap<Int, Int>()
            for (e in node.neighbors) {
                counts[e.targetNodeId] = (counts[e.targetNodeId] ?: 0) + 1
            }
            val dupTargets = counts.filterValues { it > 1 }
            if (dupTargets.isNotEmpty()) {
                multiEdgePairs += dupTargets.size
                totalPairsWithDuplicates += dupTargets.values.sum()
            }
        }

        Log.d(
            "GraphReport",
            """
            ===== GRAPH HEALTH REPORT =====
            Total features: $totalFeatureCount
            Nodes loaded: ${nodes.size} (Destinations: $destinationCount, Intersections: $intersectionCount)
            Paths total: $pathCount
            Paths connected (snapped): $connectedPathCount
            SNAP_TOLERANCE_METERS: $SNAP_TOLERANCE_METERS
            Isolated nodes (no neighbors): $isolated
            Multi-edge target pairs count: $multiEdgePairs
            Total duplicated edges across those pairs: $totalPairsWithDuplicates
            ===============================
            """.trimIndent()
        )
    }
}
