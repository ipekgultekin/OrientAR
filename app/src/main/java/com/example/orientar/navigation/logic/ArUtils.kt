package com.example.orientar.navigation.logic

import android.location.Location
import android.util.Log
import kotlin.math.*
import com.example.orientar.navigation.ar.ARPerformanceConfig


/**
 * ArUtils - Mathematical utilities for AR navigation coordinate transformations.
 *
 * ================================================================================================
 * OVERVIEW
 * ================================================================================================
 * This object provides functions for:
 * 1. GPS distance calculations (Haversine formula)
 * 2. Bearing/azimuth calculations between GPS coordinates
 * 3. GPS-to-AR coordinate transformation
 * 4. Path interpolation for smooth route rendering
 *
 * ================================================================================================
 * COORDINATE SYSTEMS
 * ================================================================================================
 *
 * GPS/REAL WORLD:
 * - Latitude: North-South position (-90° to +90°)
 * - Longitude: East-West position (-180° to +180°)
 * - Bearing: Direction from one point to another (0°=North, 90°=East, 180°=South, 270°=West)
 *
 * AR WORLD (SceneView/ARCore):
 * - X-axis: Right (+X = right, -X = left)
 * - Y-axis: Up (+Y = up, -Y = down)
 * - Z-axis: Forward (+Z = behind camera, -Z = in front of camera)
 * - Right-handed coordinate system
 *
 * ================================================================================================
 */
object ArUtils {
    private const val TAG = "ArUtils"

    // Earth's radius in meters (WGS84 mean radius)
    private const val EARTH_RADIUS_METERS = 6371000.0

    // ============================================================================
    // BUG-006 FIX: Use centralized constants from ARPerformanceConfig
    // ============================================================================
    // REMOVED: private const val MAX_AR_RENDER_DISTANCE = 500.0
    //
    // Now using:
    //   - ARPerformanceConfig.MAX_RENDER_DISTANCE (100m) for rendering cutoff
    //   - ARPerformanceConfig.MAX_AR_CALCULATION_DISTANCE (500m) for validation
    // ============================================================================

    // ============================================================================================
    // DISTANCE CALCULATIONS
    // ============================================================================================

    /**
     * Calculates the distance between two GPS coordinates using the Haversine formula.
     *
     * THE HAVERSINE FORMULA:
     * The Haversine formula calculates the great-circle distance between two points
     * on a sphere. It's the most accurate formula for short to medium distances on Earth.
     *
     * Formula:
     * a = sin²(Δlat/2) + cos(lat1) × cos(lat2) × sin²(Δlon/2)
     * c = 2 × atan2(√a, √(1-a))
     * d = R × c
     *
     * @param lat1 Latitude of first point (degrees)
     * @param lon1 Longitude of first point (degrees)
     * @param lat2 Latitude of second point (degrees)
     * @param lon2 Longitude of second point (degrees)
     * @return Distance in meters (Double precision for AR stability)
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Validate inputs
        if (lat1.isNaN() || lon1.isNaN() || lat2.isNaN() || lon2.isNaN()) {
            Log.w(TAG, "distanceMeters: Invalid coordinates (NaN)")
            return 0.0
        }

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    // ============================================================================================
    // BEARING CALCULATIONS
    // ============================================================================================

    /**
     * Calculates the initial bearing (azimuth) from a starting point to a target point.
     *
     * BEARING DEFINITION:
     * The bearing is the angle measured clockwise from True North.
     * - 0° = North
     * - 90° = East
     * - 180° = South
     * - 270° = West
     *
     * FORMULA:
     * θ = atan2(sin(Δlon) × cos(lat2), cos(lat1) × sin(lat2) - sin(lat1) × cos(lat2) × cos(Δlon))
     *
     * @param lat1 Latitude of starting point (degrees)
     * @param lon1 Longitude of starting point (degrees)
     * @param lat2 Latitude of target point (degrees)
     * @param lon2 Longitude of target point (degrees)
     * @return Bearing in degrees [0, 360), where 0 is North
     */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Validate inputs
        if (lat1.isNaN() || lon1.isNaN() || lat2.isNaN() || lon2.isNaN()) {
            Log.w(TAG, "bearingDeg: Invalid coordinates (NaN)")
            return 0.0
        }

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLonRad = Math.toRadians(lon2 - lon1)

        val y = sin(dLonRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(dLonRad)

        var bearing = Math.toDegrees(atan2(y, x))

        // Normalize to [0, 360)
        bearing = (bearing + 360.0) % 360.0

        return bearing
    }

    // ============================================================================================
    // ANGLE UTILITIES
    // ============================================================================================

