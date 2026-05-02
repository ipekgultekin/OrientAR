package com.example.orientar.navigation.logic

import org.junit.Test
import org.junit.Assert.assertNotNull

/**
 * Smoke test confirming the fromGeoJsonString() factory works — the basis
 * for SCRUM-84 unit tests of A* routing logic.
 */
class CampusGraphFactorySmokeTest {

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
