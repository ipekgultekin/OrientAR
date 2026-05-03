package com.example.orientar.navigation.location

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for KalmanFilter — 4-state GPS smoothing filter (lat, lng, vel_lat, vel_lng).
 *
 * Test Plan Section 5.1.4 — behavioral assertions on filter state lifecycle.
 *
 * IMPORTANT JVM-TEST CONSTRAINTS (deviations from prompt):
 *
 * 1. KalmanFilter's tuning constants (MAX_JUMP_DISTANCE, MAX_TIME_GAP, MIN_VARIANCE,
 *    MIN_TIME_DELTA) are declared `private const val` inside the companion object.
 *    Constant-regression-guard tests cannot reference them from outside the class —
 *    those tests are omitted here. The audit-table values stay frozen via the
 *    code-review process instead of compile-time assertion.
 *
 * 2. `KalmanFilter.filter()` returns a Location built via the android.jar stub's
 *    `Location(Location)` copy constructor + `latitude`/`longitude` setters
 *    (KalmanFilter.kt:237-243). Under `testOptions.unitTests.isReturnDefaultValues = true`,
 *    those setters are no-ops and the returned Location's getters return 0.0 by default.
 *    Therefore we CANNOT directly assert `output.latitude == expected` in JVM tests.
 *
 *    Instead, these tests exercise observable primitive-return public APIs
 *    (isInitialized, reset, getVelocity, getApproximateGain) which expose the
 *    filter's internal state evolution without going through Location.
 *
 *    Direct lat/lng correctness verification belongs in androidTest (Robolectric or
 *    instrumented) where a real Location implementation is available — out of scope
 *    for SCRUM-84.
 */
class KalmanFilterUnitTest {

    /**
     * Build a mocked Location with the fields KalmanFilter reads.
     * Uses `relaxed = true` so unstubbed methods return type defaults — needed
     * because the production `Location(originalLocation)` copy constructor inside
     * `createFilteredLocation()` may probe additional getters we don't explicitly stub.
     */
    private fun mockLocation(
        lat: Double,
        lng: Double,
        accuracy: Float = 5.0f,
        timeMs: Long = 1_700_000_000_000L
    ): Location {
        val loc = mockk<Location>(relaxed = true)
        every { loc.latitude } returns lat
        every { loc.longitude } returns lng
        every { loc.accuracy } returns accuracy
        every { loc.time } returns timeMs
        every { loc.hasSpeed() } returns false  // skips the speed-tier branch
        return loc
    }

    // ========================================================================
    // LIFECYCLE — isInitialized state machine
    // ========================================================================

    // Test Plan Section 5.1.4 — initial state contract
    @Test
    fun `KalmanFilter starts uninitialized`() {
        val kf = KalmanFilter()
        assertFalse("Newly constructed filter should be uninitialized", kf.isInitialized())
    }

    // Test Plan Section 5.1.4 — initialization on first input
    @Test
    fun `filter call initializes the filter`() {
        val kf = KalmanFilter()
        kf.filter(mockLocation(35.2490, 33.0239))
        assertTrue("Filter should be initialized after first filter() call", kf.isInitialized())
    }

    // Test Plan Section 5.1.4 — reset() lifecycle
    @Test
    fun `reset restores filter to uninitialized state`() {
        val kf = KalmanFilter()
        kf.filter(mockLocation(35.2490, 33.0239))
        assertTrue("Sanity: filter is initialized before reset", kf.isInitialized())

        kf.reset()
        assertFalse("After reset, filter should be uninitialized", kf.isInitialized())
    }

    // ========================================================================
    // RETURN-CONTRACT — filter never returns null
    // ========================================================================

    // Test Plan Section 5.1.4 — filter() return contract
    @Test
    fun `filter returns non-null Location for valid input`() {
        val kf = KalmanFilter()
        val output = kf.filter(mockLocation(35.2490, 33.0239))
        assertNotNull("filter() should never return null for valid input", output)
    }

    // ========================================================================
    // PRIMITIVE-STATE OBSERVATIONS — what we can read without Location getters
    // ========================================================================

    // Test Plan Section 5.1.4 — getVelocity initial state
    @Test
    fun `getVelocity returns zero zero before initialization`() {
        val kf = KalmanFilter()
        val (velLat, velLng) = kf.getVelocity()
        assertEquals("velLat should be 0 before any input", 0.0, velLat, 0.001)
        assertEquals("velLng should be 0 before any input", 0.0, velLng, 0.001)
    }

    // Test Plan Section 5.1.4 — getApproximateGain initial uncertainty
    @Test
    fun `getApproximateGain is close to 1 before initialization`() {
        val kf = KalmanFilter()
        // Initial variance is 1.0 in both lat and lng. Typical measurement noise for
        // 5m accuracy is (5/111000)² ≈ 2e-9. Gain ≈ 1.0 / (1.0 + 2e-9) ≈ 1.0.
        // This reflects "filter has no prior — trust the next measurement entirely".
        val gain = kf.getApproximateGain()
        assertTrue("Initial gain should be close to 1 (uncertain state); was $gain", gain > 0.9)
    }

    // Test Plan Section 5.1.4 — variance/gain reduction after convergence
    @Test
    fun `getApproximateGain decreases after multiple consistent samples`() {
        val kf = KalmanFilter()
        val baseTime = 1_700_000_000_000L
        val initialGain = kf.getApproximateGain()

        // Feed 10 consistent samples at 1-second intervals — variance should shrink
        // as the filter converges on the position.
        for (i in 0 until 10) {
            kf.filter(
                mockLocation(35.2490, 33.0239, accuracy = 5.0f, timeMs = baseTime + i * 1000L)
            )
        }

        val finalGain = kf.getApproximateGain()
        assertTrue(
            "Gain should decrease as filter converges; initial=$initialGain, final=$finalGain",
            finalGain < initialGain
        )
    }
}
