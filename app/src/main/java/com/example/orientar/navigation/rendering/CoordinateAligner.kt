package com.example.orientar.navigation.rendering

import android.location.Location
import android.util.Log
import com.example.orientar.navigation.logic.ArUtils
import kotlin.math.abs
import kotlin.math.atan2
import com.example.orientar.navigation.util.FileLogger
/**
 * CoordinateAligner - Manages alignment between Real World and AR World coordinate systems.
 *
 * ================================================================================================
 * COORDINATE SYSTEM DOCUMENTATION
 * ================================================================================================
 *
 * REAL WORLD (GPS/Compass):
 * - North = 0°, East = 90°, South = 180°, West = 270°
 * - Compass bearing: Direction device is facing relative to True North
 * - GPS bearing: Direction of travel (only valid when moving)
 *
 * AR WORLD (SceneView/ARCore):
 * - Coordinate system is arbitrary - defined by device orientation when AR session starts
 * - +X = Right, +Y = Up, -Z = Forward (standard OpenGL right-handed coordinate system)
 * - Camera "yaw" is the rotation around the Y-axis (horizontal plane rotation)
 *
 * THE ALIGNMENT PROBLEM:
 * When the AR session starts, the device might be facing any direction. ARCore sets -Z
 * as "forward" at that moment. But "forward" in the real world depends on compass bearing.
 *
 * Example:
 * - User starts AR facing East (compass = 90°)
 * - AR camera yaw starts at 0° (facing -Z in AR world)
 * - If we want to place something North of the user, we need to translate:
 *   - North (0° compass) should be at yawOffset = 90° - 0° = 90° rotation from AR forward
 *
 * THE SOLUTION:
 * yawOffset = compassBearing - arCameraYaw
 *
 * To convert GPS bearing to AR angle:
 * arAngle = gpsBearing - yawOffset
 *
 * ================================================================================================
 */
class CoordinateAligner {
    companion object {
        private const val TAG = "CoordinateAligner"

        // Minimum speed required for motion-based alignment updates
        // Too low: GPS bearing is unreliable when moving slowly (noise dominates)
        // Too high: Updates rarely happen
        // 1.0 m/s = 3.6 km/h = slow walking pace
        private const val MIN_SPEED_FOR_ALIGNMENT = 1.0f

        // How much to adjust offset per update (0.0 = no change, 1.0 = full change)
        // Lower values = more stable but slower to correct drift
        // Higher values = faster correction but more jittery
        private const val ALIGNMENT_SMOOTHING_FACTOR = 0.15

        // Minimum time between alignment updates (prevents oscillation)
        private const val ALIGNMENT_UPDATE_COOLDOWN_MS = 2000L

        // Maximum allowed alignment error before logging warning
        private const val ALIGNMENT_WARNING_THRESHOLD_DEG = 30.0

        // Maximum allowed initial offset difference for sanity check
        private const val MAX_REASONABLE_OFFSET_CHANGE = 45.0

        // Drift correction settings
        private const val DRIFT_CORRECTION_INTERVAL_MS = 10000L  // Check every 10 seconds
        private const val MAX_DRIFT_CORRECTION_PER_UPDATE = 2.0  // Max 2° correction per update
        private const val MIN_TRAVEL_DISTANCE_FOR_CORRECTION = 10.0  // Need 10m of travel
    }

    // ============================================================================================
    // STATE VARIABLES
    // ============================================================================================

    // The offset angle between Real North and AR World forward direction
    // Formula: yawOffset = compassBearing - arCameraYaw
    // Usage: arAngle = gpsBearing - yawOffset
    private var yawOffsetDeg: Double = 0.0

    // Whether the coordinate system has been initialized
    private var isInitialized = false

    // Timestamp of last motion-based alignment update
    private var lastAlignmentUpdate: Long = 0

    // ============================================================================
    // BUG-007 FIX: History buffer with explicit size management
    // ============================================================================
    // PROBLEM: ArrayDeque(5) sets initial CAPACITY, not maximum size.
    //          Without explicit size management, buffer can grow unbounded.
    //
    // SOLUTION: Define MAX_HISTORY_SIZE and enforce it when adding elements.
    //           See the add operations in initialize() and updateWithMotion().
    // ============================================================================
    private val MAX_HISTORY_SIZE = 5
    private val offsetHistory = ArrayDeque<Double>(MAX_HISTORY_SIZE)

