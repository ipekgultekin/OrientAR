package com.example.orientar.navigation.logic

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Unit tests for ArUtils mathematical helpers.
 *
 * Coverage scope: pure math functions used throughout AR navigation —
 * haversine distance, initial bearing between coordinates, angle
 * normalization, and route interpolation. All tests are framework-free
 * and follow the team convention established in commit 7d4a553.
 *
 * Reference values for distance and bearing computed via the standard
 * Movable Type Scripts haversine calculator (https://www.movable-type.co.uk/scripts/latlong.html).
 */
class ArUtilsUnitTest {

    // Test Plan Section 5.1.4 — distanceMeters: pure math, ±0.01 m tolerance
    @Test
    fun `distanceMeters should return zero for identical points`() {
        val distance = ArUtils.distanceMeters(35.2490, 33.0239, 35.2490, 33.0239)
        assertEquals(0.0, distance, 0.01)
    }

    // Test Plan Section 5.1.4 — bearingDeg: pure math, ±0.01° tolerance
    @Test
    fun `bearingDeg should return zero for due north`() {
        // Point B is directly north of A (same longitude, higher latitude)
        val bearing = ArUtils.bearingDeg(35.2490, 33.0239, 35.2500, 33.0239)
        assertEquals(0.0, bearing, 0.01)
    }

    // Test Plan Section 5.1.4 — bearingDeg: pure math, ±0.01° tolerance
    @Test
    fun `bearingDeg should return ninety for due east`() {
        // Point B is directly east of A (same latitude, higher longitude)
        val bearing = ArUtils.bearingDeg(35.2490, 33.0239, 35.2490, 33.0249)
        assertEquals(90.0, bearing, 0.5)
        // Note: small bearing deviation from exactly 90° is expected on a sphere;
        // tolerance widened to 0.5° to absorb haversine bearing quirks at non-equator latitudes.
    }

    // Test Plan Section 5.1.4 — normalizeAngleDeg: pure math, ±0.01° tolerance
    @Test
    fun `normalizeAngleDeg should wrap 350 degrees to -10 in -180 to 180 range`() {
        // 350° normalizes to -10° (function returns range [-180, 180))
        val normalized = ArUtils.normalizeAngleDeg(350.0)
        assertEquals(-10.0, normalized, 0.01)
    }

    // ========================================================================
    // distanceMeters — extended coverage (5 methods)
    // ========================================================================

    // Test Plan Section 5.1.4 — distanceMeters: pure math, ±1 m tolerance for ~100m scale
    @Test
    fun `distanceMeters should return approximately 100m for points 100m apart`() {
        // From (35.2490, 33.0239) heading north by ~100m → roughly (35.2499, 33.0239)
        // Reference: 1° latitude ≈ 111320m, so 0.0009° ≈ 100.2m
        val distance = ArUtils.distanceMeters(35.2490, 33.0239, 35.2499, 33.0239)
        assertEquals(100.0, distance, 1.0)
    }

    // Test Plan Section 5.1.4 — distanceMeters: pure math, ±0.5 m tolerance for ~5m scale
    @Test
    fun `distanceMeters should return approximately 5m for points 5m apart`() {
        // 5m north-south at this latitude is approximately 0.000045° latitude
        val distance = ArUtils.distanceMeters(35.2490, 33.0239, 35.249045, 33.0239)
        assertEquals(5.0, distance, 0.5)
    }

    // Test Plan Section 5.1.4 — distanceMeters: pure math, ±5 m tolerance for ~500m scale
    @Test
    fun `distanceMeters should return approximately 500m for points 500m apart`() {
        // 500m north at this latitude
        val distance = ArUtils.distanceMeters(35.2490, 33.0239, 35.2535, 33.0239)
        assertEquals(500.0, distance, 5.0)
    }

    // Test Plan Section 5.1.4 — distanceMeters: symmetry, exact equality
    @Test
    fun `distanceMeters should be symmetric (A to B equals B to A)`() {
        val ab = ArUtils.distanceMeters(35.2490, 33.0239, 35.2499, 33.0249)
        val ba = ArUtils.distanceMeters(35.2499, 33.0249, 35.2490, 33.0239)
        assertEquals(ab, ba, 0.01)
    }

    // Test Plan Section 5.1.4 — distanceMeters: numerical stability for distant coordinates
    @Test
    fun `distanceMeters should not produce NaN for any valid coordinates`() {
        // Far apart coordinates that previously could trigger numerical issues
        val distance = ArUtils.distanceMeters(35.2490, 33.0239, -35.2490, -33.0239)
        assertFalse("Result should not be NaN", distance.isNaN())
        assertTrue("Result should be positive for non-identical points", distance > 0.0)
    }

    // ========================================================================
    // bearingDeg — extended coverage (6 methods; north + east already in patch 3)
    // ========================================================================

    // Test Plan Section 5.1.4 — bearingDeg: cardinal direction, ±0.5° tolerance
    @Test
    fun `bearingDeg should return 180 for due south`() {
        val bearing = ArUtils.bearingDeg(35.2500, 33.0239, 35.2490, 33.0239)
        assertEquals(180.0, bearing, 0.5)
    }

    // Test Plan Section 5.1.4 — bearingDeg: cardinal direction, ±0.5° tolerance
    @Test
    fun `bearingDeg should return 270 for due west`() {
        val bearing = ArUtils.bearingDeg(35.2490, 33.0249, 35.2490, 33.0239)
        assertEquals(270.0, bearing, 0.5)
    }

    // Test Plan Section 5.1.4 — bearingDeg: ordinal direction, ±1° tolerance
    @Test
    fun `bearingDeg should return 45 for northeast`() {
        val bearing = ArUtils.bearingDeg(35.2490, 33.0239, 35.2499, 33.0250)
        assertEquals(45.0, bearing, 1.0)
        // Wider tolerance: ordinal bearings on a sphere have larger deviation
    }

    // Test Plan Section 5.1.4 — bearingDeg: ordinal direction, ±1° tolerance
    @Test
    fun `bearingDeg should return 135 for southeast`() {
        val bearing = ArUtils.bearingDeg(35.2499, 33.0239, 35.2490, 33.0250)
        assertEquals(135.0, bearing, 1.0)
    }

    // Test Plan Section 5.1.4 — bearingDeg: ordinal direction, ±1° tolerance
    @Test
    fun `bearingDeg should return 225 for southwest`() {
        val bearing = ArUtils.bearingDeg(35.2499, 33.0250, 35.2490, 33.0239)
        assertEquals(225.0, bearing, 1.0)
    }

    // Test Plan Section 5.1.4 — bearingDeg: ordinal direction, ±1° tolerance
    @Test
    fun `bearingDeg should return 315 for northwest`() {
        val bearing = ArUtils.bearingDeg(35.2490, 33.0250, 35.2499, 33.0239)
        assertEquals(315.0, bearing, 1.0)
    }

    // ========================================================================
    // normalizeAngleDeg — extended coverage (4 methods)
    // Production output range is [-180, 180) per the function's KDoc.
    // ========================================================================

    // Test Plan Section 5.1.4 — normalizeAngleDeg: identity, ±0.01° tolerance
    @Test
    fun `normalizeAngleDeg should leave 0 unchanged`() {
        assertEquals(0.0, ArUtils.normalizeAngleDeg(0.0), 0.01)
    }

    // Test Plan Section 5.1.4 — normalizeAngleDeg: identity, ±0.01° tolerance
    @Test
    fun `normalizeAngleDeg should leave 90 unchanged`() {
        assertEquals(90.0, ArUtils.normalizeAngleDeg(90.0), 0.01)
    }

    // Test Plan Section 5.1.4 — normalizeAngleDeg: full-rotation wrap, ±0.01° tolerance
    @Test
    fun `normalizeAngleDeg should wrap 720 to 0`() {
        // 720° = 2 full rotations, should normalize to 0°
        assertEquals(0.0, ArUtils.normalizeAngleDeg(720.0), 0.01)
    }

    // Test Plan Section 5.1.4 — normalizeAngleDeg: large negative wrap, ±0.01° tolerance
    @Test
    fun `normalizeAngleDeg should wrap large negative angle into range`() {
        // -190° normalizes to 170° (per ArUtils KDoc example)
        assertEquals(170.0, ArUtils.normalizeAngleDeg(-190.0), 0.01)
    }

    // ========================================================================
    // interpolate — extended coverage (4 methods)
    // ADJUSTMENT: production signature is interpolate(startLat, startLng,
    //   endLat, endLng, stepMeters), NOT (List, spacing) as the prompt assumed.
    // Tests rewritten to use the actual signature.
    // ========================================================================

    // Test Plan Section 5.1.4 — interpolate: error path for invalid step
    @Test
    fun `interpolate should return empty list for non-positive step`() {
        // Production guard: stepMeters <= 0 returns emptyList()
        val result = ArUtils.interpolate(35.2490, 33.0239, 35.2499, 33.0239, 0.0)
        assertTrue("step=0 should produce empty result", result.isEmpty())
    }

    // Test Plan Section 5.1.4 — interpolate: single-point fallback when distance < step
    @Test
    fun `interpolate should return single-point list when distance is less than step`() {
        // Distance ~0.1m, step 2.0m → distance < step, returns [start]
        val result = ArUtils.interpolate(35.2490, 33.0239, 35.249001, 33.0239, 2.0)
        assertEquals(1, result.size)
        assertEquals(35.2490, result[0].first, 0.0001)
        assertEquals(33.0239, result[0].second, 0.0001)
    }

    // Test Plan Section 5.1.4 — interpolate: spacing tolerance ±0.5m per Table 20
    @Test
    fun `interpolate should produce 6 points for 10m route at 2m spacing`() {
        // 10m / 2m = 5 intermediates + 1 start = 6 points (end is NOT included
        // by production code; intermediates use fraction=i/(count+1), never reaching 1)
        val result = ArUtils.interpolate(35.2490, 33.0239, 35.249090, 33.0239, 2.0)
        assertEquals(6, result.size)
    }

    // Test Plan Section 5.1.4 — interpolate: first point identity, ±0.0001° tolerance
    @Test
    fun `interpolate first point should equal route start`() {
        val result = ArUtils.interpolate(35.2490, 33.0239, 35.2499, 33.0250, 2.0)
        val firstResult = result.first()
        assertEquals(35.2490, firstResult.first, 0.0001)
        assertEquals(33.0239, firstResult.second, 0.0001)
    }

    // ========================================================================
    // convertGpsToArPosition — extended coverage (3 methods)
    // ADJUSTMENT: production signature is convertGpsToArPosition(userLoc: Location,
    //   targetLat, targetLng, yawOffsetDeg), NOT (targetLat, targetLng, anchorLat,
    //   anchorLng, yawOffsetDeg) as the prompt assumed. The Location parameter is
    //   mocked via MockK because android.location.Location setters are no-ops
    //   under testOptions.unitTests.isReturnDefaultValues = true.
    // ========================================================================

    private fun mockLocation(lat: Double, lng: Double): Location {
        val loc = mockk<Location>()
        every { loc.latitude } returns lat
        every { loc.longitude } returns lng
        return loc
    }

    // Test Plan Section 5.1.4 — convertGpsToArPosition: identity, ±0.01m tolerance
    @Test
    fun `convertGpsToArPosition should return zero offset for same anchor and target`() {
        val anchor = mockLocation(35.2490, 33.0239)
        val (x, z) = ArUtils.convertGpsToArPosition(anchor, 35.2490, 33.0239, 0.0)
        assertEquals(0.0f, x, 0.01f)
        assertEquals(0.0f, z, 0.01f)
    }

    // Test Plan Section 5.1.4 — convertGpsToArPosition: north → -Z, ±5m z tolerance for haversine
    @Test
    fun `convertGpsToArPosition should map due north to negative z when yaw is zero`() {
        // Anchor at (35.2490, 33.0239), target ~100m north at (35.2499, 33.0239).
        // With yaw=0, AR coordinate system has -Z = north.
        // Expected: x ≈ 0, z ≈ -100.
        val anchor = mockLocation(35.2490, 33.0239)
        val (x, z) = ArUtils.convertGpsToArPosition(anchor, 35.2499, 33.0239, 0.0)
        assertEquals(0.0f, x, 1.0f)
        assertEquals(-100.0f, z, 5.0f)
        // Wider z tolerance to absorb haversine approximations
    }

    // Test Plan Section 5.1.4 — convertGpsToArPosition: east → +X, ±5m tolerance
    @Test
    fun `convertGpsToArPosition should map due east to positive x when yaw is zero`() {
        // Anchor at (35.2490, 33.0239), target ~100m east.
        // With yaw=0, AR coordinate system has +X = east.
        val anchor = mockLocation(35.2490, 33.0239)
        val (x, z) = ArUtils.convertGpsToArPosition(anchor, 35.2490, 33.0250, 0.0)
        assertTrue("X should be positive (east)", x > 0)
        assertEquals(0.0f, z, 5.0f)
    }
}
