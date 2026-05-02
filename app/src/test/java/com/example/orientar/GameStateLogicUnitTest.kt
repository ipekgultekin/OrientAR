package com.example.orientar

import com.example.orientar.treasure.GameState
import com.example.orientar.treasure.Question
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class GameStateLogicUnitTest {

    @Before
    fun setup() {
        GameState.clearMemory()
        GameState.questions.clear()

        GameState.questions.add(
            Question(1, "Q1", "T1", modelFilePath = "m1",
                modelScale = 1f, modelRotationX = 0f, modelRotationY = 0f, modelRotationZ = 0f)
        )

        GameState.questions.add(
            Question(2, "Q2", "T2", modelFilePath = "m2",
                modelScale = 1f, modelRotationX = 0f, modelRotationY = 0f, modelRotationZ = 0f)
        )
    }

    @Test
    fun `markSolved should update totalSolved and totalTime`() {
        GameState.markSolved(1, 5000L, null)

        assertEquals(1, GameState.totalSolved)
        assertEquals(5000L, GameState.totalTimeMs)
    }

    @Test
    fun `markSolved should not double count same question`() {
        GameState.markSolved(1, 5000L, null)
        GameState.markSolved(1, 5000L, null)

        // 🔥 important
        assertEquals(1, GameState.totalSolved)
        assertEquals(5000L, GameState.totalTimeMs)
    }

    @Test
    fun `markSolved should update bestTime correctly`() {
        GameState.markSolved(1, 10000L, null)
        GameState.markSolved(1, 5000L, null)
        GameState.markSolved(1, 8000L, null)

        assertEquals(5000L, GameState.bestTimes[1])
    }

    @Test
    fun `nextUnsolved should return correct question`() {
        assertEquals(1, GameState.nextUnsolved()?.id)

        GameState.markSolved(1, 1000L, null)

        assertEquals(2, GameState.nextUnsolved()?.id)
    }

    @Test
    fun `nextUnsolvedAfter should work correctly`() {
        GameState.markSolved(1, 1000L, null)

        val next = GameState.nextUnsolvedAfter(1)

        assertEquals(2, next?.id)
    }

    @Test
    fun `clearMemory should reset all states`() {
        GameState.markSolved(1, 3000L, null)

        GameState.clearMemory()

        assertEquals(0, GameState.totalSolved)
        assertEquals(0L, GameState.totalTimeMs)
        assertTrue(GameState.bestTimes.isEmpty())
    }
}