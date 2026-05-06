package com.example.orientar.navigation.integration

import com.example.orientar.navigation.logic.ArUtils
import com.example.orientar.navigation.logic.CampusGraph
import com.example.orientar.navigation.logic.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration test for the AR Navigation Route Data Pipeline.
 *
 * Exercises: GeoJSON load → A* findPath → boundary dedup (almostSame at
 * CampusGraph.kt:306, eps=1e-10) → ArUtils.interpolate.
 *
 * Test Plan Section 5.2.4 chain 1 / Table 20:
 *   - Waypoint count ±1 from expected
 *   - Spacing tolerance ±0.5m from target stepMeters
 *   - Edge cases handled gracefully (disconnected, same start=end, invalid)
 *
 * KEY BEHAVIOR (per read-only audit):
 *   - ArUtils.interpolate output spacing = dist/(count+1) where
 *     count = floor(dist/stepMeters); always slightly less than stepMeters.
 *     For long segments (dist >> stepMeters), spacing approaches stepMeters.
 *   - ArUtils.interpolate INCLUDES the start point but EXCLUDES the end
 *     point. Final result fraction = count/(count+1) < 1.0.
 *   - CampusGraph dedup is boundary-only at 1e-10 epsilon (exact equality
 *     in practice, ~11µm at GPS scale). The Test Plan's 1m-threshold dedup
 *     lives in SphereRefresher.initialize, downstream of this chain — out
 *     of scope here, exercised by system tests.
 *   - findPath edge cases all return emptyList() without throwing.
 *
 * Synthetic 8-node L-shaped graph used instead of production map.geojson.
 * Each adjacent LineString shares its endpoint coordinate with the next
 * (e.g. [33.0243, 35.2490] ends edge 1 and starts edge 2), so the
 * boundary-dedup branch at CampusGraph.kt:306 IS exercised by Test 4.
 */
class RoutePipelineIntegrationTest {

    companion object {
        /**
         * 8-node L-shaped graph at METU NCC area.
         *
         *   N0──N1──N2──N3──N4
         *                   │
         *                  N5
         *                   │
         *                  N6──N7
         *
         * Node IDs are auto-numbered 0..7 by parse order (production ignores
         * any id property in the JSON). All marked Type="Destination" for
         * simplicity. Coordinates spaced ~30m apart so interpolate segments
         * are long enough that spacing ≈ stepMeters within ±0.5m tolerance.
         *
         * Adjacent LineStrings share endpoint coordinates exactly (byte-equal
         * Doubles), exercising almostSame's boundary dedup branch.
         */
        private val L_SHAPED_GRAPH_JSON = """
        {
          "type": "FeatureCollection",
          "features": [
            { "type": "Feature", "properties": {"Type": "Destination", "Name": "N0"},
              "geometry": {"type": "Point", "coordinates": [33.0240, 35.2490]}},
            { "type": "Feature", "properties": {"Type": "Destination", "Name": "N1"},
              "geometry": {"type": "Point", "coordinates": [33.0243, 35.2490]}},
            { "type": "Feature", "properties": {"Type": "Destination", "Name": "N2"},
              "geometry": {"type": "Point", "coordinates": [33.0246, 35.2490]}},
            { "type": "Feature", "properties": {"Type": "Destination", "Name": "N3"},
              "geometry": {"type": "Point", "coordinates": [33.0249, 35.2490]}},
            { "type": "Feature", "properties": {"Type": "Destination", "Name": "N4"},
              "geometry": {"type": "Point", "coordinates": [33.0252, 35.2490]}},
            { "type": "Feature", "properties": {"Type": "Destination", "Name": "N5"},
              "geometry": {"type": "Point", "coordinates": [33.0252, 35.2487]}},
            { "type": "Feature", "properties": {"Type": "Destination", "Name": "N6"},
              "geometry": {"type": "Point", "coordinates": [33.0252, 35.2484]}},
            { "type": "Feature", "properties": {"Type": "Destination", "Name": "N7"},
              "geometry": {"type": "Point", "coordinates": [33.0255, 35.2484]}},

            { "type": "Feature", "properties": {},
              "geometry": {"type": "LineString", "coordinates": [[33.0240, 35.2490], [33.0243, 35.2490]]}},
            { "type": "Feature", "properties": {},
              "geometry": {"type": "LineString", "coordinates": [[33.0243, 35.2490], [33.0246, 35.2490]]}},
            { "type": "Feature", "properties": {},
              "geometry": {"type": "LineString", "coordinates": [[33.0246, 35.2490], [33.0249, 35.2490]]}},
            { "type": "Feature", "properties": {},
              "geometry": {"type": "LineString", "coordinates": [[33.0249, 35.2490], [33.0252, 35.2490]]}},
            { "type": "Feature", "properties": {},
              "geometry": {"type": "LineString", "coordinates": [[33.0252, 35.2490], [33.0252, 35.2487]]}},
            { "type": "Feature", "properties": {},
              "geometry": {"type": "LineString", "coordinates": [[33.0252, 35.2487], [33.0252, 35.2484]]}},
            { "type": "Feature", "properties": {},
              "geometry": {"type": "LineString", "coordinates": [[33.0252, 35.2484], [33.0255, 35.2484]]}}
          ]
        }
        """.trimIndent()
    }

