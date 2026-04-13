package com.example.orientar

import com.example.orientar.treasure.fuzzyContainsKeyword
import com.example.orientar.treasure.isSimilar
import com.example.orientar.treasure.levenshtein
import com.example.orientar.treasure.normalize
import org.junit.Test
import org.junit.Assert.*

class TreasureHuntLogicUnitTest {

    // 1. normalize(String) Tests
    // Objective: To verify case normalization, punctuation removal, and whitespace cleanup[cite: 16, 227].
    @Test
    fun `normalize should handle case, punctuation and spaces`() {
        assertEquals("LIBRARY", normalize("library"))
        assertEquals("METU", normalize("  metu  "))
        assertEquals("LIBRARY", normalize("Library!"))
        assertEquals("BLOCK A", normalize("Block-A..."))
    }

    // 2. levenshtein(String, String) Tests
    // Objective: To verify correct edit distance calculation using known input-output pairs[cite: 16, 530].
    @Test
    fun `levenshtein should compute correct edit distance`() {
        assertEquals(0, levenshtein("METU", "METU")) // Exact match
        assertEquals(1, levenshtein("METU", "METO")) // 1 character substitution
        assertEquals(2, levenshtein("LIBRARY", "LIBRY")) // 2 characters missing
        assertEquals(3, levenshtein("ABC", "DEF")) // Completely different
    }

    // 3. isSimilar(String, String) Tests
    // Objective: To verify correct classification based on the 20% similarity threshold[cite: 16, 531, 1119].
    @Test
    fun `isSimilar should respect 20 percent threshold`() {
        // "KUTUPHANE" is 9 chars. 20% is 1.8 chars. Therefore, 1 error should be accepted.
        assertTrue(isSimilar("KUTUPHANE", "KUTUPHANE")) // 0 errors -> OK
        assertTrue(isSimilar("KUTUPHANE", "KUTUPHANI")) // 1 error (approx 11%) -> OK

        // 2 errors (2/9 = approx 22%) exceeds the threshold and should return FALSE.
        assertFalse(isSimilar("KUTUPHANE", "KUTUPXXX")) // 3 errors -> FAIL
    }

    // 4. fuzzyContainsKeyword(String, String) Tests
    // Objective: To verify matching behavior including fuzzy matches and sliding window logic in long OCR outputs[cite: 16, 229, 532].
    @Test
    fun `fuzzyContainsKeyword should find matches in noisy text`() {
        val ocrOutput = "WELCOME TO THE METU L1BRARY OF CAMPUS"
        val target = "LIBRARY"

        // Should recognize "L1BRARY" as "LIBRARY" despite OCR noise
        assertTrue(fuzzyContainsKeyword(ocrOutput, target))
    }

    @Test
    fun `fuzzyContainsKeyword should handle multi-word keywords`() {
        val ocrOutput = "THIS IS THE RECTORATE BUILD1NG"
        val target = "RECTORATE BUILDING"

        // Should identify matches for multi-word targets using windowed comparison logic [cite: 16, 229]
        assertTrue(fuzzyContainsKeyword(ocrOutput, target))
    }

    @Test
    fun `fuzzyContainsKeyword should prevent false positives`() {
        val ocrOutput = "THE WEATHER IS VERY NICE TODAY"
        val target = "LIBRARY"

        // Unrelated text should not trigger a match [cite: 16, 532, 540]
        assertFalse(fuzzyContainsKeyword(ocrOutput, target))
    }
}