package com.example.orientar.navigation.integration

import com.example.orientar.navigation.rendering.CoordinateAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Integration test for the AR Navigation Heading Refinement Chain.
 *
 * Exercises: CoordinateAligner.addAlignmentSample(...) dual-delta sample chain
 * with synthetic GPS+AR displacement primitives.
 *
 * Test Plan Section 5.2.4 chain 3 / Section 3.4: convergence to within ±10°
 * verified in controlled integration tests.
 *
 * Pass criteria from Table 20: heading offset converges within expected range,
 * edge cases handled without errors.
 *
 * KEY THRESHOLDS (per read-only audit, all private const val):
 *   - MAX_GPS_ACCURACY_FOR_ALIGNMENT = 10.0f
 *   - MIN_GPS_DISPLACEMENT_FOR_ALIGNMENT = 3.0
 *   - MIN_AR_DISPLACEMENT_FOR_ALIGNMENT = 3.0
 *   - MIN_ALIGNMENT_WEIGHT = 0.5 (weight = gpsDistance / gpsAccuracy)
 *   - ALIGNMENT_UPDATE_COOLDOWN_MS = 1000L (was 2000; reduced because at 1Hz
 *     GPS, 2000ms rejected every other update). NOT enforced in
 *     addAlignmentSample — only in updateWithMotion.
 *   - 45° outlier-rejection threshold is HARDCODED in updateWithMotion line 371,
 *     NOT a named constant. Not exercised here (we test addAlignmentSample only).
 *
 * IMPORTANT BEHAVIORAL NOTE — addAlignmentSample is ONE-SHOT for dual-delta:
 *   At line 203, `if (isInitialized && dualDeltaCompleted) return true` causes
 *   all subsequent calls to short-circuit after the first successful alignment.
 *   Convergence is INSTANT (one passing sample sets the offset exactly via
 *   `normalizeAngleDeg(gpsBearing - arBearing)`), NOT gradual smoothing across
 *   a chain. The "sample chain" framing in test 7 still validates the spec
 *   ("offset within ±10° after the chain"), but the offset is determined by
 *   the first sample at index i=1; samples at i=2..5 are no-ops returning
 *   true without state change. Continuous smoothing of the offset happens
 *   downstream in updateWithMotion (with ALIGNMENT_SMOOTHING_FACTOR=0.10),
 *   which is out of scope for this patch (deferred to androidTest).
 *
 * No MockK required — addAlignmentSample takes pure primitives. Per-test
 * construction follows Patch 9 (CoordinateAlignerUnitTest) convention.
 */
class HeadingRefinementIntegrationTest {

    // Test Plan Section 5.2.4 — first addAlignmentSample sets baseline, returns false
    @Test
    fun `first addAlignmentSample returns false because it sets baseline`() {
        val aligner = CoordinateAligner()
        val accepted = aligner.addAlignmentSample(35.2490, 33.0240, 5.0f, 0f, 0f)
        assertFalse("First sample should set baseline and return false", accepted)
        assertFalse("Aligner should not be initialized after baseline-only", aligner.isInitialized())
    }

    // Test Plan Section 5.2.4 — second sample with sufficient displacement → alignment computed
    @Test
    fun `second addAlignmentSample with sufficient displacement returns true and initializes`() {
        val aligner = CoordinateAligner()

        aligner.addAlignmentSample(35.2490, 33.0240, 5.0f, 0f, 0f)  // baseline

        // ~5m north on both GPS (0.000045° lat) and AR (-Z forward) axes — above 3m gates
        val accepted = aligner.addAlignmentSample(35.249045, 33.0240, 5.0f, 0f, -5f)

        assertTrue("Second sample with >3m displacement on both axes should be accepted", accepted)
        assertTrue("Aligner should become initialized after successful sample", aligner.isInitialized())
    }

    // Test Plan Section 5.2.4 — accuracy gate rejects samples >10m
    @Test
    fun `addAlignmentSample rejects gpsAccuracy above 10m threshold`() {
        val aligner = CoordinateAligner()

        aligner.addAlignmentSample(35.2490, 33.0240, 5.0f, 0f, 0f)
        val accepted = aligner.addAlignmentSample(35.249045, 33.0240, 12.0f, 0f, -5f)

        assertFalse("Sample with accuracy=12m should be rejected (>10m gate)", accepted)
        assertFalse("Aligner should remain uninitialized", aligner.isInitialized())
    }

    // Test Plan Section 5.2.4 — GPS displacement gate rejects <3m movement
    @Test
    fun `addAlignmentSample rejects sample with insufficient GPS displacement`() {
        val aligner = CoordinateAligner()

        aligner.addAlignmentSample(35.2490, 33.0240, 5.0f, 0f, 0f)
        // GPS ~1m (below 3m gate), AR 5m (sufficient)
        val accepted = aligner.addAlignmentSample(35.249009, 33.0240, 5.0f, 0f, -5f)

        assertFalse("Sample with <3m GPS displacement should be rejected", accepted)
    }

    // Test Plan Section 5.2.4 — AR displacement gate rejects <3m movement
    @Test
    fun `addAlignmentSample rejects sample with insufficient AR displacement`() {
        val aligner = CoordinateAligner()

        aligner.addAlignmentSample(35.2490, 33.0240, 5.0f, 0f, 0f)
        // GPS 5m (sufficient), AR ~1m (below 3m gate)
        val accepted = aligner.addAlignmentSample(35.249045, 33.0240, 5.0f, 0f, -1f)

        assertFalse("Sample with <3m AR displacement should be rejected", accepted)
    }

    // Test Plan Section 5.2.4 — forceReinitialize recomputes offset after init
    @Test
    fun `forceReinitialize recomputes yaw offset after prior initialization`() {
        val aligner = CoordinateAligner()

        aligner.initialize(0.0, 0.0)
        assertEquals(0.0, aligner.getYawOffset(), 1e-6)

        // forceReinitialize with new inputs → offset recomputed via ArUtils.normalizeAngleDeg
        aligner.forceReinitialize(90.0, 0.0)
        assertEquals(90.0, aligner.getYawOffset(), 1e-6)
    }

    // Test Plan Section 5.2.4 / Section 3.4 — convergence within ±10° after sample chain
    @Test
    fun `addAlignmentSample chain produces yaw offset within tolerance`() {
        val aligner = CoordinateAligner()

        aligner.addAlignmentSample(35.2490, 33.0240, 5.0f, 0f, 0f)  // baseline

        // Walking due north: GPS lat increases, AR moves -Z forward.
        // True bearing = 0° (north). With AR yaw 0° (also pointing north),
        // yaw offset should be near 0°.
        var lastOffset = Double.NaN
        for (i in 1..5) {
            val deltaLat = 0.000045 * i  // ~5m * i steps north
            aligner.addAlignmentSample(35.2490 + deltaLat, 33.0240, 5.0f, 0f, -5f * i)
            if (aligner.isInitialized()) {
                lastOffset = aligner.getYawOffset()
            }
        }

        assertTrue("Aligner should be initialized after sample chain", aligner.isInitialized())
        assertTrue(
            "Yaw offset should be within ±10° of expected 0° for north walk; got $lastOffset",
            abs(lastOffset) <= 10.0
        )
    }
}
