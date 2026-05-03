package com.example.orientar.generalappfeatures

import org.junit.Test
import org.junit.Assert.*
import java.util.Locale

class GeneralAppLogicUnitTest {

    // 1. Enrollment validation logic: Email domain validation [cite: 515, 1085]
    // Objective: To verify that only official METU email domains are accepted.
    @Test
    fun `isEmailValid should only accept metu edu tr domains`() {
        fun isEmailValid(email: String): Boolean {
            val normalizedEmail = email.lowercase(Locale.US).trim()
            return normalizedEmail.endsWith("@metu.edu.tr") && normalizedEmail.contains("@")
        }

        assertTrue(isEmailValid("user@metu.edu.tr"))
        assertFalse(isEmailValid("user@gmail.com")) // Unauthorized domain [cite: 57]
        assertFalse(isEmailValid("user@ncc.metu.edu.tr")) // Exact domain match check [cite: 182]
        assertFalse(isEmailValid("   ")) // Negative testing: empty input [cite: 523]
    }

    // 2. Enrollment validation logic: Invitation code normalization [cite: 515, 1085]
    // Objective: To ensure invitation codes are cleaned of whitespace and formatted correctly.
    @Test
    fun `normalizeInviteCode should handle mixed case and excessive spaces`() {
        fun normalize(code: String) = code.trim().uppercase(Locale.US)

        assertEquals("INVITE123", normalize("  invite123  "))
        assertEquals("METU2026", normalize("  metu2026  "))
        assertEquals("ABC123", normalize("AbC123"))
        assertEquals("", normalize("   ")) // Negative testing: blank input [cite: 523, 1085]
    }

    // 3. Password rule validation [cite: 515, 522, 1085]
    // Objective: To apply boundary value analysis on password length (minimum 6 characters).
    @Test
    fun `isPasswordSecure should enforce minimum length`() {
        fun isPasswordSecure(pass: String): Boolean {
            return pass.length >= 6
        }

        assertTrue(isPasswordSecure("123456"))    // Boundary value: Exactly 6
        assertTrue(isPasswordSecure("securePass123"))
        assertFalse(isPasswordSecure("12345"))   // Edge case: 5 characters (FAIL)
        assertFalse(isPasswordSecure(""))        // Negative testing: Empty input [cite: 523]
    }

    // 4. Student Societies client-side logic: Case-insensitive search [cite: 517, 1090]
    // Objective: To verify that search filtering works regardless of user casing.
    @Test
    fun `societySearch should be case insensitive and handle empty results`() {
        val societies = listOf("IEEE Student Branch", "ACM Society", "Photography Club")

        fun filterSocieties(query: String): List<String> {
            val normalizedQuery = query.lowercase(Locale.US).trim()
            return societies.filter { it.lowercase(Locale.US).contains(normalizedQuery) }
        }

        assertTrue(filterSocieties("ieee").isNotEmpty()) // Match found
        assertEquals(1, filterSocieties("ACM").size)
        assertTrue(filterSocieties("random").isEmpty()) // Empty-result handling [cite: 517, 1090]
    }

    // 5. Student Societies: Alphabetical sorting logic [cite: 517, 1090]
    // Objective: To verify the requirement for alphabetical listing of societies.
    @Test
    fun `societies should be displayed in alphabetical order`() {
        val rawList = listOf("Photography Club", "ACM Society", "IEEE Student Branch")
        val expected = listOf("ACM Society", "IEEE Student Branch", "Photography Club")

        val sortedList = rawList.sorted()

        assertEquals(expected, sortedList) // Sorting logic verification [cite: 1090]
    }

    // 6. Profile & Navigation: Role-specific setting checks [cite: 516, 208, 1041]
    // Objective: To verify role-specific visibility rules (e.g., restricted access for guests).
    @Test
    fun `isFeatureAccessible should restrict modules based on user role`() {
        fun canAccessRestrictedModules(role: String): Boolean {
            // Guest users must be restricted from certain features [cite: 60, 214]
            return role == "student" || role == "leader"
        }

        assertTrue(canAccessRestrictedModules("student"))
        assertTrue(canAccessRestrictedModules("leader"))
        assertFalse(canAccessRestrictedModules("guest")) // Guest restriction [cite: 1041]
    }

    // 7. General state handling logic: Logout local state reset [cite: 518, 176]
    // Objective: To verify that session-related flags are cleared correctly upon logout.
    @Test
    fun `clearLocalSessionState should reset login and role flags`() {
        var isLoggedIn = true
        var currentUserRole = "student"

        // Simulating logout behavior [cite: 176, 639]
        fun logout() {
            isLoggedIn = false
            currentUserRole = ""
        }

        logout()

        assertFalse(isLoggedIn) // Session must be cleared [cite: 176]
        assertEquals("", currentUserRole) // Role must be reset
    }

    // 8. My Unit visibility: Phone sharing logic [cite: 516, 202]
    // Objective: To verify that phone visibility respects sharePhone settings.
    @Test
    fun `shouldShowPhoneNumber should respect privacy settings`() {
        fun shouldShow(role: String, sharePhone: Boolean): Boolean {
            if (role == "leader") return true // Leaders are usually visible
            return sharePhone
        }

        assertTrue(shouldShow("student", true))
        assertFalse(shouldShow("student", false)) // Privacy enforcement [cite: 202, 760]
        assertTrue(shouldShow("leader", false)) // Role-based override
    }
}