    /**
     * Normalizes an angle to the range [-180, 180].
     *
     * This is essential for calculating the smallest angular difference between two angles.
     * For example:
     * - normalizeAngleDeg(350) = -10  (350° is the same as -10°)
     * - normalizeAngleDeg(-190) = 170 (-190° is the same as 170°)
     *
     * @param angle Angle in degrees (any value)
     * @return Normalized angle in degrees [-180, 180]
     */
    fun normalizeAngleDeg(angle: Double): Double {
        if (angle.isNaN() || angle.isInfinite()) {
            Log.w(TAG, "normalizeAngleDeg: Invalid angle (NaN or Infinite)")
            return 0.0
        }

        var a = (angle + 180.0) % 360.0
        if (a < 0) a += 360.0
        return a - 180.0
    }

    // ============================================================================================
    // PATH INTERPOLATION
    // ============================================================================================

    /**
     * Generates intermediate points between two GPS coordinates to create a smooth path.
     *
     * PURPOSE:
     * Instead of just having spheres at waypoints, we create a "breadcrumb trail"
     * of smaller spheres between waypoints. This helps the user follow the path
     * more easily in AR.
     *
     * ALGORITHM:
     * Linear interpolation between start and end points:
     * point[i] = start + (end - start) × (i / (count + 1))
     *
     * @param startLat Starting latitude
     * @param startLng Starting longitude
     * @param endLat Ending latitude
     * @param endLng Ending longitude
     * @param stepMeters Distance between interpolated points (default: 1.5m)
     * @return List of (lat, lng) pairs for the interpolated points
     */
    fun interpolate(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        stepMeters: Double = 1.5
    ): List<Pair<Double, Double>> {
        // Validate step size
        if (stepMeters <= 0) {
            Log.w(TAG, "interpolate: Invalid step size ($stepMeters)")
            return emptyList()
        }

        val dist = distanceMeters(startLat, startLng, endLat, endLng)

        // If distance is too short for interpolation, return the start point
        // NOTE: Changed from returning empty list to returning start point
        // This ensures we don't lose any path segments
        if (dist < stepMeters) {
            return listOf(Pair(startLat, startLng))
        }

        // Calculate number of intermediate points
        val count = (dist / stepMeters).toInt()
        val points = ArrayList<Pair<Double, Double>>(count + 1)

        // Always include the start point
        points.add(Pair(startLat, startLng))

        // Add intermediate points
        for (i in 1..count) {
            val fraction = i.toDouble() / (count + 1)
            val lat = startLat + (endLat - startLat) * fraction
            val lng = startLng + (endLng - startLng) * fraction
            points.add(Pair(lat, lng))
        }

        return points
    }

    // ============================================================================================
    // GPS-TO-AR COORDINATE TRANSFORMATION
    // ============================================================================================

