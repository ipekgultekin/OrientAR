package com.example.orientar.navigation.integration

import android.location.Location
import com.example.orientar.navigation.location.GPSBufferManager
import com.example.orientar.navigation.location.KalmanFilter
import com.example.orientar.navigation.logic.ArUtils
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration test for the AR Navigation GPS Processing Chain.
 *
 * Exercises: KalmanFilter → GPSBufferManager → ArUtils.convertGpsToArPosition.
 *
 * Test Plan Section 5.2.4 chain 2 / Table 20: edge cases handled, behaviors
 * observable via primitive-state proxies.
 *
 * KEY LIMITATIONS (per read-only audit, JVM):
 *   - android.location.Location setters are no-ops under returnDefaultValues=true.
 *     KalmanFilter.filter() and GPSBufferManager.calculateWeightedAverage() both
 *     build output Locations via that pattern, so their lat/lng cannot be asserted
 *     in JVM. Use primitive-return proxies (getApproximateGain, getState,
 *     getSampleCount).
 *   - GPSBufferManager.isClusterTight() also uses the stub pattern internally,
 *     so the buffer never reaches State.READY in JVM. Tests target COLLECTING
 *     and REJECTED transitions; READY-transition assertions deferred to androidTest.
 *   - KalmanFilter.MAX_TIME_GAP = 10.0 seconds (private const val, line 50).
 *   - KalmanFilter.MAX_JUMP_DISTANCE = 50.0 m (private const val, line 44). The
 *     outlier branch (lines 117-128) is a SOFT REJECTION: returns the prior
 *     filtered location without resetting and without updating internal state.
 *
 * MockK helper duplicated from KalmanFilterUnitTest.kt per existing convention
 * (no shared testutils/ folder).
 */
class GpsProcessingIntegrationTest {

    private fun mockLocation(
        lat: Double, lng: Double,
        accuracy: Float = 5.0f,
        timeMs: Long = 1_700_000_000_000L
    ): Location {
        val loc = mockk<Location>(relaxed = true)
        every { loc.latitude } returns lat
        every { loc.longitude } returns lng
        every { loc.accuracy } returns accuracy
        every { loc.time } returns timeMs
        every { loc.hasSpeed() } returns false
        return loc
    }

    // Test Plan Section 5.2.4 — Kalman variance reduction proxy via getApproximateGain
    @Test
    fun `kalman gain decreases over a sequence of consistent samples`() {
        val kf = KalmanFilter()
        val baseLat = 35.2490
        val baseLng = 33.0240
        val baseTime = 1_700_000_000_000L

        val gains = mutableListOf<Double>()
        for (i in 0 until 10) {
            val noisyLat = baseLat + (i % 3) * 1e-6
            val noisyLng = baseLng + (i % 4) * 1e-6
            kf.filter(mockLocation(noisyLat, noisyLng, timeMs = baseTime + i * 1000L))
            gains += kf.getApproximateGain()
        }

        assertTrue(
            "Kalman gain should decrease over consistent samples; got ${gains.first()} → ${gains.last()}",
            gains.last() < gains.first()
        )
    }

    // Test Plan Section 5.2.4 — Kalman handles a 51m+ jump without diverging
    @Test
    fun `kalman handles GPS jump above MAX_JUMP_DISTANCE without crash`() {
        val kf = KalmanFilter()
        val baseLat = 35.2490
        val baseLng = 33.0240
        val baseTime = 1_700_000_000_000L

        for (i in 0 until 5) {
            kf.filter(mockLocation(baseLat, baseLng, timeMs = baseTime + i * 1000L))
        }

        // 0.0005° lat ≈ 55m at this latitude (above MAX_JUMP_DISTANCE = 50m).
        // Soft rejection: filter returns prior filtered location, stays initialized.
        kf.filter(mockLocation(baseLat + 0.0005, baseLng, timeMs = baseTime + 5000L))

        assertTrue("Filter should remain initialized after GPS jump", kf.isInitialized())
        assertTrue(
            "Gain should be a finite value in [0,1] after jump; got ${kf.getApproximateGain()}",
            kf.getApproximateGain() in 0.0..1.0
        )
    }

