package com.example.orientar.navigation.rendering

import android.location.Location
import com.example.orientar.navigation.logic.ArUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import com.example.orientar.navigation.util.FileLogger
/**
 * Manages alignment between GPS (compass-based) and AR (ARCore) coordinate systems.
 *
 * The core problem: when AR starts, -Z (forward) maps to whatever direction the device faces.
 * yawOffset = compassBearing - arCameraYaw bridges the two systems.
 * To convert GPS bearing to AR angle: arAngle = gpsBearing - yawOffset
 */
class CoordinateAligner {
    companion object {
        private const val TAG = "CoordinateAligner"

        // Minimum speed required for motion-based alignment updates
        // Too low: GPS bearing is unreliable when moving slowly (noise dominates)
        // Too high: Updates rarely happen
        // 1.0 m/s = 3.6 km/h = slow walking pace
        private const val MIN_SPEED_FOR_ALIGNMENT = 0.2f

        // How much to adjust offset per update (0.0 = no change, 1.0 = full change)
        // Lower values = more stable but slower to correct drift
        // Higher values = faster correction but more jittery
        private const val ALIGNMENT_SMOOTHING_FACTOR = 0.10

        // Minimum time between alignment updates (prevents oscillation)
        private const val ALIGNMENT_UPDATE_COOLDOWN_MS = 1000L  // was 2000; at 1Hz GPS, 2000ms rejected every other update

        // Maximum allowed alignment error before logging warning
        private const val ALIGNMENT_WARNING_THRESHOLD_DEG = 30.0

        // Maximum allowed initial offset difference for sanity check
        private const val MAX_REASONABLE_OFFSET_CHANGE = 45.0

        // ========================================================================================
        // DUAL-DELTA ALIGNMENT CONSTANTS
        // ========================================================================================

        // Minimum GPS displacement before computing alignment (meters)
        // Too low: GPS jitter produces wrong bearing
        // Too high: User must walk too far before spheres appear
        private const val MIN_GPS_DISPLACEMENT_FOR_ALIGNMENT = 3.0

        // Minimum AR displacement before computing alignment (meters)
        // Filters out cases where GPS jumped but user didn't actually move
        private const val MIN_AR_DISPLACEMENT_FOR_ALIGNMENT = 3.0

        // Maximum GPS accuracy allowed for alignment samples (meters)
        // Poor accuracy = noisy bearing = bad alignment
        private const val MAX_GPS_ACCURACY_FOR_ALIGNMENT = 10.0f

        // Maximum number of alignment samples to keep
        private const val MAX_ALIGNMENT_SAMPLES = 10

        // Minimum weight sum before accepting alignment (quality threshold)
        // weight = gpsDistance / gpsAccuracy; 0.5 = 5m walk with 10m accuracy passes
        private const val MIN_ALIGNMENT_WEIGHT = 0.5  // was 1.0; too strict for campus GPS (6-9m accuracy)
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

    // Offset history for stability analysis (capped at MAX_HISTORY_SIZE)
    private val MAX_HISTORY_SIZE = 5
    private val offsetHistory = ArrayDeque<Double>(MAX_HISTORY_SIZE)

    // Initial values for diagnostics
    private var initialCompassBearing: Double = 0.0
    private var initialArYaw: Double = 0.0

    private var hasReceivedFirstGPSCorrection = false
    private var motionUpdateCount = 0  // Tracks accepted motion updates for heading confidence

    // ========================================================================================
    // DUAL-DELTA ALIGNMENT STATE
    // ========================================================================================

    /**
     * A snapshot of GPS position + AR camera position at the same moment.
     */
    data class AlignmentSnapshot(
        val gpsLat: Double,
        val gpsLng: Double,
        val gpsAccuracy: Float,
        val arX: Float,
        val arZ: Float,
        val timestamp: Long
    )

    // The FIRST snapshot (baseline) taken when navigation starts
    private var baselineSnapshot: AlignmentSnapshot? = null

    // Rolling collection of snapshots for multi-sample alignment
    private val alignmentSnapshots = mutableListOf<AlignmentSnapshot>()

    // Whether dual-delta alignment has produced a result
    private var dualDeltaCompleted = false
    private var lastDualDeltaProgressLog = 0L


    /**
     * Initializes the yaw offset using compass bearing and AR camera orientation.
     * Call when: compass calibrated, AR tracking stable, user standing still.
     */
    fun initialize(compassBearing: Double, arCameraYaw: Double) {
        if (compassBearing < 0 || compassBearing >= 360) {
            FileLogger.w(TAG, "Compass bearing out of range [0,360): $compassBearing")
        }
        if (isInitialized) {
            FileLogger.w(TAG, "Already initialized. Use forceReinitialize() to reset.")
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
            FileLogger.e(TAG, "Invalid yaw offset! compass=$compassBearing, arYaw=$arCameraYaw")
            yawOffsetDeg = 0.0
        }

        isInitialized = true

        // Initialize offset history for stability tracking
        offsetHistory.clear()
        offsetHistory.add(yawOffsetDeg)

        FileLogger.align("INITIALIZED: compass=${compassBearing.toInt()}°, arYaw=${arCameraYaw.toInt()}°, offset=${yawOffsetDeg.toInt()}°")
    }

    /**
     * Force re-initialization. Use when drift is severe, re-anchoring occurs, or user requests recalibration.
     */
    fun forceReinitialize(compassBearing: Double, arCameraYaw: Double) {
        val oldOffset = yawOffsetDeg
        isInitialized = false
        lastAlignmentUpdate = 0
        offsetHistory.clear()
        hasReceivedFirstGPSCorrection = false
        motionUpdateCount = 0

        initialize(compassBearing, arCameraYaw)

        val offsetChange = ArUtils.normalizeAngleDeg(yawOffsetDeg - oldOffset)
        FileLogger.align("Force reinitialized: ${oldOffset.toInt()}° → ${yawOffsetDeg.toInt()}° (change: ${offsetChange.toInt()}°)")

        if (abs(offsetChange) > MAX_REASONABLE_OFFSET_CHANGE) {
            FileLogger.w(TAG, "Large offset change (${offsetChange.toInt()}°) — may cause position jump")
        }
    }

    // ============================================================================================
    // DUAL-DELTA ALIGNMENT
    // ============================================================================================

    /**
     * Records a GPS + AR position snapshot for dual-delta alignment.
     * Call this on every GPS update during the "waiting for alignment" phase.
     *
     * When enough displacement is detected (>5m GPS AND >3m AR), the yaw offset
     * is automatically computed from the angle difference between the GPS vector
     * and the AR vector. No compass or speed threshold needed.
     *
     * @param gpsLat Current GPS latitude
     * @param gpsLng Current GPS longitude
     * @param gpsAccuracy Current GPS accuracy in meters
     * @param arX ARCore camera world position X
     * @param arZ ARCore camera world position Z (note: Y is up in ARCore)
     * @return true if alignment was computed and aligner is now initialized
     */
    fun addAlignmentSample(
        gpsLat: Double,
        gpsLng: Double,
        gpsAccuracy: Float,
        arX: Float,
        arZ: Float
    ): Boolean {
        // If already initialized AND dual-delta wasn't reset, skip
        // (allows fresh recalibration after re-anchor resets dual-delta state)
        if (isInitialized && dualDeltaCompleted) return true

        // Reject poor GPS readings
        if (gpsAccuracy > MAX_GPS_ACCURACY_FOR_ALIGNMENT) {
            FileLogger.d(TAG, "Dual-delta: skipping sample, GPS accuracy ${gpsAccuracy}m too poor")
            return false
        }

        val now = System.currentTimeMillis()
        val snapshot = AlignmentSnapshot(gpsLat, gpsLng, gpsAccuracy, arX, arZ, now)

        // Set baseline on first good sample
        if (baselineSnapshot == null) {
            baselineSnapshot = snapshot
            FileLogger.align("Dual-delta: baseline set at GPS=(${String.format("%.6f", gpsLat)}, ${String.format("%.6f", gpsLng)}), AR=(${String.format("%.1f", arX)}, ${String.format("%.1f", arZ)})")
            return false
        }

        val baseline = baselineSnapshot!!

        // Calculate GPS displacement from baseline
        val gpsDistance = ArUtils.distanceMeters(
            baseline.gpsLat, baseline.gpsLng,
            gpsLat, gpsLng
        )

        // Calculate AR displacement from baseline
        val arDx = arX - baseline.arX
        val arDz = arZ - baseline.arZ
        val arDistance = Math.sqrt((arDx * arDx + arDz * arDz).toDouble())

        // Not enough movement yet
        if (gpsDistance < MIN_GPS_DISPLACEMENT_FOR_ALIGNMENT || arDistance < MIN_AR_DISPLACEMENT_FOR_ALIGNMENT) {
            if (now - lastDualDeltaProgressLog > 3000L) {
                lastDualDeltaProgressLog = now
                FileLogger.d(TAG, "Dual-delta progress: gpsDisp=${String.format("%.1f", gpsDistance)}m/${MIN_GPS_DISPLACEMENT_FOR_ALIGNMENT}m, " +
                    "arDisp=${String.format("%.1f", arDistance)}m/${MIN_AR_DISPLACEMENT_FOR_ALIGNMENT}m, " +
                    "accuracy=${String.format("%.1f", gpsAccuracy)}m, weight=${String.format("%.2f", gpsDistance / gpsAccuracy)}")
            }
            return false
        }

        // ====================================================================
        // ENOUGH DISPLACEMENT — COMPUTE ALIGNMENT
        // ====================================================================

        // GPS bearing: geographic direction from baseline to current position
        val gpsBearing = ArUtils.bearingDeg(
            baseline.gpsLat, baseline.gpsLng,
            gpsLat, gpsLng
        )

        // AR bearing: direction in AR space from baseline to current position
        // atan2(dx, -dz) because ARCore: +X=right, -Z=forward
        val arBearing = (Math.toDegrees(
            Math.atan2(arDx.toDouble(), -arDz.toDouble())
        ) + 360.0) % 360.0

        // Yaw offset = geographic bearing - AR bearing
        val computedOffset = ArUtils.normalizeAngleDeg(gpsBearing - arBearing)

        // Weight by (distance / accuracy) — more distance + better accuracy = more reliable
        val weight = gpsDistance / gpsAccuracy

        FileLogger.align("Dual-delta SAMPLE: gpsBearing=${String.format("%.1f", gpsBearing)}°, arBearing=${String.format("%.1f", arBearing)}°, offset=${String.format("%.1f", computedOffset)}°, weight=${String.format("%.2f", weight)}")

        // Store for potential multi-sample averaging
        alignmentSnapshots.add(snapshot)

        // Accept first sample that passes weight threshold.
        // Safety nets: ±5° clamp on motion updates + disabled strong first correction
        // protect against a bad initial sample.
        if (weight >= MIN_ALIGNMENT_WEIGHT) {
            yawOffsetDeg = computedOffset
            isInitialized = true
            dualDeltaCompleted = true

            initialCompassBearing = gpsBearing  // Store for diagnostics (not actually compass)
            initialArYaw = arBearing

            offsetHistory.clear()
            offsetHistory.add(yawOffsetDeg)

            FileLogger.align("DUAL-DELTA INITIALIZED: offset=${yawOffsetDeg.toInt()}° (gpsBearing=${gpsBearing.toInt()}°, arBearing=${arBearing.toInt()}°, displacement=${String.format("%.1f", gpsDistance)}m, weight=${String.format("%.1f", weight)})")
            return true
        }

        FileLogger.d(TAG, "Dual-delta: weight ${String.format("%.2f", weight)} below threshold $MIN_ALIGNMENT_WEIGHT, waiting for more displacement")
        return false
    }

    /**
     * Resets dual-delta state for a new alignment attempt.
     * Call when re-anchoring or recalibrating.
     */
    fun resetDualDelta() {
        baselineSnapshot = null
        alignmentSnapshots.clear()
        dualDeltaCompleted = false
        lastDualDeltaProgressLog = 0L
        FileLogger.d(TAG, "Dual-delta state reset")
    }

    /**
     * Whether dual-delta alignment has been completed at least once.
     */
    fun isDualDeltaCompleted(): Boolean = dualDeltaCompleted

    // ============================================================================================
    // MOTION-BASED ALIGNMENT UPDATES
    // ============================================================================================

    /**
     * Updates yaw offset based on walking direction (GPS bearing vs AR camera yaw).
     * Gradually corrects accumulated drift. GPS bearing requires speed > 1 m/s.
     */
    fun updateWithMotion(location: Location, arCameraYaw: Double, computedBearing: Double? = null, computedDisplacement: Double? = null): Boolean {
        if (!isInitialized) {
            FileLogger.w(TAG, "Cannot update: not initialized")
            return false
        }

        // ========================================================================
        // VALIDATION: Get a reliable GPS bearing
        // ========================================================================

        // Use computed bearing from Kalman positions, fall back to location.bearing
        val gpsBearing = when {
            computedBearing != null -> computedBearing
            location.hasBearing() && location.speed >= MIN_SPEED_FOR_ALIGNMENT -> location.bearing.toDouble()
            else -> {
                FileLogger.d("MOTION_SKIP", "No bearing available (computed=null, hasBearing=${location.hasBearing()}, speed=${String.format("%.2f", location.speed)})")
                return false
            }
        }

        // GPS bearing is less reliable with poor accuracy
        if (location.accuracy > 10.0f) {  // was 5.0; quality-weight system already down-weights poor accuracy
            FileLogger.d("MOTION_SKIP", "Accuracy too poor: ${String.format("%.1f", location.accuracy)}m > 10.0m")
            return false
        }

        // ========================================================================
        // RATE LIMITING: Don't update too frequently
        // ========================================================================
        val now = System.currentTimeMillis()
        if (now - lastAlignmentUpdate < ALIGNMENT_UPDATE_COOLDOWN_MS) {
            FileLogger.d("MOTION_SKIP", "Cooldown: ${now - lastAlignmentUpdate}ms < ${ALIGNMENT_UPDATE_COOLDOWN_MS}ms")
            return false
        }

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
            FileLogger.d("MOTION_SKIP", "Diff too small: ${String.format("%.1f", diff)}° < 2.0°")
            return false
        }

        // Outlier rejection: if correction > 45°, the GPS bearing is likely noise
        if (abs(diff) > 45.0) {
            FileLogger.w("MOTION_OUTLIER", "Rejected ${String.format("%.1f", diff)}° correction — " +
                "gpsBearing=${String.format("%.0f", gpsBearing)}°, likely noise")
            return false
        }

        FileLogger.d("MOTION_EVAL", "PASSED all gates: gpsBearing=${String.format("%.0f", gpsBearing)}°, arYaw=${String.format("%.0f", arCameraYaw)}°, targetOffset=${String.format("%.0f", targetOffset)}°, diff=${String.format("%.1f", diff)}°, motionCount=${motionUpdateCount + 1}")

        // ========================================================================
        // APPLY SMOOTHED CORRECTION
        // ========================================================================
        val oldOffset = yawOffsetDeg

        // Apply smoothed correction (gradual adjustment to prevent sudden jumps)
        val isFirstCorrection = !hasReceivedFirstGPSCorrection
        val effectiveFactor = if (!hasReceivedFirstGPSCorrection && abs(diff) > 15.0 && !dualDeltaCompleted) {
            // Strong correction only needed for compass initialization.
            // Dual-delta gives a good initial heading — no strong correction needed.
            hasReceivedFirstGPSCorrection = true
            FileLogger.align("FIRST GPS correction: strong factor (0.8) for ${diff.toInt()}° error")
            0.8
        } else {
            if (!hasReceivedFirstGPSCorrection && dualDeltaCompleted) {
                FileLogger.align("Skipping strong first correction — dual-delta provided good heading")
            }
            hasReceivedFirstGPSCorrection = true
            ALIGNMENT_SMOOTHING_FACTOR
        }

        // Quality weight based on displacement/accuracy ratio
        // If displacement unavailable, preserve original unweighted behavior.
        val qualityWeight = if (computedDisplacement != null && location.accuracy > 0) {
            val ratio = computedDisplacement / location.accuracy.toDouble()
            (ratio / 2.0).coerceIn(0.2, 1.0)
        } else {
            1.0  // No displacement data — preserve original behavior
        }
        val weightedFactor = effectiveFactor * qualityWeight

        FileLogger.d("MOTION_QUALITY",
            "disp=${String.format("%.1f", computedDisplacement ?: -1.0)}m " +
            "acc=${String.format("%.1f", location.accuracy)}m " +
            "ratio=${String.format("%.2f", if (computedDisplacement != null && location.accuracy > 0) computedDisplacement / location.accuracy else -1.0)} " +
            "weight=${String.format("%.2f", qualityWeight)} " +
            "factor=${String.format("%.3f", weightedFactor)} " +
            "correction=${String.format("%.1f", diff)}°")

        val rawCorrection = diff * weightedFactor
        val maxCorrection = if (isFirstCorrection) 15.0 else 5.0  // ±15° for first correction (compass error), ±5° for subsequent
        val correction = rawCorrection.coerceIn(-maxCorrection, maxCorrection)
        yawOffsetDeg += correction
        yawOffsetDeg = ArUtils.normalizeAngleDeg(yawOffsetDeg)
        motionUpdateCount++

        // Track in history for stability analysis
        // BUG-007 FIX: Use constant for size limit
        offsetHistory.add(yawOffsetDeg)
        while (offsetHistory.size > MAX_HISTORY_SIZE) {
            offsetHistory.removeFirst()
        }

        lastAlignmentUpdate = now

        // Log the update
        FileLogger.align("Motion update: ${oldOffset.toInt()}° → ${yawOffsetDeg.toInt()}° (correction: ${String.format("%.1f", diff * ALIGNMENT_SMOOTHING_FACTOR)}°, speed: ${String.format("%.1f", location.speed)}m/s)")

        if (abs(diff) > ALIGNMENT_WARNING_THRESHOLD_DEG) {
            FileLogger.w(TAG, "Large alignment correction needed (${diff.toInt()}°) — possible significant drift")
        }

        return true
    }

