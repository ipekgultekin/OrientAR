package com.example.orientar.navigation.logic

import android.location.Location
import kotlin.math.*
import com.example.orientar.navigation.util.FileLogger


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

    /** Maximum reasonable distance for AR calculations (meters) — for validation */
    private const val MAX_AR_CALCULATION_DISTANCE = 500.0f
    /** Maximum distance to render spheres from anchor (meters) — practical AR visibility limit */
    private const val MAX_RENDER_DISTANCE = 100.0f

    // ============================================================================================
    // DISTANCE CALCULATIONS
    // ============================================================================================

    /**
     * Haversine distance between two GPS coordinates.
     * @return Distance in meters
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Validate inputs
        if (lat1.isNaN() || lon1.isNaN() || lat2.isNaN() || lon2.isNaN()) {
            FileLogger.w(TAG, "distanceMeters: NaN coordinates")
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
     * Initial bearing (azimuth) from start to target point.
     * @return Bearing in degrees [0, 360), where 0° = North
     */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Validate inputs
        if (lat1.isNaN() || lon1.isNaN() || lat2.isNaN() || lon2.isNaN()) {
            FileLogger.w(TAG, "bearingDeg: NaN coordinates")
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
            FileLogger.w(TAG, "normalizeAngleDeg: invalid angle (NaN/Inf)")
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
     * Generates evenly-spaced intermediate GPS points between two coordinates.
     * Creates a "breadcrumb trail" of points for smooth AR path rendering.
     *
     * @param stepMeters Distance between interpolated points (default: 1.5m)
     * @return List of (lat, lng) pairs including start point
     */
    fun interpolate(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        stepMeters: Double = 1.5
    ): List<Pair<Double, Double>> {
        // Validate step size
        if (stepMeters <= 0) {
            FileLogger.w(TAG, "interpolate: invalid step size ($stepMeters)")
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
     * Converts a GPS target into AR scene coordinates (x, z) relative to anchor.
     *
     * Coordinate mapping:
     *   GPS: N=0°, E=90°    →    AR: -Z=forward, +X=right
     *
     * Formula: arAngle = bearing - yawOffset
     *   x = distance × sin(arAngle), z = -distance × cos(arAngle)
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

        if (dist.isNaN() || dist.isInfinite()) {
            FileLogger.w(TAG, "convertGpsToArPosition: invalid distance")
            return Pair(0f, 0f)
        }

        // No clamping — callers should use convertGpsToArPositionOrNull() for filtering
        val renderDist = dist

        if (dist > MAX_AR_CALCULATION_DISTANCE) {
            FileLogger.w(TAG, "Point at ${dist.toInt()}m exceeds max calculation distance")
        }

        // Convert GPS bearing to AR-relative angle, then polar → cartesian
        val angleInAr = normalizeAngleDeg(bearingToTarget - yawOffsetDeg)
        val angleRad = Math.toRadians(angleInAr)
        val x = (renderDist * sin(angleRad)).toFloat()
        val z = (-renderDist * cos(angleRad)).toFloat()

        if (x.isNaN() || z.isNaN()) {
            FileLogger.e(TAG, "convertGpsToArPosition: result is NaN!")
            return Pair(0f, 0f)
        }

        return Pair(x, z)
    }

    /** Like [convertGpsToArPosition] but returns null if beyond [maxDistance]. */
    fun convertGpsToArPositionOrNull(
        userLoc: Location,
        targetLat: Double,
        targetLng: Double,
        yawOffsetDeg: Double,
        maxDistance: Double = MAX_RENDER_DISTANCE.toDouble()
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