    // Initial compass and AR yaw values (for debugging)

    // Initial compass and AR yaw values (for debugging)
    private var initialCompassBearing: Double = 0.0
    private var initialArYaw: Double = 0.0

    // Drift correction tracking
    private var lastDriftCheckTime: Long = 0
    private var lastDriftCheckLocation: Location? = null
    private var accumulatedTravelDistance: Double = 0.0

    // ============================================================================================
    // INITIALIZATION
    // ============================================================================================

    /**
     * Initializes the yaw offset using the current compass bearing and AR camera orientation.
     * This "locks" the coordinate system so GPS coordinates can be accurately placed in AR.
     *
     * CRITICAL: This should be called when:
     * 1. The compass has been calibrated (accuracy is good)
     * 2. The AR session has stabilized (tracking is working)
     * 3. The user is standing still (reduces noise)
     *
     * @param compassBearing Current compass bearing (True North, in degrees 0-360)
     * @param arCameraYaw Current AR camera yaw angle (in degrees 0-360)
     */
    fun initialize(compassBearing: Double, arCameraYaw: Double) {
        if (compassBearing < 0 || compassBearing >= 360) { // Validation: Log warning if bearing looks suspicious
            Log.w(TAG, "WARNING: Compass bearing out of range [0, 360): $compassBearing")
        }
        if (isInitialized) {
            Log.w(TAG, "Already initialized. Use forceReinitialize() to reset.")
            return
        }

        // Store initial values for debugging
        initialCompassBearing = compassBearing
        initialArYaw = arCameraYaw

        // Calculate the difference between real north and AR world orientation
        // This offset will be used to convert all GPS bearings to AR angles
        yawOffsetDeg = ArUtils.normalizeAngleDeg(compassBearing - arCameraYaw)

        // Validate the calculated offset
        if (yawOffsetDeg.isNaN() || yawOffsetDeg.isInfinite()) {
            Log.e(TAG, "ERROR: Calculated invalid yaw offset! Compass=$compassBearing, ARYaw=$arCameraYaw")
            yawOffsetDeg = 0.0  // Fallback to 0
        }

        isInitialized = true

        // Initialize offset history for stability tracking
        offsetHistory.clear()
        offsetHistory.add(yawOffsetDeg)

        Log.d(TAG, "╔════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ COORDINATE SYSTEM INITIALIZED")
        Log.d(TAG, "╠════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ Compass Bearing (True North): ${compassBearing.toInt()}°")
        Log.d(TAG, "║ AR Camera Yaw:                ${arCameraYaw.toInt()}°")
        Log.d(TAG, "║ Calculated Yaw Offset:        ${yawOffsetDeg.toInt()}°")
        Log.d(TAG, "╠════════════════════════════════════════════════════════════")
        Log.d(TAG, "║ Interpretation:")
        Log.d(TAG, "║ - Objects at GPS bearing 0° (North) will appear at")
        Log.d(TAG, "║   AR angle ${(-yawOffsetDeg).toInt()}° relative to forward")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════")
        FileLogger.align("INITIALIZED: compass=${compassBearing.toInt()}°, arYaw=${arCameraYaw.toInt()}°, offset=${yawOffsetDeg.toInt()}°")
    }

    /**
     * Force re-initialization of the coordinate system.
     * Use this when:
     * - Drift is too severe (alignment error > 30°)
     * - Re-anchoring occurs
     * - User explicitly requests recalibration
     *
     * @param compassBearing Current compass bearing (True North, in degrees)
     * @param arCameraYaw Current AR camera yaw angle (in degrees)
     */
    fun forceReinitialize(compassBearing: Double, arCameraYaw: Double) {
        val oldOffset = yawOffsetDeg

        // Reset state
        isInitialized = false
        lastAlignmentUpdate = 0
        offsetHistory.clear()

        // Reinitialize
        initialize(compassBearing, arCameraYaw)

        // Log the change for debugging
        val offsetChange = ArUtils.normalizeAngleDeg(yawOffsetDeg - oldOffset)
        Log.d(TAG, "Force reinitialized: Offset changed by ${offsetChange.toInt()}° (was ${oldOffset.toInt()}°, now ${yawOffsetDeg.toInt()}°)")

        // Warn if the change is very large (might indicate a problem)
        if (abs(offsetChange) > MAX_REASONABLE_OFFSET_CHANGE) {
            Log.w(TAG, "⚠️ Large offset change detected! This might cause route position jump.")
        }
    }

