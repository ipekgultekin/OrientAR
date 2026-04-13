package com.example.orientar

import com.example.orientar.treasure.GameState
import com.example.orientar.treasure.Question
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class GameStateLogicUnitTest {

    @Before
    fun setup() {
        // Clear memory and initialize mock question data before each test execution [cite: 518]
        GameState.clearMemory()
        GameState.questions.clear()
        GameState.questions.add(Question(id = 1, title = "Q1", text = "T1", modelFilePath = "m1", modelScale = 1f, modelRotationX = 0f, modelRotationY = 0f, modelRotationZ = 0f))
        GameState.questions.add(Question(id = 2, title = "Q2", text = "T2", modelFilePath = "m2", modelScale = 1f, modelRotationX = 0f, modelRotationY = 0f, modelRotationZ = 0f))
    }

    // Test Plan Objective: To verify solved-state tracking and cumulative elapsed time calculation
    @Test
    fun `markSolved should update totalSolved and totalTime`() {
        // Simulate solving Question 1 in 5 seconds (5000ms)
        GameState.markSolved(1, 5000L, null)

        assertEquals(1, GameState.totalSolved)
        assertEquals(5000L, GameState.totalTimeMs)
    }

    // Test Plan Objective: To verify best time persistence and comparison logic for each clue [cite: 82, 1005]
    @Test
    fun `markSolved should only update bestTime if the new time is shorter`() {
        GameState.markSolved(1, 10000L, null) // Initial solution: 10s
        assertEquals(10000L, GameState.bestTimes[1])

        GameState.markSolved(1, 5000L, null) // Faster solution: 5s
        assertEquals(5000L, GameState.bestTimes[1]) // Value should be updated

        GameState.markSolved(1, 8000L, null) // Slower solution: 8s
        assertEquals(5000L, GameState.bestTimes[1]) // Previous best value should be retained
    }

    // Test Plan Objective: To verify correct question sequencing and unsolved-state detection [cite: 83, 231]
    @Test
    fun `nextUnsolved should return the first available unsolved question`() {
        // Initially, the first question in the list should be returned
        assertEquals(1, GameState.nextUnsolved()?.id)

        // Mark Question 1 as solved
        GameState.markSolved(1, 1000L, null)

        // System should now return Question 2 as the next target
        assertEquals(2, GameState.nextUnsolved()?.id)
    }

    // Test Plan Objective: To verify state cleanup during logout or game reset scenarios [cite: 235, 518]
    @Test
    fun `clearMemory should wipe all in-memory progress`() {
        // Populate state with existing progress
        GameState.markSolved(1, 3000L, null)

        // Trigger memory cleanup (simulating logout or reset)
        GameState.clearMemory()

        // Verify that all tracking variables are reset to their initial states [cite: 1152]
        assertEquals(0, GameState.totalSolved)
        assertEquals(0L, GameState.totalTimeMs)
        assertTrue(GameState.bestTimes.isEmpty())
    }
}