    /**
     * Converts a real-world GPS target into AR Scene coordinates (x, z).
     *
     * ================================================================================================
     * THE TRANSFORMATION PROBLEM
     * ================================================================================================
     *
     * We have:
     * 1. User's GPS position (anchor location)
     * 2. Target's GPS position (where we want to place a sphere)
     * 3. Yaw offset (difference between compass North and AR world forward)
     *
     * We need:
     * AR coordinates (x, z) where the sphere should be placed relative to the anchor
     *
     * ================================================================================================
     * THE SOLUTION
     * ================================================================================================
     *
     * Step 1: Calculate polar coordinates (distance, bearing) from user to target
     * Step 2: Adjust bearing by yaw offset to get AR-relative angle
     * Step 3: Convert polar (distance, angle) to Cartesian (x, z)
     *
     * ================================================================================================
     * COORDINATE SYSTEM MAPPING
     * ================================================================================================
     *
     *              Real World                    AR World
     *              (GPS/Compass)                 (SceneView)
     *
     *                 N (0°)                       -Z (Forward)
     *                   ↑                            ↑
     *                   │                            │
     *      W (270°) ←───┼───→ E (90°)  -X (Left) ←───┼───→ +X (Right)
     *                   │                            │
     *                   ↓                            ↓
     *                 S (180°)                      +Z (Behind)
     *
     * The yawOffset aligns these two coordinate systems:
     * - yawOffset = 0° means AR forward (-Z) = North
     * - yawOffset = 90° means AR forward (-Z) = East
     *
     * ================================================================================================
     * MATHEMATICAL FORMULA
     * ================================================================================================
     *
     * Given:
     * - bearingToTarget: GPS bearing from user to target [0°, 360°)
     * - yawOffset: Offset from AR forward to North
     *
     * AR angle = bearingToTarget - yawOffset
     * x = distance × sin(AR angle)    (positive = right)
     * z = -distance × cos(AR angle)   (negative = forward)
     *
     * Examples:
     * - Target at 0° (North), yawOffset = 0°: x = 0, z = -dist (directly forward)
     * - Target at 90° (East), yawOffset = 0°: x = +dist, z = 0 (directly right)
     * - Target at 0° (North), yawOffset = 90°: x = -dist, z = 0 (directly left)
     *
     * @param userLoc Current GPS location of the user (anchor position)
     * @param targetLat Latitude of the target point
     * @param targetLng Longitude of the target point
     * @param yawOffsetDeg The yaw offset for coordinate alignment
     * @return Pair(x, z) positions in AR local space (relative to anchor)
     */
    fun convertGpsToArPosition(
        userLoc: Location,
        targetLat: Double,
        targetLng: Double,
        yawOffsetDeg: Double
    ): Pair<Float, Float> {

        // ========================================================================
        // STEP 1: Calculate polar coordinates (distance and bearing)
        // ========================================================================
        val dist = distanceMeters(userLoc.latitude, userLoc.longitude, targetLat, targetLng)
        val bearingToTarget = bearingDeg(userLoc.latitude, userLoc.longitude, targetLat, targetLng)

        // Validate distance
        if (dist.isNaN() || dist.isInfinite()) {
            Log.w(TAG, "convertGpsToArPosition: Invalid distance calculated")
            return Pair(0f, 0f)
        }

        // ========================================================================
        // STEP 2: Use actual distance (no clamping to preserve geometry)
        // ========================================================================
        // Note: Callers should use convertGpsToArPositionOrNull() to skip far points
        // instead of rendering them at wrong positions
        val renderDist = dist

        // Log warning for very far points (callers should filter these)
        if (dist > ARPerformanceConfig.MAX_AR_CALCULATION_DISTANCE) {
            Log.w(TAG, "Warning: Rendering point at ${dist.toInt()}m (beyond ${ARPerformanceConfig.MAX_AR_CALCULATION_DISTANCE.toInt()}m)")
        }

        // ========================================================================
        // STEP 3: Convert GPS bearing to AR angle
        // ========================================================================
        // AR angle = GPS bearing - yaw offset
        // This rotates the bearing from "relative to North" to "relative to AR forward"
        val angleInAr = normalizeAngleDeg(bearingToTarget - yawOffsetDeg)
        val angleRad = Math.toRadians(angleInAr)

        // ========================================================================
        // STEP 4: Convert polar to Cartesian
        // ========================================================================
        // x = distance × sin(angle)  → positive = right
        // z = -distance × cos(angle) → negative = forward (into screen)
        //
        // When angleInAr = 0° (target is in AR forward direction):
        //   x = sin(0) × dist = 0
        //   z = -cos(0) × dist = -dist (forward in AR)
        //
        // When angleInAr = 90° (target is to the right in AR):
        //   x = sin(90) × dist = +dist (right in AR)
        //   z = -cos(90) × dist = 0
        val x = (renderDist * sin(angleRad)).toFloat()
        val z = (-renderDist * cos(angleRad)).toFloat()

        // Validate output
        if (x.isNaN() || z.isNaN()) {
            Log.e(TAG, "convertGpsToArPosition: Calculation resulted in NaN!")
            return Pair(0f, 0f)
        }

        return Pair(x, z)
    }

    /**
     * Converts GPS to AR position with distance validation.
     * Returns null if the point is too far to render (should be skipped).
     *
     * @return Pair(x, z) or null if point should not be rendered
     */
    fun convertGpsToArPositionOrNull(
        userLoc: Location,
        targetLat: Double,
        targetLng: Double,
        yawOffsetDeg: Double,
        maxDistance: Double = ARPerformanceConfig.MAX_RENDER_DISTANCE.toDouble()
    ): Pair<Float, Float>? {
        val dist = distanceMeters(userLoc.latitude, userLoc.longitude, targetLat, targetLng)

        // Skip points that are too far (don't clamp - skip entirely)
        if (dist > maxDistance) {
            return null
        }

        return convertGpsToArPosition(userLoc, targetLat, targetLng, yawOffsetDeg)
    }

    // ============================================================================================
    // DEBUGGING UTILITIES
    // ============================================================================================

    /**
     * Formats a GPS-to-AR conversion for logging.
     * Useful for debugging coordinate transformation issues.
     */
    fun formatConversionDebug(
        userLoc: Location,
        targetLat: Double,
        targetLng: Double,
        yawOffsetDeg: Double,
        result: Pair<Float, Float>
    ): String {
        val dist = distanceMeters(userLoc.latitude, userLoc.longitude, targetLat, targetLng)
        val bearing = bearingDeg(userLoc.latitude, userLoc.longitude, targetLat, targetLng)
        val arAngle = normalizeAngleDeg(bearing - yawOffsetDeg)

        return """
            GPS→AR Conversion:
              User: (${userLoc.latitude}, ${userLoc.longitude})
              Target: ($targetLat, $targetLng)
              Distance: ${"%.1f".format(dist)}m
              Bearing: ${"%.1f".format(bearing)}°
              YawOffset: ${"%.1f".format(yawOffsetDeg)}°
              AR Angle: ${"%.1f".format(arAngle)}°
              Result: (x=${"%.2f".format(result.first)}, z=${"%.2f".format(result.second)})
        """.trimIndent()
    }
}