    // Test Plan Section 5.2.4 chain 1 — full pipeline produces a usable polyline
    @Test
    fun `pipeline produces non-empty path for valid start and end`() {
        val graph = CampusGraph.fromGeoJsonString(L_SHAPED_GRAPH_JSON)
        val path = graph.findPath(0, 4)  // N0 → N4, straight east ~120m

        assertTrue("Pipeline should produce non-empty path", path.isNotEmpty())
        assertTrue("Path should have at least 5 coordinates, got ${path.size}", path.size >= 5)
    }

    /**
     * Test Plan Section 5.2.4 / Table 20 — interpolated spacing within ±0.5m of stepMeters.
     *
     * NOTE on interpolate's actual behavior (per audit, ArUtils.kt:164-176):
     *   spacing = dist / (count + 1) where count = floor(dist / stepMeters).
     *   Always slightly less than stepMeters; approaches stepMeters as dist grows.
     *
     * For N0→N4 path (first to last ≈ 120m) and stepMeters=2.0:
     *   count = floor(120/2) = 60
     *   spacing = 120 / 61 ≈ 1.967m
     *   Falls within stepMeters ±0.5m band [1.5, 2.5]. ✓
     *
     * If this test ever changes to use a shorter segment, the band must widen
     * accordingly (or the segment must be made longer to keep the ratio
     * count/(count+1) close to 1.0).
     */
    @Test
    fun `interpolated path spacing is within tolerance of stepMeters`() {
        val graph = CampusGraph.fromGeoJsonString(L_SHAPED_GRAPH_JSON)
        val path = graph.findPath(0, 4)

        val first = path.first()
        val last = path.last()
        val segmentDist = ArUtils.distanceMeters(first.lat, first.lng, last.lat, last.lng)

        // Defensive precondition: segment must be long enough for spacing to
        // land near stepMeters. dist/stepMeters >= 5 keeps spacing within
        // ~17% of stepMeters (count/(count+1) >= 5/6 for count >= 5).
        assertTrue(
            "Test requires segmentDist >= 10m for stepMeters=2.0; got ${segmentDist}m",
            segmentDist >= 10.0
        )

        val stepMeters = 2.0
        val interpolated = ArUtils.interpolate(first.lat, first.lng, last.lat, last.lng, stepMeters)

        for (i in 0 until interpolated.size - 1) {
            val a = interpolated[i]
            val b = interpolated[i + 1]
            val gap = ArUtils.distanceMeters(a.first, a.second, b.first, b.second)
            assertTrue(
                "Interpolation spacing at index $i was ${gap}m; expected within ±0.5m of stepMeters=2.0 (actual formula: dist/(count+1))",
                gap in 1.5..2.5
            )
        }
    }

    // Test Plan Section 5.2.4 chain 1 — diagonal traversal across the L-bend
    @Test
    fun `pipeline routes through L-bend for diagonal destinations`() {
        val graph = CampusGraph.fromGeoJsonString(L_SHAPED_GRAPH_JSON)
        val path = graph.findPath(0, 7)  // N0 → N7 via N1..N5..N6

        assertTrue("Diagonal route should be non-empty", path.isNotEmpty())
        assertTrue(
            "L-shaped diagonal route should have at least 8 coordinates, got ${path.size}",
            path.size >= 8
        )
    }

    /**
     * Test Plan Section 5.2.4 chain 1 — boundary dedup behavior.
     *
     * Asserts that findPath output has no consecutive duplicate coordinates
     * (exact equality). This verifies CampusGraph.almostSame at line 306
     * (eps=1e-10), which fires when stitching adjacent edge geometries that
     * share endpoint coordinates.
     *
     * SCOPE: This tests the boundary-only 1e-10 dedup in CampusGraph's
     * findPath reconstruction. The 1m-threshold dedup specified in Test Plan
     * Section 3.4 ("consecutive points closer than 1m") lives in
     * SphereRefresher.initialize at lines 70-88, downstream of this chain.
     * That layer is out of scope here; it's exercised by system testing
     * (SCRUM-86) on real device walks. This separation is a finding for
     * the SCRUM-88 report.
     */
    @Test
    fun `pipeline output has no consecutive duplicate coordinates`() {
        val graph = CampusGraph.fromGeoJsonString(L_SHAPED_GRAPH_JSON)
        val path = graph.findPath(0, 7)  // diagonal — multiple edge stitches

        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val identical = a.lat == b.lat && a.lng == b.lng
            assertTrue(
                "Path has consecutive duplicate coords at index $i: ($a) and ($b)",
                !identical
            )
        }
    }

    // Test Plan Section 5.2.4 chain 1 — edge case: same start and end
    @Test
    fun `pipeline returns empty list for identical start and end`() {
        val graph = CampusGraph.fromGeoJsonString(L_SHAPED_GRAPH_JSON)
        val path = graph.findPath(0, 0)
        assertTrue("Same-node path should be empty, got ${path.size}", path.isEmpty())
    }

    // Test Plan Section 5.2.4 chain 1 — edge case: invalid node id
    @Test
    fun `pipeline returns empty list for invalid start node`() {
        val graph = CampusGraph.fromGeoJsonString(L_SHAPED_GRAPH_JSON)
        val path = graph.findPath(999, 0)
        assertTrue("Invalid-start path should be empty, got ${path.size}", path.isEmpty())
    }
}
