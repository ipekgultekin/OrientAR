package com.example.orientar.navigation.logic

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Unit tests for GeoProjection — pure-math point-to-segment / polyline projection
 * and adaptive snap tolerance functions used by the phantom routing pipeline (SCRUM-56).
 *
 * Test Plan Section 5.1.4 — pure math, ±0.01 tolerance.
 *
 * All reference values verified against documented behavior in GeoProjection.kt
 * before assertions written.
 */
class GeoProjectionUnitTest {

    // ========================================================================
    // CONSTANTS — regression guards
    // Tests below freeze the contract: if anyone changes a constant, the test
    // breaks and forces conscious review.
    // ========================================================================

    @Test
    fun `R_SNAP_BASE_M is 30 meters`() {
        assertEquals(30.0, GeoProjection.R_SNAP_BASE_M, 0.001)
    }

    @Test
    fun `R_SNAP_CAP_M is 75 meters`() {
        assertEquals(75.0, GeoProjection.R_SNAP_CAP_M, 0.001)
    }

    @Test
    fun `R_REFUSE_M is 75 meters`() {
        assertEquals(75.0, GeoProjection.R_REFUSE_M, 0.001)
    }

    @Test
    fun `R_NODE_BASE_M is 5 meters`() {
        assertEquals(5.0, GeoProjection.R_NODE_BASE_M, 0.001)
    }

    @Test
    fun `MAX_ACCURACY_M is 30 meters`() {
        assertEquals(30.0f, GeoProjection.MAX_ACCURACY_M, 0.001f)
    }

    // ========================================================================
    // rNode(accuracy) — adaptive node-snap radius
    // Formula: max(5.0, 1.5 * accuracy)
    // ========================================================================

    @Test
    fun `rNode at low accuracy returns base 5 meters`() {
        // accuracy 2m → max(5.0, 3.0) = 5.0
        val result = GeoProjection.rNode(2.0f)
        assertEquals(5.0, result, 0.01)
    }

    @Test
    fun `rNode at 10m accuracy returns 15 meters`() {
        // accuracy 10m → max(5.0, 15.0) = 15.0
        val result = GeoProjection.rNode(10.0f)
        assertEquals(15.0, result, 0.01)
    }

    @Test
    fun `rNode at 20m accuracy returns 30 meters`() {
        // accuracy 20m → max(5.0, 30.0) = 30.0
        val result = GeoProjection.rNode(20.0f)
        assertEquals(30.0, result, 0.01)
    }

    // ========================================================================
    // rSnap(accuracy) — adaptive edge-snap radius
    // Formula: clamp(2.5 * accuracy, 30.0, 75.0)
    // ========================================================================

    @Test
    fun `rSnap at low accuracy clamps to base 30 meters`() {
        // accuracy 5m → 12.5, clamped up to 30.0
        val result = GeoProjection.rSnap(5.0f)
        assertEquals(30.0, result, 0.01)
    }

    @Test
    fun `rSnap at 12m accuracy still clamped to 30 meters`() {
        // accuracy 12m → 30.0, exactly at base
        val result = GeoProjection.rSnap(12.0f)
        assertEquals(30.0, result, 0.01)
    }

    @Test
    fun `rSnap at 20m accuracy returns 50 meters`() {
        // accuracy 20m → 50.0 (between base and cap)
        val result = GeoProjection.rSnap(20.0f)
        assertEquals(50.0, result, 0.01)
    }

    @Test
    fun `rSnap at 30m accuracy clamps to cap 75 meters`() {
        // accuracy 30m → 75.0 (at cap)
        val result = GeoProjection.rSnap(30.0f)
        assertEquals(75.0, result, 0.01)
    }

    @Test
    fun `rSnap at 50m accuracy clamps to cap 75 meters`() {
        // accuracy 50m → 125.0 raw, clamped to 75.0 cap
        val result = GeoProjection.rSnap(50.0f)
        assertEquals(75.0, result, 0.01)
    }
}