    // ============================================================================================
    // GETTERS AND STATE QUERIES
    // ============================================================================================

    fun getYawOffset(): Double {
        return yawOffsetDeg
    }

    fun getMotionUpdateCount(): Int = motionUpdateCount

    /**
     * Sets the yaw offset directly.
     * Use this for 180° flip corrections when behind-camera is detected.
     *
     * @param offset New yaw offset in degrees (0-360)
     */
    fun setYawOffset(offset: Double) {
        val normalizedOffset = ((offset % 360.0) + 360.0) % 360.0
        FileLogger.align("Yaw offset set directly: ${yawOffsetDeg.toInt()}° → ${normalizedOffset.toInt()}°")
        yawOffsetDeg = normalizedOffset
    }

    /**
     * Returns whether the aligner has been initialized.
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Calculates AR yaw from forward direction vector.
     * yaw = atan2(forwardX, -forwardZ), normalized to [0, 360).
     */
    fun calculateYawFromForward(forwardX: Float, forwardZ: Float): Double {
        if (forwardX == 0f && forwardZ == 0f) {
            FileLogger.w(TAG, "Forward vector is zero — cannot calculate yaw")
            return 0.0
        }

        val xzMagnitude = kotlin.math.sqrt(forwardX * forwardX + forwardZ * forwardZ)

        // atan2(x, -z) gives angle from -Z axis (forward) to the forward vector
        // Result is in radians: [-π, π]
        val yawRad = atan2(forwardX.toDouble(), -forwardZ.toDouble())

        // Convert to degrees and normalize to [0, 360)
        val yaw = ((Math.toDegrees(yawRad) + 360.0) % 360.0)

        FileLogger.d("YAW_CALC", "forwardX=${String.format("%.3f", forwardX)}, forwardZ=${String.format("%.3f", forwardZ)}, " +
            "xzMagnitude=${String.format("%.3f", xzMagnitude)}, yaw=${String.format("%.1f", yaw)}°" +
            if (xzMagnitude < 0.3f) " ⚠️ LOW XZ MAGNITUDE — phone may be too flat/vertical" else "")

        return yaw
    }

