package com.example.orientar.navigation.logic

import org.junit.Test
import org.junit.Assert.assertNotNull

/**
 * Unit tests for CampusGraph routing logic.
 *
 * Currently covers the [CampusGraph.fromGeoJsonString] factory entry point;
 * SCRUM-84 will extend to A* `findPath` and phantom-flow `findPathFromPoint`,
 * including disconnected-node and start-equals-end edge cases.
 */
class CampusGraphUnitTest {

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
}
