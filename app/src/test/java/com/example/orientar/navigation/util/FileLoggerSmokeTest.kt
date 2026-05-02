package com.example.orientar.navigation.util

import org.junit.Test

/**
 * Smoke test confirming testOptions.unitTests.isReturnDefaultValues = true
 * is in effect. Without it, FileLogger.d would crash because:
 *   1. FileLogger.d calls writeLog (line 304 area)
 *   2. writeLog calls android.util.Log.w as fallback when not initialized
 *   3. android.util.Log throws "Method w not mocked" without testOptions
 *
 * This test passes only if the testOptions block in build.gradle.kts is correct.
 */
class FileLoggerSmokeTest {

    @Test
    fun `FileLogger d is callable from JVM unit test without crash`() {
        // FileLogger is uninitialized in JVM context (no Activity called init).
        // Calling d() should hit isInitialized=false guard and silently return.
        // The internal Log.w fallback only fires if isReturnDefaultValues=true
        // is set — otherwise the test crashes with "Method w not mocked".
        FileLogger.d("SMOKE_TEST", "this should not crash")
    }
}