    /**
     * Test Plan Section 5.2.4 — Kalman time-gap reset (MAX_TIME_GAP = 10s).
     *
     * NOTE on timestamp arithmetic: the seeding loop sets lastUpdateTime to
     * baseTime + 4000 (i goes 0..4, last sample at i=4). The follow-up sample
     * therefore needs timeMs >= baseTime + 14_001L for dt to exceed
     * MAX_TIME_GAP=10s. Using baseTime + 20_000L (dt = 16s) for clean margin.
     *
     * After the gap-triggered reset, the filter re-initializes with
     * varianceLat = (accuracy/111000)² ≈ 2e-9, producing gain ≈ 0.5 — strictly
     * larger than the converged gain after 5 consistent updates (~0.17).
     */
    @Test
    fun `kalman re-initializes when time gap exceeds MAX_TIME_GAP`() {
        val kf = KalmanFilter()
        val baseLat = 35.2490
        val baseLng = 33.0240
        val baseTime = 1_700_000_000_000L

        for (i in 0 until 5) {
            kf.filter(mockLocation(baseLat, baseLng, timeMs = baseTime + i * 1000L))
        }
        val gainBeforeGap = kf.getApproximateGain()
        assertTrue("Gain should drop below 1.0 after 5 samples", gainBeforeGap < 1.0)

        // dt = (20000 - 4000) / 1000 = 16s, unambiguously above MAX_TIME_GAP=10s
        kf.filter(mockLocation(baseLat, baseLng, timeMs = baseTime + 20_000L))

        val gainAfterReset = kf.getApproximateGain()
        assertTrue(
            "Gain should reset toward 1.0 after time-gap reset; got $gainAfterReset (was $gainBeforeGap)",
            gainAfterReset > gainBeforeGap
        )
    }

    // Test Plan Section 5.2.4 — GPSBufferManager state progression COLLECTING
    @Test
    fun `bufferManager stays in COLLECTING with fewer than required samples`() {
        val buffer = GPSBufferManager()  // requiredSampleCount=8 default

        for (i in 0 until 7) {
            buffer.addSample(mockLocation(35.2490, 33.0240 + i * 1e-6, accuracy = 5f))
        }

        assertEquals(GPSBufferManager.State.COLLECTING, buffer.getState())
        assertEquals(7, buffer.getSampleCount())
    }

    // Test Plan Section 5.2.4 — bufferManager rejects samples above accuracy threshold
    @Test
    fun `bufferManager rejects samples above accuracy threshold`() {
        val buffer = GPSBufferManager()
        buffer.addSample(mockLocation(35.2490, 33.0240, accuracy = 12f))

        assertEquals(GPSBufferManager.State.COLLECTING, buffer.getState())
        assertEquals(0, buffer.getSampleCount())
    }

    // Test Plan Section 5.2.4 — bufferManager weighted average non-null after samples
    // (Cannot assert lat/lng numerically due to Location setter stub limitation.)
    @Test
    fun `bufferManager calculateWeightedAverage returns non-null after samples`() {
        val buffer = GPSBufferManager()

        for (i in 0 until 5) {
            buffer.addSample(mockLocation(35.2490, 33.0240 + i * 1e-6, accuracy = 5f))
        }

        val avg = buffer.calculateWeightedAverage()
        assertNotNull("Weighted average should be non-null after samples", avg)
    }

    // Test Plan Section 5.2.4 — convertGpsToArPosition produces consistent AR coords
    // for known input (chain endpoint; Kalman/Buffer outputs cannot be chained
    // numerically due to Location setter stub limitation).
    @Test
    fun `convertGpsToArPosition produces expected coordinates for known input`() {
        // User at origin, target ~111m due north, yaw offset 0°
        // Expected: AR convention -Z = forward → x≈0, z≈-111
        val user = mockLocation(35.2490, 33.0240)
        val targetLat = 35.2500
        val targetLng = 33.0240
        val yawOffsetDeg = 0.0

        val (x, z) = ArUtils.convertGpsToArPosition(user, targetLat, targetLng, yawOffsetDeg)

        assertTrue("X should be near 0 for due-north target; got $x", Math.abs(x) < 5f)
        assertTrue("Z should be near -111m for due-north target; got $z", z in -120f..-100f)
    }
}
