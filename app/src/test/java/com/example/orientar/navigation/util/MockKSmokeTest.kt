package com.example.orientar.navigation.util

import io.mockk.mockk
import io.mockk.every
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Confirms MockK 1.13.13 dependency resolves and basic mock behavior works.
 * Reusable pattern for SCRUM-85 integration tests.
 */
class MockKSmokeTest {

    interface SimpleService {
        fun getValue(): Int
    }

    @Test
    fun `mockk creates a mock and every defines behavior`() {
        val service = mockk<SimpleService>()
        every { service.getValue() } returns 42
        assertEquals(42, service.getValue())
    }
}
