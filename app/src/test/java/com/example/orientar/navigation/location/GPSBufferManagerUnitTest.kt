package com.example.orientar.navigation.location

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for GPSBufferManager — pre-navigation GPS sample buffer
 * with quality filtering (accuracy + scatter + HDOP/satellite tiers).
 *
 * Test Plan Section 5.1.4 — primitive-return public API tests.
 *
 * IMPORTANT (deviations from prompt template):
 *
 * 1. Production constants (`MAX_BUFFER_SIZE = 20`, `HDOP_*`, `SATELLITES_*`,
 *    `FIX_*`) are `private const val` inside the companion object —
 *    constant-regression-guard tests cannot reference them from outside.
 *    Constructor defaults (`requiredSampleCount = 8`, `maxAccuracyThreshold = 10.0f`,
 *    `maxScatterDistance = 5.0f`) are private val — also not directly assertable.
 *
 * 2. Production has NO `isReady()` method. Tests use
 *    `getState() == State.READY` instead.
 *
 * 3. Production has NO `getBestSample()` method. The closest equivalents are
 *    `getLastSample(): Location?`, `getLastEnhancedSample(): EnhancedSample?`,
 *    and `calculateWeightedAverage(): Location?`. Tests use `getLastSample()`
 *    plus `getSampleCount()` for the "buffer is empty" assertions.
 *
 * 4. The "state transitions to READY" and "reset from READY" tests originally
 *    in the prompt template are OMITTED here because of a JVM-test-infrastructure
 *    constraint: `isClusterTight()` (production line 307-325) computes the
 *    cluster center via `calculateWeightedAverage(samples)` which builds a
 *    Location via `Location(...).apply { latitude = X; longitude = Y }`. Under
 *    `testOptions.unitTests.isReturnDefaultValues = true`, those setters are
 *    no-ops on the android.jar stub — the center Location has lat=0/lng=0,
 *    causing distance-from-center to return ~5,000,000 m, which exceeds
 *    `maxScatterDistance` (5.0f). The buffer's state correctly transitions
 *    to REJECTED instead of READY in JVM tests, even with identical input
 *    coordinates. Same Location-stub issue as KalmanFilter (Patch 6).
 *
 *    Cluster-tightness and READY-transition coverage belongs in androidTest
 *    where a real Location implementation is available — out of scope for
 *    SCRUM-84.
 */
class GPSBufferManagerUnitTest {

    /**
     * Build a mocked Location with the fields GPSBufferManager reads.
     * Uses `relaxed = true` so the production code's `location.extras?.getFloat("hdop", ...)`
     * etc. resolve to null defaults (no HDOP/satellite filtering applied).
     */
    private fun mockLocation(
        lat: Double,
        lng: Double,
        accuracy: Float = 5.0f,
        timeMs: Long = 1_000L
    ): Location {
        val loc = mockk<Location>(relaxed = true)
        every { loc.latitude } returns lat
        every { loc.longitude } returns lng
        every { loc.accuracy } returns accuracy
        every { loc.time } returns timeMs
        every { loc.hasAccuracy() } returns true
        return loc
    }

    // ========================================================================
    // INITIAL STATE
    // ========================================================================

    // Test Plan Section 5.1.4 — initial state contract
    @Test
    fun `GPSBufferManager starts in COLLECTING state`() {
        val buffer = GPSBufferManager()
        assertEquals(GPSBufferManager.State.COLLECTING, buffer.getState())
    }

    // Test Plan Section 5.1.4 — empty-buffer query contract
    @Test
    fun `getLastSample returns null before any samples are added`() {
        val buffer = GPSBufferManager()
        assertNull("Empty buffer should have no last sample", buffer.getLastSample())
    }

    // Test Plan Section 5.1.4 — sample-count counter starts at zero
    @Test
    fun `getSampleCount returns 0 before any samples`() {
        val buffer = GPSBufferManager()
        assertEquals(0, buffer.getSampleCount())
    }

    // ========================================================================
    // STATE TRANSITIONS
    // ========================================================================

    // Test Plan Section 5.1.4 — accuracy filter blocks out-of-spec samples
    @Test
    fun `samples above accuracy threshold are rejected`() {
        val buffer = GPSBufferManager()
        // accuracy 50m is way above maxAccuracyThreshold = 10.0f.
        // Each sample is rejected at the accuracy filter (line 119-124) and the
        // buffer never reaches the requiredSampleCount → state stays COLLECTING.
        repeat(8) { i ->
            buffer.addSample(
                mockLocation(35.2490, 33.0239, accuracy = 50.0f, timeMs = 1_000L + i * 1000L)
            )
        }
        assertNotEquals(
            "Bad-accuracy samples should not transition to READY",
            GPSBufferManager.State.READY,
            buffer.getState()
        )
        // Buffer count should also stay 0 because accuracy-rejected samples
        // are not added to the internal buffer.
        assertEquals("Rejected samples should not grow the buffer", 0, buffer.getSampleCount())
    }

    // Test Plan Section 5.1.4 — reset() returns buffer to initial state
    @Test
    fun `reset clears buffer back to COLLECTING`() {
        val buffer = GPSBufferManager()
        // Add a few good-accuracy samples so the buffer accumulates state
        // (without requiring the cluster-tightness path that needs a real
        // Location implementation — see file KDoc note 4).
        repeat(3) { i ->
            buffer.addSample(
                mockLocation(35.2490, 33.0239, accuracy = 4.0f, timeMs = 1_000L + i * 1000L)
            )
        }
        assertEquals("Sanity: 3 good samples should be in the buffer", 3, buffer.getSampleCount())

        buffer.reset()
        assertEquals(GPSBufferManager.State.COLLECTING, buffer.getState())
        assertEquals("Buffer count should be cleared after reset", 0, buffer.getSampleCount())
    }
}
