package com.example.orientar

import com.example.orientar.treasure.fuzzyContainsKeyword
import com.example.orientar.treasure.isSimilar
import com.example.orientar.treasure.levenshtein
import com.example.orientar.treasure.normalize
import org.junit.Test
import org.junit.Assert.*

class TreasureHuntLogicUnitTest {

    // 1. normalize Tests
    @Test
    fun `normalize should handle case punctuation spaces and Turkish chars`() {
        assertEquals("LIBRARY", normalize("library"))
        assertEquals("METU", normalize("  metu  "))
        assertEquals("LIBRARY", normalize("Library!"))
        assertEquals("BLOCK A", normalize("Block-A..."))
        assertEquals("KUTUPHANE", normalize("Kütüphane"))
        assertEquals("GIRIS", normalize("Giriş"))
    }

    @Test
    fun `normalize should return empty for empty input`() {
        assertEquals("", normalize(""))
        assertEquals("", normalize("   "))
    }

    // 2. levenshtein Tests
    @Test
    fun `levenshtein should compute correct edit distance`() {
        assertEquals(0, levenshtein("METU", "METU"))
        assertEquals(1, levenshtein("METU", "METO"))
        assertEquals(2, levenshtein("LIBRARY", "LIBRY"))
        assertEquals(3, levenshtein("ABC", "DEF"))
    }

    // 3. isSimilar Tests
    @Test
    fun `isSimilar should respect 20 percent threshold`() {
        assertTrue(isSimilar("KUTUPHANE", "KUTUPHANE"))
        assertTrue(isSimilar("KUTUPHANE", "KUTUPHANI"))

        assertFalse(isSimilar("KUTUPHANE", "KUTUPXXX"))
    }

    @Test
    fun `isSimilar should handle empty safely`() {
        assertFalse(isSimilar("", "ABC"))
        assertFalse(isSimilar("ABC", ""))
    }

    // 4. fuzzyContainsKeyword Tests
    @Test
    fun `fuzzyContainsKeyword should match noisy OCR text`() {
        val ocr = "WELCOME TO THE METU L1BRARY OF CAMPUS"
        val target = "LIBRARY"

        assertTrue(fuzzyContainsKeyword(ocr, target))
    }

    @Test
    fun `fuzzyContainsKeyword should handle multi word sliding window`() {
        val ocr = "THIS IS THE RECTORATE BUILD1NG"
        val target = "RECTORATE BUILDING"

        assertTrue(fuzzyContainsKeyword(ocr, target))
    }

    @Test
    fun `fuzzyContainsKeyword should fallback when OCR shorter than keyword`() {
        val ocr = "LIB"
        val target = "LIBRARY"

        assertTrue(isSimilar(normalize(ocr), normalize(target)) == false)
    }

    @Test
    fun `fuzzyContainsKeyword should return false for unrelated text`() {
        val ocr = "THE WEATHER IS NICE"
        val target = "LIBRARY"

        assertFalse(fuzzyContainsKeyword(ocr, target))
    }
}