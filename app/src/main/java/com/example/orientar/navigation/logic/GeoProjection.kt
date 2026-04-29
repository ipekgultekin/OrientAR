package com.example.orientar.navigation.logic

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Result of projecting a point onto a segment or polyline.
 *
 * - [projectedLat], [projectedLng]: the snap point on the line.
 * - [distanceMeters]: haversine distance from the input point to the snap point.
 * - [segmentIndex]: index `i` of the winning segment (between points `Pi` and `Pi+1`) within the
 *   polyline. For single-segment input, always `0`.
 * - [tOnSegment]: fractional position in `[0, 1]` within the winning segment — for slicing the
 *   polyline's geometry at the projection.
 * - [tOnPolyline]: fractional position in `[0, 1]` along the entire polyline by cumulative
 *   haversine distance — for seeding A* partial-edge costs. For single-segment input, equals
 *   [tOnSegment].
 */
data class ProjectionResult(
    val projectedLat: Double,
    val projectedLng: Double,
    val distanceMeters: Double,
    val segmentIndex: Int,
    val tOnSegment: Double,
    val tOnPolyline: Double
)

/**
 * Point-to-line-segment / point-to-polyline projection utilities plus adaptive phantom-node
 * tolerances. Pure math; no Android dependencies.
 *
 * Projection uses a local equirectangular approximation anchored at each segment's midpoint.
 * At campus scale (~1 km extents, latitude ~35° N) this introduces sub-meter error — well
 * below GPS accuracy — while being much simpler than full great-circle cross-track math.
 */
object GeoProjection {

    // ============================================================
    // THRESHOLDS
    // ============================================================

    /** "AT node" base radius in meters (applied when GPS accuracy is excellent). */
    const val R_NODE_BASE_M = 5.0

    /** Accuracy multiplier for "AT node" radius — scales with GPS uncertainty. */
    const val R_NODE_ACCURACY_K = 1.5

    /** Base snap tolerance in meters (applied when GPS accuracy is excellent). */
    const val R_SNAP_BASE_M = 30.0

    /** Accuracy multiplier for snap tolerance. */
    const val R_SNAP_ACCURACY_K = 2.5

    /** Hard cap on snap tolerance regardless of accuracy. */
    const val R_SNAP_CAP_M = 75.0

    /** Beyond this, refuse to route (user too far from any path). */
    const val R_REFUSE_M = 75.0

    /** Mid tier: snap but flag the start as off-network (user must walk to path). */
    const val R_OFFNET_M = 50.0

    /** Refuse chip tap if GPS accuracy is worse than this (meters). */
    const val MAX_ACCURACY_M = 30.0f

    // ============================================================
    // INTERNAL CONSTANTS
    // ============================================================

    private const val EARTH_R_METERS = 6_371_000.0

    /** Squared-length guard against zero-length segments. */
    private const val ZERO_LEN_SQ = 1e-18

    // ============================================================
    // ADAPTIVE TOLERANCE FUNCTIONS
    // ============================================================

    /**
     * Radius within which a user is considered "at" an existing graph node.
     * Grows with GPS uncertainty so that coarse fixes don't falsely miss a node.
     */
    fun rNode(accuracy: Float): Double =
        max(R_NODE_BASE_M, R_NODE_ACCURACY_K * accuracy)

    /**
     * Radius within which we'll snap to the nearest edge without treating the start as
     * off-network. Capped by [R_SNAP_CAP_M] so pathological accuracy readings don't disable
     * the refuse gate.
     */
    fun rSnap(accuracy: Float): Double =
        min(max(R_SNAP_BASE_M, R_SNAP_ACCURACY_K * accuracy), R_SNAP_CAP_M)

    // ============================================================
    // PROJECTION
    // ============================================================

