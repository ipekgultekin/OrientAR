package com.example.orientar.navigation.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for HeadingFusionFilter — complementary filter fusing gyroscope
 * and compass signals into a stable fused heading.
 *
 * Test Plan Section 5.1.4 — pure math tests on primitive-return public API.
 *
 * IMPORTANT (deviations from prompt template):
 *
 * 1. Production constants are `private const val` inside the companion object
 *    (ALPHA=0.98f, ALPHA_LOW=0.90f, ALPHA_HIGH=0.98f, MOTION_THRESHOLD=0.02f
 *    rad/s, MAX_DIVERGENCE_DEG=45.0f, MIN_UPDATE_INTERVAL=0.001f s,
 *    BIAS_ESTIMATION_ALPHA=0.001f). Constant-regression-guard tests cannot
 *    reference them from outside the class — those values are frozen via the
 *    code-review process instead.
 *
 * 2. Production signatures differ from the prompt's template:
 *      - `updateGyro(gyroZ: Float, timestamp: Long)` — single Z-axis float, NOT
 *        a four-parameter (x, y, z, timestamp) tuple.
 *      - `updateCompass(compassHeading: Float)` — no timestamp parameter.
 *      - Motion accessor is `isMoving(): Boolean`, not `isMotionDetected()`.
 *
 * 3. `updateGyro` requires the filter to be initialized (via `updateCompass`)
 *    AND a previously recorded `lastGyroTimestamp` such that the new sample's
 *    dt falls within `[MIN_UPDATE_INTERVAL, 1.0s]`. Tests that exercise motion
 *    detection seed `lastGyroTimestamp` with an initial pre-init `updateGyro`
 *    call so the second post-init call has a valid 1ms dt.
 */
class HeadingFusionFilterUnitTest {

    // ========================================================================
    // LIFECYCLE — isInitialized state machine
    // ========================================================================

    // Test Plan Section 5.1.4 — initial state contract
    @Test
    fun `HeadingFusionFilter starts uninitialized`() {
        val filter = HeadingFusionFilter()
        assertFalse("Newly constructed filter should be uninitialized", filter.isInitialized())
    }

    // Test Plan Section 5.1.4 — initialization on first compass update
    @Test
    fun `compass update initializes the filter`() {
        val filter = HeadingFusionFilter()
        filter.updateCompass(90.0f)
        assertTrue("Filter should be initialized after first compass update", filter.isInitialized())
    }

    // Test Plan Section 5.1.4 — reset() lifecycle
    @Test
    fun `reset restores filter to uninitialized state`() {
        val filter = HeadingFusionFilter()
        filter.updateCompass(90.0f)
        assertTrue("Sanity: filter is initialized before reset", filter.isInitialized())

        filter.reset()
        assertFalse("After reset, filter should be uninitialized", filter.isInitialized())
    }

    // ========================================================================
    // FUSED HEADING — pure math
    // ========================================================================

    // Test Plan Section 5.1.4 — first compass update sets fused heading
    @Test
    fun `fused heading approximately equals compass after first compass update`() {
        val filter = HeadingFusionFilter()
        filter.updateCompass(90.0f)
        val fused = filter.getFusedHeading()
        // First compass-only update: fused heading is initialized to the
        // normalized compass value with no gyro state mixed in.
        assertEquals(90.0f, fused, 1.0f)
    }

    // Test Plan Section 5.1.4 — zero gyro updates leave heading approximately stable at 0
    @Test
    fun `fused heading at 0 degrees stays at 0 with zero gyro updates`() {
        val filter = HeadingFusionFilter()
        filter.updateCompass(0.0f)

        // Two updateGyro calls with zero rotation. The first one after init
        // typically gets bypassed because lastGyroTimestamp was 0 (huge dt);
        // the second one has dt=1ms and proceeds, applying zero heading delta.
        filter.updateGyro(0.0f, 1_001_000_000L)
        filter.updateGyro(0.0f, 1_002_000_000L)

        // Re-correct via compass; should still be ~0.
        filter.updateCompass(0.0f)

        val fused = filter.getFusedHeading()
        assertEquals(0.0f, fused, 1.0f)
    }

    // ========================================================================
    // MOTION DETECTION — gyro Z magnitude vs MOTION_THRESHOLD (0.02 rad/s)
    // ========================================================================

    // Test Plan Section 5.1.4 — motion detection threshold (below)
    @Test
    fun `isMoving is false for small gyro values below threshold`() {
        val filter = HeadingFusionFilter()
        // Seed lastGyroTimestamp via a pre-init updateGyro call (the not-init
        // branch sets lastGyroTimestamp and returns).
        filter.updateGyro(0.0f, 1_000_000_000L)
        filter.updateCompass(0.0f)

        // gyroZ = 0.01 rad/s is BELOW MOTION_THRESHOLD of 0.02 rad/s.
        // dt = 1ms = MIN_UPDATE_INTERVAL exactly, so the update proceeds.
        filter.updateGyro(0.01f, 1_001_000_000L)
        assertFalse("0.01 rad/s should not trigger motion", filter.isMoving())
    }

    // Test Plan Section 5.1.4 — motion detection threshold (above)
    @Test
    fun `isMoving is true for gyro values above threshold`() {
        val filter = HeadingFusionFilter()
        filter.updateGyro(0.0f, 1_000_000_000L)  // seed timestamp
        filter.updateCompass(0.0f)

        // gyroZ = 0.5 rad/s is well above MOTION_THRESHOLD of 0.02 rad/s.
        filter.updateGyro(0.5f, 1_001_000_000L)
        assertTrue("0.5 rad/s should trigger motion", filter.isMoving())
    }
}