    // ============================================================================================
    // MOTION-BASED ALIGNMENT UPDATES
    // ============================================================================================

    /**
     * Updates the yaw offset based on the user's walking direction.
     * This gradually corrects accumulated drift as the user moves.
     *
     * THEORY:
     * When walking, the GPS provides a "bearing" which is the direction of travel.
     * If the user is walking forward (looking where they're going), then:
     * - GPS bearing = actual direction of travel (real world)
     * - AR camera yaw = direction the camera is facing (AR world)
     * These SHOULD match (user is looking where they're going).
     * Any difference indicates drift that needs correction.
     *
     * ASSUMPTIONS:
     * 1. User is walking forward (not sideways or backwards)
     * 2. User is looking in the direction of travel
     * 3. GPS bearing is accurate (requires speed > 1 m/s)
     *
     * @param location Current GPS location (must have bearing and speed)
     * @param arCameraYaw Current AR camera yaw
     * @return true if alignment was updated, false otherwise
     */
    fun updateWithMotion(location: Location, arCameraYaw: Double): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "Cannot update: Not initialized")
            return false
        }

        // ========================================================================
        // VALIDATION: Ensure GPS bearing is reliable
        // ========================================================================

        // GPS bearing is only valid when the device has a bearing value
        if (!location.hasBearing()) {
            return false
        }

        // GPS bearing is unreliable at low speeds (dominated by noise)
        if (location.speed < MIN_SPEED_FOR_ALIGNMENT) {
            return false
        }

        // GPS bearing is less reliable with poor accuracy
        if (location.accuracy > 15.0f) {
            Log.d(TAG, "Skipping motion update: GPS accuracy too poor (${location.accuracy}m)")
            return false
        }

        // ========================================================================
        // RATE LIMITING: Don't update too frequently
        // ========================================================================
        val now = System.currentTimeMillis()
        if (now - lastAlignmentUpdate < ALIGNMENT_UPDATE_COOLDOWN_MS) {
            return false
        }

        // ========================================================================
        // CALCULATE CORRECTION
        // ========================================================================
        val gpsBearing = location.bearing.toDouble()

        // What the offset SHOULD be based on current motion:
        // If user is walking forward and looking ahead:
        //   gpsBearing = real world direction
        //   arCameraYaw = AR world direction the camera faces
        //   targetOffset = gpsBearing - arCameraYaw
        val targetOffset = ArUtils.normalizeAngleDeg(gpsBearing - arCameraYaw)

        // How far off is our current offset from the target?
        val diff = ArUtils.normalizeAngleDeg(targetOffset - yawOffsetDeg)

        // Skip tiny corrections (reduces jitter)
        if (abs(diff) < 2.0) {
            return false
        }

        // ========================================================================
        // APPLY SMOOTHED CORRECTION
        // ========================================================================
        val oldOffset = yawOffsetDeg

        // Apply smoothed correction (gradual adjustment to prevent sudden jumps)
        yawOffsetDeg += diff * ALIGNMENT_SMOOTHING_FACTOR
        yawOffsetDeg = ArUtils.normalizeAngleDeg(yawOffsetDeg)

        // Track in history for stability analysis
        // BUG-007 FIX: Use constant for size limit
        offsetHistory.add(yawOffsetDeg)
        while (offsetHistory.size > MAX_HISTORY_SIZE) {
            offsetHistory.removeFirst()
        }

        lastAlignmentUpdate = now

        // Log the update
        Log.d(TAG, "Motion alignment update:")
        Log.d(TAG, "  GPS Bearing: ${gpsBearing.toInt()}° @ ${"%.1f".format(location.speed)} m/s")
        Log.d(TAG, "  AR Yaw: ${arCameraYaw.toInt()}°")
        Log.d(TAG, "  Target Offset: ${targetOffset.toInt()}°")
        Log.d(TAG, "  Correction Applied: ${"%.1f".format(diff * ALIGNMENT_SMOOTHING_FACTOR)}° (raw diff: ${diff.toInt()}°)")
        Log.d(TAG, "  Offset: ${oldOffset.toInt()}° → ${yawOffsetDeg.toInt()}°")

        // Warn if correction is very large (might indicate serious drift)
        if (abs(diff) > ALIGNMENT_WARNING_THRESHOLD_DEG) {
            Log.w(TAG, "⚠️ Large alignment correction needed (${diff.toInt()}°) - possible significant drift")
        }

        return true
    }

    /**
     * Gradually correct drift based on GPS movement direction.
     * Call this periodically during navigation.
     *
     * @param currentLocation Current GPS location
     * @param gpsBearing GPS-derived bearing (direction of travel)
     * @param arForwardYaw Current AR camera forward direction
     * @return true if correction was applied
     */
    fun applyDriftCorrection(
        currentLocation: Location,
        gpsBearing: Float,
        arForwardYaw: Double
    ): Boolean {
        if (!isInitialized) return false

        val now = System.currentTimeMillis()

        // Check if enough time has passed
        if (now - lastDriftCheckTime < DRIFT_CORRECTION_INTERVAL_MS) {
            return false
        }

        // Check if we have a previous location
        val prevLocation = lastDriftCheckLocation
        if (prevLocation == null) {
            lastDriftCheckLocation = currentLocation
            lastDriftCheckTime = now
            return false
        }

        // Calculate travel distance
        val travelDistance = ArUtils.distanceMeters(
            prevLocation.latitude, prevLocation.longitude,
            currentLocation.latitude, currentLocation.longitude
        )

        accumulatedTravelDistance += travelDistance

        // Only correct if we've traveled enough distance
        if (accumulatedTravelDistance < MIN_TRAVEL_DISTANCE_FOR_CORRECTION) {
            lastDriftCheckLocation = currentLocation
            lastDriftCheckTime = now
            return false
        }

        // Calculate expected offset from GPS bearing
        val expectedOffset = ArUtils.normalizeAngleDeg(gpsBearing.toDouble() - arForwardYaw)
        val currentOffset = yawOffsetDeg

        // Calculate correction needed
        val correction = ArUtils.normalizeAngleDeg(expectedOffset - currentOffset)

        // Apply gradual correction (clamped)
        val clampedCorrection = correction.coerceIn(
            -MAX_DRIFT_CORRECTION_PER_UPDATE,
            MAX_DRIFT_CORRECTION_PER_UPDATE
        )

        if (abs(clampedCorrection) > 0.1) {
            yawOffsetDeg = ArUtils.normalizeAngleDeg(yawOffsetDeg + clampedCorrection)
            Log.d(TAG, "Drift correction applied: ${clampedCorrection.format(1)}° (total offset: ${yawOffsetDeg.format(1)}°)")
            FileLogger.align("Drift correction: ${String.format("%.1f", clampedCorrection)}° applied, total offset: ${String.format("%.1f", yawOffsetDeg)}°")
        }

        // Reset tracking
        lastDriftCheckLocation = currentLocation
        lastDriftCheckTime = now
        accumulatedTravelDistance = 0.0

        return abs(clampedCorrection) > 0.1
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)

    // ============================================================================================
    // GETTERS AND STATE QUERIES
    // ============================================================================================

    fun getYawOffset(): Double {
        return yawOffsetDeg
    }

    /**
     * Sets the yaw offset directly.
     * Use this for 180° flip corrections when behind-camera is detected.
     *
     * @param offset New yaw offset in degrees (0-360)
     */
    fun setYawOffset(offset: Double) {
        val normalizedOffset = ((offset % 360.0) + 360.0) % 360.0
        Log.d(TAG, "Yaw offset set directly: ${yawOffsetDeg}° → $normalizedOffset°")
        yawOffsetDeg = normalizedOffset
    }

    /**
     * Returns whether the aligner has been initialized.
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Calculates the AR camera yaw from a forward direction vector.
     *
     * COORDINATE SYSTEM:
     * In SceneView/ARCore's right-handed coordinate system:
     * - +X = Right
     * - +Y = Up
     * - -Z = Forward (into the screen)
     *
     * The forward vector (forwardX, forwardY, forwardZ) points in the direction
     * the camera is looking. We calculate the yaw (rotation around Y-axis) from this.
     *
     * FORMULA:
     * yaw = atan2(forwardX, -forwardZ)
     * - When looking forward (-Z): atan2(0, 1) = 0°
     * - When looking right (+X): atan2(1, 0) = 90°
     * - When looking back (+Z): atan2(0, -1) = 180°
     * - When looking left (-X): atan2(-1, 0) = -90° → normalized to 270°
     *
     * @param forwardX X component of the forward vector
     * @param forwardZ Z component of the forward vector
     * @return Yaw angle in degrees [0, 360)
     */
    fun calculateYawFromForward(forwardX: Float, forwardZ: Float): Double {
        // Validate inputs
        if (forwardX == 0f && forwardZ == 0f) {
            Log.w(TAG, "Forward vector is zero - cannot calculate yaw")
            return 0.0
        }

        // atan2(x, -z) gives angle from -Z axis (forward) to the forward vector
        // Result is in radians: [-π, π]
        val yawRad = atan2(forwardX.toDouble(), -forwardZ.toDouble())

        // Convert to degrees and normalize to [0, 360)
        return ((Math.toDegrees(yawRad) + 360.0) % 360.0)
    }

    /**
     * Validates the current alignment by comparing compass bearing with AR yaw.
     *
     * THEORY:
     * If alignment is perfect:
     *   compassBearing - arCameraYaw = yawOffset
     * Any deviation from this indicates drift or error.
     *
     * @param compassBearing Current compass bearing (True North, degrees)
     * @param arCameraYaw Current AR camera yaw (degrees)
     * @return Absolute alignment error in degrees (0 = perfect, 180 = opposite)
     */
    fun getAlignmentError(compassBearing: Double, arCameraYaw: Double): Double {
        if (!isInitialized) return Double.NaN

        // What the offset SHOULD be right now
        val expectedOffset = ArUtils.normalizeAngleDeg(compassBearing - arCameraYaw)

        // Difference between expected and actual offset
        val error = ArUtils.normalizeAngleDeg(expectedOffset - yawOffsetDeg)

        return abs(error)
    }

    /**
     * Checks if the alignment has significant drift that needs correction.
     *
     * @param compassBearing Current compass bearing
     * @param arCameraYaw Current AR camera yaw
     * @return true if alignment error exceeds warning threshold
     */
    fun hasSignificantDrift(compassBearing: Double, arCameraYaw: Double): Boolean {
        val error = getAlignmentError(compassBearing, arCameraYaw)
        return !error.isNaN() && error > ALIGNMENT_WARNING_THRESHOLD_DEG
    }

    // ============================================================================================
    // RESET AND CLEANUP
    // ============================================================================================

    /**
     * Resets the aligner to uninitialized state.
     * Call this before starting a new navigation session.
     */
    fun reset() {
        yawOffsetDeg = 0.0
        isInitialized = false
        lastAlignmentUpdate = 0
        initialCompassBearing = 0.0
        initialArYaw = 0.0
        offsetHistory.clear()

        Log.d(TAG, "Coordinate aligner reset to initial state")
    }

    // ============================================================================================
    // DIAGNOSTICS
    // ============================================================================================

    /**
     * Returns detailed diagnostic information for debugging.
     */
    fun getDiagnostics(): String {
        val stabilityInfo = if (offsetHistory.size >= 2) {
            val min = offsetHistory.minOrNull() ?: 0.0
            val max = offsetHistory.maxOrNull() ?: 0.0
            val range = abs(max - min)
            "Stability: ${if (range < 10) "Stable" else "Unstable"} (range: ${"%.1f".format(range)}°)"
        } else {
            "Stability: Insufficient data"
        }

        return """
            |╔═══════════════════════════════════════
            |║ COORDINATE ALIGNER DIAGNOSTICS
            |╠═══════════════════════════════════════
            |║ Initialized: $isInitialized
            |║ Current Offset: ${"%.1f".format(yawOffsetDeg)}°
            |║ Initial Compass: ${"%.1f".format(initialCompassBearing)}°
            |║ Initial AR Yaw: ${"%.1f".format(initialArYaw)}°
            |║ Last Update: ${if (lastAlignmentUpdate > 0) "${(System.currentTimeMillis() - lastAlignmentUpdate) / 1000}s ago" else "Never"}
            |║ $stabilityInfo
            |╚═══════════════════════════════════════
        """.trimMargin()
    }
}