    /**
     * Project point P onto a single straight segment from A to B.
     *
     * Algorithm:
     * 1. Convert A, B, and P to local planar XY meters using an equirectangular projection
     *    anchored at the segment midpoint.
     * 2. Compute the scalar projection of AP onto AB, clamped to `[0, 1]`.
     * 3. Linearly interpolate in lat/lng to produce the snap point (safe at ~1 km scale —
     *    indistinguishable from geodesic interpolation).
     * 4. Return haversine distance from P to the snap point.
     *
     * For a zero-length segment (A ≈ B), returns A as the snap point with `t = 0`.
     *
     * Result uses `segmentIndex = 0` and `tOnSegment == tOnPolyline` because a single segment
     * is trivially its own polyline.
     */
    fun projectPointOnSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): ProjectionResult {
        val refLat = (aLat + bLat) / 2.0
        val cosRef = cos(Math.toRadians(refLat))

        // Convert lat/lng to local planar XY meters anchored at midpoint latitude.
        val aX = Math.toRadians(aLng) * EARTH_R_METERS * cosRef
        val aY = Math.toRadians(aLat) * EARTH_R_METERS
        val bX = Math.toRadians(bLng) * EARTH_R_METERS * cosRef
        val bY = Math.toRadians(bLat) * EARTH_R_METERS
        val pX = Math.toRadians(pLng) * EARTH_R_METERS * cosRef
        val pY = Math.toRadians(pLat) * EARTH_R_METERS

        val dx = bX - aX
        val dy = bY - aY
        val lenSq = dx * dx + dy * dy

        if (lenSq < ZERO_LEN_SQ) {
            // Zero-length segment — snap to A.
            return ProjectionResult(
                projectedLat = aLat,
                projectedLng = aLng,
                distanceMeters = haversine(pLat, pLng, aLat, aLng),
                segmentIndex = 0,
                tOnSegment = 0.0,
                tOnPolyline = 0.0
            )
        }

        val tRaw = ((pX - aX) * dx + (pY - aY) * dy) / lenSq
        val t = tRaw.coerceIn(0.0, 1.0)

        // Linear interpolation in lat/lng space — acceptable at ~1 km scale.
        val snapLat = aLat + t * (bLat - aLat)
        val snapLng = aLng + t * (bLng - aLng)

        val distance = haversine(pLat, pLng, snapLat, snapLng)

        return ProjectionResult(
            projectedLat = snapLat,
            projectedLng = snapLng,
            distanceMeters = distance,
            segmentIndex = 0,
            tOnSegment = t,
            tOnPolyline = t
        )
    }

    /**
     * Project point P onto a multi-segment polyline. Iterates segments, picks the one with the
     * minimum perpendicular distance.
     *
     * Returns `null` if the polyline has fewer than 2 points.
     *
     * For a degenerate polyline where all points coincide (total length = 0), returns the first
     * point as the snap with `segmentIndex = 0`, `tOnSegment = 0`, `tOnPolyline = 0` to avoid
     * division by zero.
     *
     * - [ProjectionResult.segmentIndex] and [ProjectionResult.tOnSegment] locate the projection
     *   on the specific winning segment — used to slice the polyline's geometry for phantom-node
     *   reconstruction.
     * - [ProjectionResult.tOnPolyline] is the fractional position along the entire polyline by
     *   cumulative haversine distance — used to seed A* partial-edge costs
     *   (`gScore[B] = tOnPolyline * edge.distance`).
     */
    fun projectPointOnPolyline(
        pLat: Double, pLng: Double,
        polyline: List<Coordinate>
    ): ProjectionResult? {
        if (polyline.size < 2) return null

        // Precompute cumulative haversine distances along the polyline.
        val cumulativeDists = DoubleArray(polyline.size)
        cumulativeDists[0] = 0.0
        for (i in 1 until polyline.size) {
            cumulativeDists[i] = cumulativeDists[i - 1] +
                haversine(
                    polyline[i - 1].lat, polyline[i - 1].lng,
                    polyline[i].lat, polyline[i].lng
                )
        }
        val totalLength = cumulativeDists[polyline.size - 1]

        // Degenerate polyline (all points coincide) — avoid division by zero.
        if (totalLength == 0.0) {
            return ProjectionResult(
                projectedLat = polyline[0].lat,
                projectedLng = polyline[0].lng,
                distanceMeters = haversine(pLat, pLng, polyline[0].lat, polyline[0].lng),
                segmentIndex = 0,
                tOnSegment = 0.0,
                tOnPolyline = 0.0
            )
        }

        // Iterate segments, find minimum distance.
        var bestSegIndex = 0
        var bestSegResult: ProjectionResult = projectPointOnSegment(
            pLat, pLng,
            polyline[0].lat, polyline[0].lng,
            polyline[1].lat, polyline[1].lng
        )
        for (i in 1 until polyline.size - 1) {
            val segResult = projectPointOnSegment(
                pLat, pLng,
                polyline[i].lat, polyline[i].lng,
                polyline[i + 1].lat, polyline[i + 1].lng
            )
            if (segResult.distanceMeters < bestSegResult.distanceMeters) {
                bestSegResult = segResult
                bestSegIndex = i
            }
        }

        // Convert segment-local t to polyline-global t via cumulative distances.
        val segmentLen = cumulativeDists[bestSegIndex + 1] - cumulativeDists[bestSegIndex]
        val tOnPolyline =
            (cumulativeDists[bestSegIndex] + bestSegResult.tOnSegment * segmentLen) / totalLength

        return ProjectionResult(
            projectedLat = bestSegResult.projectedLat,
            projectedLng = bestSegResult.projectedLng,
            distanceMeters = bestSegResult.distanceMeters,
            segmentIndex = bestSegIndex,
            tOnSegment = bestSegResult.tOnSegment,
            tOnPolyline = tOnPolyline
        )
    }

    // ============================================================
    // INTERNAL HELPERS
    // ============================================================

    /**
     * Haversine distance in meters between two (lat, lng) points. Private copy — [CampusGraph]
     * has a private haversine of its own, so reuse is not possible.
     */
    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * Math.sin(dLambda / 2).let { it * it }
        return 2 * EARTH_R_METERS * kotlin.math.asin(min(1.0, sqrt(a)))
    }
}
