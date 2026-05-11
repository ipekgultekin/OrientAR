package com.example.orientar.navigation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for SCRUM-120 final-checkpoint completion bug.
 *
 * BUG (preserved in RouteProgressTracker pending Step 3 fix):
 *   The gate `if (bestIdx != lastClosestRouteIndex)` inside
 *   [RouteProgressTracker.updateProgress] suppresses the visitation check
 *   after `lastClosestRouteIndex` saturates at the route's last index. On
 *   long routes where the user reaches the destination AFTER the index has
 *   already saturated, the arrival lambda never fires. Walk5 of the
 *   SCRUM-86 field-test set (Noshi Cafe → A2 Door, 980m) exposed this:
 *   user GPS came within 5m of A2 Door at four distinct moments in the
 *   final 130 seconds, yet zero `ARRIVED` log entries appear.
 *
 * TEST STRATEGY:
 *   Test 1 drives a GPS sequence that:
 *     (a) advances `lastClosestRouteIndex` to saturation while user is
 *         OUTSIDE the 5m arrival threshold, then
 *     (b) places the user INSIDE the 5m threshold of the destination
 *         WITHOUT changing `lastClosestRouteIndex`.
 *   The bug causes the gate to fail in step (b), so the arrival lambda
 *   is never invoked. Assertion `arrivedNodes.size == 1` fails on the
 *   current code — that failure IS the regression-captured bug.
 *
 *   Test 2 sanity-checks the happy path (user reaches destination during
 *   normal index advance). Passes on both pre- and post-fix code.
 *
 *   Test 3 sanity-checks intermediate-checkpoint dispatch (non-destination
 *   node within threshold fires `onCheckpoint`, not `onArrival`).
 *
 * AFTER STEP 3:
 *   When the gate is removed (or replaced with one that always allows the
 *   visitation check to run), Test 1 will pass and become the permanent
 *   regression guard against the bug recurring.
 *
 * Test fixtures use a synthetic 20-waypoint meridian route at the METU NCC
 * area. Waypoints are spaced ~1m apart along increasing latitude with
 * constant longitude. The destination node D is positioned at the
 * coordinates of waypoint 19 (the route's last index).
 */
class RouteProgressTrackerUnitTest {

    companion object {
        // ~1m of latitude at lat 35°. (1 / 111000 = ~9.009e-6).
        private const val ONE_METER_LAT = 0.0000090
        // ~1m of longitude at lat 35.25°. (1 / (111000 * cos(35.25°)) = ~1.10e-5).
        private const val ONE_METER_LNG = 0.0000110

        private const val BASE_LAT = 35.2490
        private const val BASE_LNG = 33.0240
        private const val ROUTE_WAYPOINT_COUNT = 20  // indices 0..19

        /** Construct the 20-waypoint synthetic route. Waypoint i at (BASE_LAT + i*ONE_METER_LAT, BASE_LNG). */
        private fun buildRoute(): List<Coordinate> {
            return (0 until ROUTE_WAYPOINT_COUNT).map { i ->
                Coordinate(BASE_LAT + i * ONE_METER_LAT, BASE_LNG)
            }
        }

        /** Destination node D positioned at the coordinates of waypoint 19 (last index). */
        private val DESTINATION_NODE = Node(
            id = 99,
            lat = BASE_LAT + (ROUTE_WAYPOINT_COUNT - 1) * ONE_METER_LAT,
            lng = BASE_LNG,
            type = "Destination",
            name = "D"
        )
    }

    /**
     * Test 1 — The bug. Asserts post-fix correct behavior.
     *
     * On current Arda_branch (gate preserved): FAILS. arrivedNodes is empty.
     * After Step 3 (gate removed): PASSES. arrivedNodes contains D.
     */
    @Test
    fun arrivedFires_whenUserReachesDestinationAfterIndexSaturation() {
        val route = buildRoute()
        val arrivedNodes = mutableListOf<Node>()
        val checkpointNodes = mutableListOf<Node>()

        val tracker = RouteProgressTracker(
            routeCoords = route,
            routeNodePath = listOf(DESTINATION_NODE),
            selectedEndNode = DESTINATION_NODE,
            arrivalThreshold = 5.0f,
            onArrival = { node -> arrivedNodes.add(node) },
            onCheckpoint = { node -> checkpointNodes.add(node) }
        )

        // ----- Approach: advance index through waypoints 5, 10, 13. -----
        // At each advance the gate fires and markVisitedNodes runs. Distance
        // from user (at waypoint N) to D (at waypoint 19) = (19-N) meters,
        // all > 5m for N <= 13. No arrival yet.
        tracker.updateProgress(BASE_LAT + 5 * ONE_METER_LAT, BASE_LNG)
        tracker.updateProgress(BASE_LAT + 10 * ONE_METER_LAT, BASE_LNG)
        tracker.updateProgress(BASE_LAT + 13 * ONE_METER_LAT, BASE_LNG)
        assertEquals(
            "Sanity: tracker should be at index 13 after approach walk",
            13, tracker.getCurrentIndex()
        )
        assertTrue(
            "Sanity: arrival must NOT fire during approach (D is 6m away)",
            arrivedNodes.isEmpty()
        )

        // ----- Saturating sample: user laterally offset 9m east of D, -----
        //       at same latitude as waypoint 19. Closest waypoint is 19
        //       (saturation). Gate fires (13 → 19). User distance to D = 9m,
        //       above 5m threshold. No arrival.
        val saturatingLat = BASE_LAT + (ROUTE_WAYPOINT_COUNT - 1) * ONE_METER_LAT  // waypoint 19's lat
        val saturatingLng = BASE_LNG + 9 * ONE_METER_LNG                            // 9m east
        tracker.updateProgress(saturatingLat, saturatingLng)
        assertEquals(
            "Sanity: tracker should saturate at index 19 after the overshoot sample",
            19, tracker.getCurrentIndex()
        )
        assertTrue(
            "Sanity: arrival must NOT fire on saturating sample (user is 9m east of D)",
            arrivedNodes.isEmpty()
        )

        // ----- Final approach: user moves back west to 3m east of D. ------
        //       Closest waypoint is still 19 (distance 3m, vs waypoint 18 at
        //       ~3.16m). bestIdx == lastClosestRouteIndex == 19 → gate fails
        //       under the bug → markVisitedNodes never runs → no arrival.
        val finalLat = saturatingLat
        val finalLng = BASE_LNG + 3 * ONE_METER_LNG  // 3m east of D, within 5m threshold
        tracker.updateProgress(finalLat, finalLng)

        // Post-fix expected behavior — these assertions fail on current code
        // because the bug suppresses the visitation check.
        assertEquals(
            "ARRIVED should have fired exactly once when user came within 5m of destination",
            1, arrivedNodes.size
        )
        assertEquals(
            "ARRIVED node must be the destination (D, id=99)",
            DESTINATION_NODE.id, arrivedNodes.first().id
        )
    }

    /**
     * Test 2 — Happy path. Passes on both pre- and post-fix code.
     *
     * User walks through waypoints normally; the destination's 5m radius
     * is entered DURING an index advance, so the gate fires concurrently
     * with the visitation check landing within threshold.
     */
    @Test
    fun arrivedFires_whenUserReachesDestinationDuringNormalAdvance() {
        val route = buildRoute()
        val arrivedNodes = mutableListOf<Node>()
        val checkpointNodes = mutableListOf<Node>()

        val tracker = RouteProgressTracker(
            routeCoords = route,
            routeNodePath = listOf(DESTINATION_NODE),
            selectedEndNode = DESTINATION_NODE,
            arrivalThreshold = 5.0f,
            onArrival = { node -> arrivedNodes.add(node) },
            onCheckpoint = { node -> checkpointNodes.add(node) }
        )

        // Walk through waypoints 5, 10, 14 (5m from D — at threshold, doesn't fire),
        // then 15 (4m from D — fires).
        tracker.updateProgress(BASE_LAT + 5 * ONE_METER_LAT, BASE_LNG)
        tracker.updateProgress(BASE_LAT + 10 * ONE_METER_LAT, BASE_LNG)
        tracker.updateProgress(BASE_LAT + 14 * ONE_METER_LAT, BASE_LNG)
        assertTrue(
            "Arrival must NOT fire at waypoint 14 — distance to D is exactly 5m, not strictly less",
            arrivedNodes.isEmpty()
        )

        tracker.updateProgress(BASE_LAT + 15 * ONE_METER_LAT, BASE_LNG)
        assertEquals(
            "ARRIVED should fire at waypoint 15 (4m from D, inside 5m threshold)",
            1, arrivedNodes.size
        )
        assertEquals(DESTINATION_NODE.id, arrivedNodes.first().id)
    }

    /**
     * Test 3 — Intermediate checkpoint sanity.
     *
     * Confirms that a non-destination node within threshold fires the
     * checkpoint lambda (not the arrival lambda) when the user reaches it
     * during normal index advance.
     */
    @Test
    fun checkpointFires_forIntermediateNodeWithinThreshold() {
        val route = buildRoute()
        val arrivedNodes = mutableListOf<Node>()
        val checkpointNodes = mutableListOf<Node>()

        // Intermediate checkpoint at waypoint 10's coordinates.
        val midNode = Node(
            id = 42,
            lat = BASE_LAT + 10 * ONE_METER_LAT,
            lng = BASE_LNG,
            type = "Destination",
            name = "Mid"
        )

        val tracker = RouteProgressTracker(
            routeCoords = route,
            routeNodePath = listOf(midNode, DESTINATION_NODE),
            selectedEndNode = DESTINATION_NODE,
            arrivalThreshold = 5.0f,
            onArrival = { node -> arrivedNodes.add(node) },
            onCheckpoint = { node -> checkpointNodes.add(node) }
        )

        // Walk to waypoint 5 — both midNode (5m away) and D (14m) outside threshold.
        tracker.updateProgress(BASE_LAT + 5 * ONE_METER_LAT, BASE_LNG)
        assertTrue("Sanity: nothing yet at waypoint 5", checkpointNodes.isEmpty() && arrivedNodes.isEmpty())

        // Walk to waypoint 10 — exactly at midNode coordinates (0m). Inside 5m threshold.
        tracker.updateProgress(BASE_LAT + 10 * ONE_METER_LAT, BASE_LNG)

        assertEquals("midNode should fire onCheckpoint exactly once", 1, checkpointNodes.size)
        assertEquals(midNode.id, checkpointNodes.first().id)
        assertTrue("midNode is NOT the destination; onArrival must not fire", arrivedNodes.isEmpty())
    }
}
