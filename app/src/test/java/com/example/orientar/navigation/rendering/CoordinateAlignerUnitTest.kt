package com.example.orientar.navigation.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CoordinateAligner — yawOffsetDeg between GPS (compass) and
 * AR (-Z forward) coordinate systems.
 *
 * Test Plan Section 5.1.4 — primitive-return public API tests.
 *
 * SCOPE: methods that take primitive doubles/floats as input.
 *
 * Production constants frozen via code review (all `private const val`):
 *   MIN_GPS_DISPLACEMENT_FOR_ALIGNMENT = 3.0 m
 *   MIN_AR_DISPLACEMENT_FOR_ALIGNMENT = 3.0 m
 *   MAX_GPS_ACCURACY_FOR_ALIGNMENT = 10.0f
 *   MAX_REASONABLE_OFFSET_CHANGE = 45.0
 *   MAX_ALIGNMENT_SAMPLES = 10
 *   MIN_ALIGNMENT_WEIGHT = 0.5
 *   ALIGNMENT_SMOOTHING_FACTOR = 0.10
 *   ALIGNMENT_UPDATE_COOLDOWN_MS = 1000L
 *   ALIGNMENT_WARNING_THRESHOLD_DEG = 30.0
 *
 * IMPORTANT (deviations from prompt template):
 *
 * 1. `initialize(compassBearing, arCameraYaw)` takes `Double, Double` — NOT
 *    `Float, Float` as the template assumed. Tests pass `0.0` instead of `0.0f`.
 *
 * 2. The yaw-offset accessor is `getYawOffset(): Double` — NOT `getYawOffsetDeg()`.
 *
 * 3. `normalizeAngleDeg` (used by `initialize`) returns range `[-180, 180)`,
 *    so `initialize(350.0, 20.0)` produces `yawOffset = -30.0` (not `330.0`).
 *
 * 4. `calculateYawFromPose(pose: Pose)` is NOT JVM-testable — requires a
 *    functional ARCore Pose, which is stubbed under returnDefaultValues=true.
 *    `updateWithMotion(location: Location, ...)` would need a MockK Location.
 *    Both deferred to androidTest / field walks.
 */
class CoordinateAlignerUnitTest {

    // ========================================================================
    // LIFECYCLE — isInitialized state machine
    // ========================================================================

    // Test Plan Section 5.1.4 — initial state contract
    @Test
    fun `CoordinateAligner starts uninitialized`() {
        val aligner = CoordinateAligner()
        assertFalse("Newly constructed aligner should be uninitialized", aligner.isInitialized())
    }

    // Test Plan Section 5.1.4 — compass init lifecycle
    @Test
    fun `compass init flips isInitialized to true`() {
        val aligner = CoordinateAligner()
        aligner.initialize(0.0, 0.0)
        assertTrue("Aligner should be initialized after first initialize() call", aligner.isInitialized())
    }

    // ========================================================================
    // YAW OFFSET COMPUTATION — pure math via initialize()
    // ========================================================================

    // Test Plan Section 5.1.4 — identity case: compass=0, arYaw=0 → offset=0
    @Test
    fun `compass init with zero compass and zero arYaw produces zero offset`() {
        val aligner = CoordinateAligner()
        aligner.initialize(0.0, 0.0)
        assertEquals(0.0, aligner.getYawOffset(), 0.01)
    }

    // Test Plan Section 5.1.4 — basic offset: compass=90, arYaw=0 → offset=90
    @Test
    fun `compass init with 90 degree compass and 0 arYaw produces 90 degree offset`() {
        val aligner = CoordinateAligner()
        aligner.initialize(90.0, 0.0)
        assertEquals(90.0, aligner.getYawOffset(), 0.01)
    }

    // Test Plan Section 5.1.4 — wraparound across the [-180, 180) boundary
    // compass - arYaw = 350 - 20 = 330; normalizeAngleDeg(330) = -30
    @Test
    fun `compass init handles wraparound at 350 compass and 20 arYaw`() {
        val aligner = CoordinateAligner()
        aligner.initialize(350.0, 20.0)
        // Production normalizes to [-180, 180), so 330° becomes -30°.
        assertEquals(-30.0, aligner.getYawOffset(), 0.01)
    }

    // ========================================================================
    // RESET LIFECYCLE
    // ========================================================================

    // Test Plan Section 5.1.4 — reset() restores uninitialized state
    @Test
    fun `reset returns aligner to uninitialized state`() {
        val aligner = CoordinateAligner()
        aligner.initialize(45.0, 0.0)
        assertTrue("Sanity: aligner is initialized before reset", aligner.isInitialized())

        aligner.reset()
        assertFalse("After reset, aligner should be uninitialized", aligner.isInitialized())
    }

    // ========================================================================
    // STATE STABILITY — yaw offset is deterministic across reads
    // ========================================================================

    // Test Plan Section 5.1.4 — repeated reads return same value (no read-side mutation)
    @Test
    fun `initialized aligner maintains yaw offset across multiple gets`() {
        val aligner = CoordinateAligner()
        aligner.initialize(45.0, 0.0)
        val first = aligner.getYawOffset()
        val second = aligner.getYawOffset()
        val third = aligner.getYawOffset()
        assertEquals("First and second reads should match", first, second, 0.001)
        assertEquals("Second and third reads should match", second, third, 0.001)
    }
}