    /**
     * Extract horizontal yaw from ARCore camera pose quaternion.
     * Uses the pose rotation matrix zAxis which gives the camera's forward
     * direction independent of phone tilt. This is stable whether the phone
     * is at 30°, 60°, or 90° tilt.
     *
     * ARCore pose convention:
     * - Identity pose: camera looks along -Z, so zAxis = (0, 0, 1)
     * - pose.zAxis points OPPOSITE the camera viewing direction (forward = -zAxis horizontally)
     * - Formula: atan2(-zAxis[0], zAxis[2]) produces compass-positive yaw
     *   (0° = AR's -Z direction, 90° = AR's +X direction, clockwise-positive)
     * - The X component must be negated to match calculateYawFromForward's
     *   atan2(forwardX, -forwardZ) convention. Both functions produce the same
     *   yaw for the same physical direction.
     */
    fun calculateYawFromPose(pose: com.google.ar.core.Pose): Double {
        val zAxis = pose.zAxis
        val xzMagnitude = sqrt(zAxis[0] * zAxis[0] + zAxis[2] * zAxis[2])

        val yawRad = atan2(-zAxis[0].toDouble(), zAxis[2].toDouble())
        val yaw = ((Math.toDegrees(yawRad) + 360.0) % 360.0)

        FileLogger.d("YAW_CALC", "POSE: zX=${String.format("%.3f", zAxis[0])}, zZ=${String.format("%.3f", zAxis[2])}, " +
            "xzMag=${String.format("%.3f", xzMagnitude)}, yaw=${String.format("%.1f", yaw)}°")

        return yaw
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
        hasReceivedFirstGPSCorrection = false
        motionUpdateCount = 0
        baselineSnapshot = null
        alignmentSnapshots.clear()
        dualDeltaCompleted = false
        FileLogger.align("Coordinate aligner RESET")
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