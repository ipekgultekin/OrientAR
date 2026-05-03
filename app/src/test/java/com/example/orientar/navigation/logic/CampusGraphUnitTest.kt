package com.example.orientar.navigation.logic

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Unit tests for CampusGraph routing logic.
 *
 * Covers the [CampusGraph.fromGeoJsonString] factory entry point and A*
 * pathfinding (`findPath`) on a synthetic 4-node square graph. JVM-test
 * compatibility unblocked by `testImplementation("org.json:json:20240303")`
 * which provides a functional `org.json.JSONObject` for the test classpath
 * (the android.jar stub returns no-op defaults under returnDefaultValues=true).
 *
 * Phantom-flow `findPathFromPoint` coverage is scoped for a follow-up patch
 * with coordinate-projection fixtures.
 *
 * GeoJSON schema (verified against production parser):
 *   - Production reads `Type` (capital T) and `Name` (capital N) from
 *     `properties` — lowercase variants are ignored.
 *   - `Type == "Destination"` is required for a node to be added to the
 *     `destinations` list; missing/other values default to "Intersection".
 *   - Production assigns its own internal node IDs (0..N-1) in feature-iteration
 *     order, ignoring any `id` field in the GeoJSON `properties`.
 */
class CampusGraphUnitTest {

    companion object {
        /**
         * Synthetic 4-node connected graph in a square at METU NCC area.
         * All 4 nodes marked as Destinations so they appear in graph.destinations.
         *
         *   N0(A) ────── N1(B)
         *    │            │
         *    │            │
         *   N3(D) ────── N2(C)
         *
         * Production auto-numbers nodes 0..N-1 by parse order, ignoring
         * any "id" property in GeoJSON. So N0 = first Point feature, etc.
         */
        private val SYNTHETIC_GRAPH_JSON = """
            {
              "type": "FeatureCollection",
              "features": [
                { "type": "Feature",
                  "properties": {"Type": "Destination", "Name": "A"},
                  "geometry": {"type": "Point", "coordinates": [33.0240, 35.2490]}},
                { "type": "Feature",
                  "properties": {"Type": "Destination", "Name": "B"},
                  "geometry": {"type": "Point", "coordinates": [33.0241, 35.2490]}},
                { "type": "Feature",
                  "properties": {"Type": "Destination", "Name": "C"},
                  "geometry": {"type": "Point", "coordinates": [33.0241, 35.2489]}},
                { "type": "Feature",
                  "properties": {"Type": "Destination", "Name": "D"},
                  "geometry": {"type": "Point", "coordinates": [33.0240, 35.2489]}},
                { "type": "Feature",
                  "properties": {},
                  "geometry": {"type": "LineString",
                    "coordinates": [[33.0240, 35.2490], [33.0241, 35.2490]]}},
                { "type": "Feature",
                  "properties": {},
                  "geometry": {"type": "LineString",
                    "coordinates": [[33.0241, 35.2490], [33.0241, 35.2489]]}},
                { "type": "Feature",
                  "properties": {},
                  "geometry": {"type": "LineString",
                    "coordinates": [[33.0241, 35.2489], [33.0240, 35.2489]]}},
                { "type": "Feature",
                  "properties": {},
                  "geometry": {"type": "LineString",
                    "coordinates": [[33.0240, 35.2489], [33.0240, 35.2490]]}}
              ]
            }
        """.trimIndent()
    }

    @Test
    fun `fromGeoJsonString builds a graph from minimal valid GeoJSON`() {
        val minimalJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {"id": 1, "name": "A"},
                  "geometry": {"type": "Point", "coordinates": [33.0, 35.2]}
                },
                {
                  "type": "Feature",
                  "properties": {"id": 2, "name": "B"},
                  "geometry": {"type": "Point", "coordinates": [33.001, 35.201]}
                },
                {
                  "type": "Feature",
                  "properties": {},
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [[33.0, 35.2], [33.001, 35.201]]
                  }
                }
              ]
            }
        """.trimIndent()

        val graph = CampusGraph.fromGeoJsonString(minimalJson)
        assertNotNull(graph)
    }

    // ========================================================================
    // A* PATHFINDING — synthetic graph (4-node square cycle)
    // ========================================================================

    // Test Plan Section 5.1.4 — graph load count regression guard
    @Test
    fun `synthetic graph loads exactly 4 nodes`() {
        val graph = CampusGraph.fromGeoJsonString(SYNTHETIC_GRAPH_JSON)
        assertEquals(4, graph.nodes.size)
    }

    // Test Plan Section 5.1.4 — destination filtering by Type property
    @Test
    fun `synthetic graph loads exactly 4 destinations`() {
        val graph = CampusGraph.fromGeoJsonString(SYNTHETIC_GRAPH_JSON)
        assertEquals(4, graph.destinations.size)
    }

    // Test Plan Section 5.1.4 — A* pathfinding correctness for adjacent nodes
    @Test
    fun `findPath returns at least 2 coordinates for adjacent nodes`() {
        val graph = CampusGraph.fromGeoJsonString(SYNTHETIC_GRAPH_JSON)
        // N0 (33.0240, 35.2490) → N1 (33.0241, 35.2490) — direct edge along the top of the square
        val path = graph.findPath(0, 1)
        assertTrue(
            "Adjacent-node path should have at least 2 coordinates, got ${path.size}",
            path.size >= 2
        )
    }

    // Test Plan Section 5.1.4 — A* pathfinding for diagonal traversal
    @Test
    fun `findPath returns non-empty path for diagonal traversal`() {
        val graph = CampusGraph.fromGeoJsonString(SYNTHETIC_GRAPH_JSON)
        // N0 → N2 — must traverse two edges (no direct diagonal edge in the square).
        // A* picks one of the two equal-length L-shaped routes (N0→N1→N2 or N0→N3→N2).
        val path = graph.findPath(0, 2)
        assertTrue("Diagonal path should be non-empty", path.isNotEmpty())
        // Square traversal requires at least 3 coordinates: corner + corner + corner.
        assertTrue(
            "Diagonal traversal should have at least 3 coordinates, got ${path.size}",
            path.size >= 3
        )
    }

    // Test Plan Section 5.1.4 — A* edge case: same start and end
    @Test
    fun `findPath returns empty list for same start and end node`() {
        val graph = CampusGraph.fromGeoJsonString(SYNTHETIC_GRAPH_JSON)
        // Production behavior verified: start==target returns from runAStar with
        // empty cameFromNode/cameFromEdge maps → reconstructPathWithEdges returns
        // emptyList() (the for-loop "for (i in 1 until 1)" iterates zero times).
        val path = graph.findPath(0, 0)
        assertTrue("Same-node path should be empty, got ${path.size} coordinates", path.isEmpty())
    }

    // Test Plan Section 5.1.4 — A* edge case: invalid node id (silent failure)
    @Test
    fun `findPath returns empty list for non-existent start node`() {
        val graph = CampusGraph.fromGeoJsonString(SYNTHETIC_GRAPH_JSON)
        // Node ID 999 doesn't exist in the 4-node synthetic graph.
        // runAStar bails on `nodes[startNodeId] ?: return null`, then findPath
        // converts the null to emptyList().
        val path = graph.findPath(999, 0)
        assertTrue("Invalid start should return empty path, got ${path.size}", path.isEmpty())
    }
}
