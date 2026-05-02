package com.example.orientar.navigation.logic

import android.content.Context
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

class CampusGraph private constructor() {

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

    /**
     * Production entry point: reads the campus map from app assets.
     * Behavior is identical to the pre-refactor `CampusGraph(context)` constructor.
     */
    constructor(context: Context) : this() {
        val jsonString = context.assets.open("Map/map.geojson").bufferedReader().use { it.readText() }
        loadGraphFromJsonString(jsonString)
        logGraphHealthReport() // Generate report immediately after loading
    }

    /**
     * Parse a complete GeoJSON FeatureCollection string into the graph.
     *
     * Used by the [Context]-based constructor (after asset read) and by the
     * [fromGeoJsonString] factory (for JVM unit tests, no Android Context needed).
     *
     * Schema expected:
     *   - `Point` features become [Node]s. Properties may include `Type` ("Destination"
     *     marks the node as a named destination) and `Name`.
     *   - `LineString` features become [Edge]s, with endpoints snapped to the closest
     *     [Node] within [SNAP_TOLERANCE_METERS] (gradual to 5m). Each LineString
     *     produces two `Edge` objects (bidirectional storage).
     */
    private fun loadGraphFromJsonString(jsonString: String) {
        try {
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
            FileLogger.e("GraphTest", "ERROR: ${e.message}")
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
    // PHANTOM NODE / VIRTUAL START ROUTING
    // ============================================================
    //
    // Supports "navigate from my current GPS location" by projecting the user's GPS position
    // onto the nearest edge (or snapping to the nearest node), then seeding A* with partial-edge
    // initial costs. The phantom start point is never inserted into the graph — it lives only
    // inside the polyline returned to the caller. Graph data structures are never mutated, so
    // the algorithm is thread-safe and has zero impact on existing destination-to-destination
    // routing.
    //
    // See plan_scrum_56_phase_2.md for the full design.
    // ============================================================

    /**
     * Describes a phantom (virtual) start: a point projected onto an edge, plus the data needed
     * to seed A* with partial-edge initial costs and to slice the edge's geometry during path
     * reconstruction.
     *
     * Invariant (by construction in [findNearestEdge]): `splitGeometry[0]` sits at
     * `nodes[edgeNodeAId]`'s position and `splitGeometry.last()` sits at `nodes[edgeNodeBId]`'s
     * position. This holds because edges are stored with the owner node's coordinates at index 0
     * in the graph loader, and we only scan edges where `ownerId < targetId` — which de-duplicates
     * the two halves of the bidirectional storage while preserving the "geometry starts at owner"
     * invariant.
     */
    private data class PhantomStart(
        val edgeNodeAId: Int,
        val edgeNodeBId: Int,
        val edgeLength: Double,
        val splitGeometry: List<Coordinate>,
        val segmentIndex: Int,
        val tOnSegment: Double,
        val tOnPolyline: Double,
        val projectedLat: Double,
        val projectedLng: Double,
        val distanceMeters: Double
    )

    /**
     * Route from an arbitrary GPS point to a named destination node.
     *
     * Decision tree:
     * 1. Reject if `accuracy` is worse than [GeoProjection.MAX_ACCURACY_M].
     * 2. If the user is within [GeoProjection.rNode] of any graph node, snap to that node and
     *    delegate to the existing [findPath] — no phantom needed.
     * 3. Otherwise project onto the nearest edge. Refuse if the projection is beyond
     *    [GeoProjection.R_REFUSE_M]; flag it as off-network if beyond [GeoProjection.rSnap].
     * 4. Run multi-source A* seeded with both edge endpoints' partial-edge costs, then
     *    reconstruct the polyline with the projection prepended and the split edge's
     *    sub-geometry sliced between projection and the winning root.
     *
     * @return a [PhantomRouteResult]; [PhantomRouteResult.Success] carries the polyline, node
     *         path, snap distance, and classification.
     */
    fun findPathFromPoint(
        userLat: Double,
        userLng: Double,
        accuracy: Float,
        targetNodeId: Int
    ): PhantomRouteResult {
        // 1. Accuracy gate.
        if (accuracy > GeoProjection.MAX_ACCURACY_M) {
            return PhantomRouteResult.AccuracyTooLow(accuracy)
        }

        // Target must exist.
        nodes[targetNodeId] ?: return PhantomRouteResult.NoPath

        // 2. AT-node detection (D5): O(N) scan over ALL nodes (not just destinations).
        var nearestNode: Node? = null
        var nearestNodeDist = Double.POSITIVE_INFINITY
        for (n in nodes.values) {
            val d = haversine(n.lat, n.lng, userLat, userLng)
            if (d < nearestNodeDist) {
                nearestNodeDist = d
                nearestNode = n
            }
        }
        if (nearestNode == null) return PhantomRouteResult.NoPath

        // 3. AT-node short-circuit — delegate to existing findPath.
        if (nearestNodeDist <= GeoProjection.rNode(accuracy)) {
            val polyline = findPath(nearestNode.id, targetNodeId)
            val nodePath = findNodePath(nearestNode.id, targetNodeId)
            if (polyline.isEmpty() || nodePath.isEmpty()) return PhantomRouteResult.NoPath
            return PhantomRouteResult.Success(
                polyline = polyline,
                nodePath = nodePath,
                snapDistance = nearestNodeDist,
                isOffNetwork = false,
                startMode = PhantomStartMode.AT_NODE
            )
        }

        // 4. Find the nearest edge.
        val phantom = findNearestEdge(userLat, userLng)
            ?: return PhantomRouteResult.NoPath

        // 5. Refuse if beyond the hard cap.
        if (phantom.distanceMeters > GeoProjection.R_REFUSE_M) {
            return PhantomRouteResult.TooFar
        }

        // 6. Run phantom A*.
        val aStarResult = runAStarFromProjection(phantom, targetNodeId)
            ?: return PhantomRouteResult.NoPath
        val (cameFromNode, cameFromEdge) = aStarResult

        // 7. Reconstruct the polyline + node path.
        val (polyline, nodePath) = reconstructPathFromProjection(
            phantom, targetNodeId, cameFromNode, cameFromEdge
        )
        if (polyline.isEmpty() || nodePath.isEmpty()) return PhantomRouteResult.NoPath

        // 8. Classify ON_EDGE vs OFF_NETWORK based on snap distance.
        val offNetwork = phantom.distanceMeters > GeoProjection.rSnap(accuracy)
        val mode = if (offNetwork) PhantomStartMode.OFF_NETWORK else PhantomStartMode.ON_EDGE

        return PhantomRouteResult.Success(
            polyline = polyline,
            nodePath = nodePath,
            snapDistance = phantom.distanceMeters,
            isOffNetwork = offNetwork,
            startMode = mode
        )
    }

    /**
     * Scan every physical edge (with bidirectional de-duplication) and return the one whose
     * polyline is closest to (lat, lng). Returns null only when the graph has no edges at all.
     *
     * Dedup rationale: each physical path is stored as two [Edge] objects — one on each endpoint's
     * `neighbors` list, with the second holding reversed geometry. Accepting only edges where
     * `ownerId < targetId` visits each physical path exactly once while preserving the
     * "geometry starts at owner" invariant relied on by [PhantomStart].
     */
    private fun findNearestEdge(lat: Double, lng: Double): PhantomStart? {
        var best: PhantomStart? = null

        for (currentNode in nodes.values) {
            for (edge in currentNode.neighbors) {
                // D6: bidirectional dedup.
                if (currentNode.id >= edge.targetNodeId) continue

                val projection = GeoProjection.projectPointOnPolyline(
                    pLat = lat, pLng = lng,
                    polyline = edge.pathGeometry
                ) ?: continue

                if (best == null || projection.distanceMeters < best.distanceMeters) {
                    best = PhantomStart(
                        edgeNodeAId = currentNode.id,
                        edgeNodeBId = edge.targetNodeId,
                        edgeLength = edge.distance,
                        splitGeometry = edge.pathGeometry,
                        segmentIndex = projection.segmentIndex,
                        tOnSegment = projection.tOnSegment,
                        tOnPolyline = projection.tOnPolyline,
                        projectedLat = projection.projectedLat,
                        projectedLng = projection.projectedLng,
                        distanceMeters = projection.distanceMeters
                    )
                }
            }
        }
        return best
    }

    /**
     * A* variant whose start is a phantom point on an edge between two real nodes
     * (A = [PhantomStart.edgeNodeAId], B = [PhantomStart.edgeNodeBId]).
     *
     * Multi-source initialization:
     * - `gScore[A] = tOnPolyline * edgeLength`         (metres from A to projection)
     * - `gScore[B] = (1 - tOnPolyline) * edgeLength`   (metres from projection to B)
     * - both endpoints are pushed onto the open set with their respective `f = g + h`.
     *
     * The split edge A↔B is skipped during neighbor expansion (D12): its traversal cost is
     * already encoded in the initial seeds, so traversing it again would double-count. Neither
     * root has a `cameFromNode` / `cameFromEdge` entry — they are the reconstruction roots.
     *
     * Mirrors the structure of [runAStar] (stale-entry check, closed-set, etc.) but is kept as a
     * separate method so [runAStar] stays untouched.
     */
    private fun runAStarFromProjection(
        phantom: PhantomStart,
        targetNodeId: Int
    ): Pair<Map<Int, Int>, Map<Int, Edge>>? {
        val target = nodes[targetNodeId] ?: return null
        val nodeA = nodes[phantom.edgeNodeAId] ?: return null
        val nodeB = nodes[phantom.edgeNodeBId] ?: return null

        val openSet = PriorityQueue<PathNode>()
        val gScore = HashMap<Int, Double>()
        val cameFromNode = HashMap<Int, Int>()
        val cameFromEdge = HashMap<Int, Edge>()
        val closedSet = HashSet<Int>()

        // Multi-source seeding (partial-edge costs).
        val gA = phantom.tOnPolyline * phantom.edgeLength
        val gB = (1.0 - phantom.tOnPolyline) * phantom.edgeLength
        gScore[phantom.edgeNodeAId] = gA
        gScore[phantom.edgeNodeBId] = gB

        val hA = haversine(nodeA.lat, nodeA.lng, target.lat, target.lng)
        val hB = haversine(nodeB.lat, nodeB.lng, target.lat, target.lng)
        openSet.add(PathNode(phantom.edgeNodeAId, gA + hA))
        openSet.add(PathNode(phantom.edgeNodeBId, gB + hB))

        while (openSet.isNotEmpty()) {
            val current = openSet.poll()
            val currentId = current.id

            // Stale-entry check — mirrors the pattern in runAStar.
            val currentG = gScore[currentId] ?: Double.MAX_VALUE
            val currentNode = nodes[currentId] ?: continue
            val bestPossibleF = currentG +
                haversine(currentNode.lat, currentNode.lng, target.lat, target.lng)
            if (current.fScore > bestPossibleF + 1e-6) continue

            if (currentId == targetNodeId) return Pair(cameFromNode, cameFromEdge)

            if (currentId in closedSet) continue
            closedSet.add(currentId)

            for (edge in currentNode.neighbors) {
                val neighborId = edge.targetNodeId

                // D12: skip the split edge — its cost is encoded in the phantom seeds.
                if ((currentId == phantom.edgeNodeAId && neighborId == phantom.edgeNodeBId) ||
                    (currentId == phantom.edgeNodeBId && neighborId == phantom.edgeNodeAId)
                ) continue

                if (neighborId in closedSet) continue

                val tentativeG = currentG + edge.distance
                val oldG = gScore.getOrDefault(neighborId, Double.MAX_VALUE)
                if (tentativeG < oldG) {
                    cameFromNode[neighborId] = currentId
                    cameFromEdge[neighborId] = edge
                    gScore[neighborId] = tentativeG
                    val neighborNode = nodes[neighborId] ?: continue
                    val h = haversine(neighborNode.lat, neighborNode.lng, target.lat, target.lng)
                    openSet.add(PathNode(neighborId, tentativeG + h))
                }
            }
        }
        return null
    }

    /**
     * Reconstruct the polyline and node chain for a phantom route.
     *
     * The polyline is assembled as:
     * 1. `[projection point]`
     * 2. partial-edge sub-geometry from the projection to whichever root (A or B) the walk-back
     *    terminates at
     * 3. the full geometry of each edge A* traversed from the winning root to the target
     *
     * Boundary de-duplication (D17) is applied at every append via [appendWithDedup] so that a
     * projection landing on a polyline vertex (`tOnSegment` ≈ 0 or ≈ 1) does not introduce a
     * duplicate coordinate.
     *
     * The returned `nodePath` starts at the winning root (a real node), not at the phantom —
     * satisfying D7 so SphereRefresher does not render a milestone at the phantom position.
     */
    private fun reconstructPathFromProjection(
        phantom: PhantomStart,
        targetNodeId: Int,
        cameFromNode: Map<Int, Int>,
        cameFromEdge: Map<Int, Edge>
    ): Pair<List<Coordinate>, List<Node>> {
        // Walk back from target until we hit a phantom root.
        val nodeChain = mutableListOf<Int>()
        var curr = targetNodeId
        while (curr != phantom.edgeNodeAId && curr != phantom.edgeNodeBId) {
            nodeChain.add(0, curr)
            curr = cameFromNode[curr] ?: return Pair(emptyList(), emptyList())
        }
        val winningRoot = curr
        nodeChain.add(0, winningRoot)

        // Build polyline.
        val polyline = mutableListOf<Coordinate>()
        polyline.add(Coordinate(phantom.projectedLat, phantom.projectedLng))

        // Partial-edge slice from projection to winning root.
        val splitSlice: List<Coordinate> = if (winningRoot == phantom.edgeNodeAId) {
            // A lies at splitGeometry[0]; walking projection → A means reversing segments
            // [0..segmentIndex], which yields [Pi, Pi-1, ..., P0].
            phantom.splitGeometry.subList(0, phantom.segmentIndex + 1).reversed()
        } else {
            // B lies at splitGeometry.last(); walking projection → B means segments
            // [segmentIndex+1..end], which yields [Pi+1, Pi+2, ..., Pn].
            phantom.splitGeometry.subList(
                phantom.segmentIndex + 1,
                phantom.splitGeometry.size
            )
        }
        appendWithDedup(polyline, splitSlice)

        // Traverse the rest of the node chain, appending each edge's full geometry.
        for (i in 1 until nodeChain.size) {
            val childId = nodeChain[i]
            val edgeUsed = cameFromEdge[childId] ?: continue
            appendWithDedup(polyline, edgeUsed.pathGeometry)
        }

        val nodePath = nodeChain.mapNotNull { nodes[it] }
        return Pair(polyline, nodePath)
    }

    /**
     * Append each coordinate to `polyline`, skipping any that is [almostSame] as the current tail.
     * Ensures phantom reconstruction produces a clean, duplicate-free polyline at every segment
     * boundary.
     */
    private fun appendWithDedup(polyline: MutableList<Coordinate>, toAdd: List<Coordinate>) {
        for (coord in toAdd) {
            val last = polyline.lastOrNull()
            if (last == null || !almostSame(last, coord)) {
                polyline.add(coord)
            }
        }
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

        FileLogger.d(
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

    companion object {
        /**
         * Test-friendly factory: builds a [CampusGraph] from a GeoJSON string directly,
         * bypassing the need for an Android [Context]. Used primarily by JVM unit tests
         * to avoid Robolectric or instrumented test overhead.
         *
         * The graph health report (which the [Context]-based constructor logs after
         * loading) is intentionally skipped here — tests that want to verify counts
         * should read the public fields ([nodes], [destinations], counters) directly.
         *
         * @param json A complete GeoJSON FeatureCollection containing Point and
         *             LineString features matching the campus graph schema.
         * @return A fully-initialized CampusGraph instance.
         */
        fun fromGeoJsonString(json: String): CampusGraph {
            val graph = CampusGraph()
            graph.loadGraphFromJsonString(json)
            return graph
        }
    }
}

// ================================================================================================
// PHANTOM NODE / VIRTUAL START — PUBLIC RESULT TYPES
// ================================================================================================
//
// Returned by [CampusGraph.findPathFromPoint]. Consumed by ArNavigationActivity to dispatch UX
// feedback per outcome (success with off-network flag, too far, GPS accuracy too low, no path).

/**
 * How the phantom start was resolved.
 */
enum class PhantomStartMode {
    /** User was within [GeoProjection.rNode] of a real graph node; existing [CampusGraph.findPath] was used. */
    AT_NODE,

    /** User was on (or within [GeoProjection.rSnap] of) a path edge; projected onto the edge polyline. */
    ON_EDGE,

    /** User was beyond [GeoProjection.rSnap] but within [GeoProjection.R_REFUSE_M]; snapped with a warning. */
    OFF_NETWORK
}

/**
 * Result of [CampusGraph.findPathFromPoint]. Exhaustive — consumers should handle every variant.
 */
sealed class PhantomRouteResult {
    /** Successful route. [polyline] begins at the phantom (or AT_NODE snap point). */
    data class Success(
        val polyline: List<Coordinate>,
        val nodePath: List<Node>,
        val snapDistance: Double,
        val isOffNetwork: Boolean,
        val startMode: PhantomStartMode
    ) : PhantomRouteResult()

    /** User is beyond [GeoProjection.R_REFUSE_M] from any path — refuse to route. */
    object TooFar : PhantomRouteResult()

    /** A* returned no path (e.g., target in a disconnected component). */
    object NoPath : PhantomRouteResult()

    /** GPS accuracy exceeded [GeoProjection.MAX_ACCURACY_M] — refuse to route. */
    data class AccuracyTooLow(val accuracy: Float) : PhantomRouteResult()
}
