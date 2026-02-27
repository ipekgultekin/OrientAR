package com.example.orientar.navigation.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * FileLogger - Save logs to device storage for offline debugging.
 *
 * USAGE:
 * 1. Initialize: FileLogger.init(context)
 * 2. Log: FileLogger.d("TAG", "message")
 * 3. Find logs at: /Android/data/com.example.orientar.navigation/files/OrientAR_Logs/
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val LOG_FOLDER = "OrientAR_Logs"
    private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L
    private const val MAX_LOG_FILES = 5

    private var logFile: File? = null
    private var printWriter: PrintWriter? = null
    private var isInitialized = false
    private var appContext: Context? = null

    private val logBuffer = ConcurrentLinkedQueue<String>()
    private val executor = Executors.newSingleThreadExecutor()

    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var sessionStartTime: Long = 0L
    private var logCount: Int = 0

    // ========================================================================================
    // INITIALIZATION
    // ========================================================================================

    /**
     * Initialize the file logger. Call once in onCreate.
     */
    fun init(context: Context) {
        if (isInitialized) return

        appContext = context.applicationContext
        sessionStartTime = System.currentTimeMillis()

        try {
            val logDir = getLogDirectory(context)
            if (logDir == null) {
                Log.e(TAG, "Could not create log directory")
                return
            }

            cleanOldLogs(logDir)

            val fileName = "orientar_${fileNameFormat.format(Date())}.log"
            logFile = File(logDir, fileName)
            logFile?.let { printWriter = PrintWriter(FileWriter(it, true), true) }//printWriter = PrintWriter(FileWriter(logFile!!, true), true)

            isInitialized = true
            writeHeader()

            Log.d(TAG, "FileLogger initialized: ${logFile?.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FileLogger: ${e.message}")
        }
    }

    private fun getLogDirectory(context: Context): File? {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val logDir = File(externalDir, LOG_FOLDER)
            if (logDir.exists() || logDir.mkdirs()) {
                return logDir
            }
        }

        // Fallback to internal storage
        val internalDir = File(context.filesDir, LOG_FOLDER)
        if (internalDir.exists() || internalDir.mkdirs()) {
            return internalDir
        }

        return null
    }

    private fun cleanOldLogs(logDir: File) {
        try {
            val logFiles = logDir.listFiles { _, name ->
                name.startsWith("orientar_") && name.endsWith(".log")
            }

            if (logFiles != null && logFiles.size >= MAX_LOG_FILES) {
                val sortedFiles = logFiles.sortedBy { it.lastModified() }
                val filesToDelete = sortedFiles.size - MAX_LOG_FILES + 1
                for (i in 0 until filesToDelete) {
                    sortedFiles[i].delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning old logs: ${e.message}")
        }
    }

    private fun writeHeader() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val separator = "========================================================================"
        val header = buildString {
            appendLine(separator)
            appendLine("  ORIENTAR PRO - DEBUG LOG")
            appendLine(separator)
            appendLine("  Session Started: ${dateFormat.format(Date())}")
            appendLine("  Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(separator)
            appendLine()
        }
        printWriter?.print(header)
        printWriter?.flush()
    }

    // ========================================================================================
    // LOGGING METHODS
    // ========================================================================================

    fun d(tag: String, message: String) {
        writeLog("D", tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        writeLog("I", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        writeLog("W", tag, message)
        Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        writeLog("E", tag, message)
        Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        val fullMessage = "$message\n${Log.getStackTraceString(throwable)}"
        writeLog("E", tag, fullMessage)
        Log.e(tag, message, throwable)
    }

    /**
     * Log navigation event.
     */
    fun nav(message: String) {
        writeLog("D", "NAV", ">> $message")
        Log.d("NAV", message)
    }

    /**
     * Log AR event.
     */
    fun ar(message: String) {
        writeLog("D", "AR", "** $message")
        Log.d("AR", message)
    }

    fun gps(message: String) {
        writeLog("D", "GPS", "📍 $message")
        Log.d("GPS", message)
    }

    // ========================================================================================
    // NEW: Additional domain-specific loggers for comprehensive coverage
    // ========================================================================================

    /**
     * Log route calculation event (pathfinding, graph operations).
     */
    private var lastRouteLogTime = 0L
    fun route(message: String, forceLog: Boolean = false) {
        val now = System.currentTimeMillis()
        // Rate limit to prevent log spam during frequent route updates
        if (forceLog || now - lastRouteLogTime > 500) {
            writeLog("D", "ROUTE", "➡️ $message")
            Log.d("ROUTE", message)
            lastRouteLogTime = now
        }
    }

    /**
     * Log anchor management event (creation, tracking, cleanup).
     */
    fun anchor(message: String) {
        writeLog("D", "ANCHOR", "⚓ $message")
        Log.d("ANCHOR", message)
    }

    /**
     * Log coordinate alignment event (yaw offset, drift correction).
     */
    fun align(message: String) {
        writeLog("D", "ALIGN", "🎯 $message")
        Log.d("ALIGN", message)
    }

    /**
     * Log terrain profiling event (ground detection, height adjustment).
     */
    fun terrain(message: String) {
        writeLog("D", "TERRAIN", "⛰️ $message")
        Log.d("TERRAIN", message)
    }

    /**
     * Log segment management event (segment lifecycle).
     */
    fun segment(message: String) {
        writeLog("D", "SEGMENT", "🔗 $message")
        Log.d("SEGMENT", message)
    }

    /**
     * Log sensor event (compass, gyroscope, fusion).
     */
    fun sensor(message: String) {
        writeLog("D", "SENSOR", "🧲 $message")
        Log.d("SENSOR", message)
    }

    /**
     * Log performance metric (timing, frame budget).
     * Only logs if operation exceeds threshold (default: 16ms = 1 frame).
     */
    fun perf(operation: String, timeMs: Long, thresholdMs: Long = 16L) {
        if (timeMs > thresholdMs) {
            val level = if (timeMs > thresholdMs * 2) "W" else "D"
            writeLog(level, "PERF", "⏱️ $operation took ${timeMs}ms (threshold: ${thresholdMs}ms)")
            if (level == "W") {
                Log.w("PERF", "$operation took ${timeMs}ms (threshold: ${thresholdMs}ms)")
            } else {
                Log.d("PERF", "$operation took ${timeMs}ms")
            }
        }
    }

    /**
     * Log state transition (app state changes).
     */
    fun state(from: String, to: String, reason: String = "") {
        val reasonPart = if (reason.isNotEmpty()) " - $reason" else ""
        writeLog("I", "STATE", "🔄 $from → $to$reasonPart")
        Log.i("STATE", "$from → $to$reasonPart")
    }

    /**
     * Log critical milestone (important events for debugging).
     */
    fun milestone(message: String) {
        writeLog("I", "MILESTONE", "⭐ $message")
        Log.i("MILESTONE", message)
    }

    /**
     * Add section marker with visual separator.
     */
    fun section(title: String) {
        val line = "============================================================"
        writeLog("I", "---", "\n$line\n  $title\n$line")
    }

    private fun writeLog(level: String, tag: String, message: String) {
        // ========================================================================
        // FIX 2.1: Improved logging reliability
        // ========================================================================
        // PROBLEM: Logs flushed every 10 entries - crash loses up to 9 logs
        // SOLUTION:
        //   - Errors/warnings flush immediately
        //   - Other logs flush every 5 entries
        //   - Add null checks to prevent silent failures
        // ========================================================================

        if (!isInitialized) {
            Log.w(TAG, "FileLogger not initialized, log lost: [$level/$tag] $message")
            return
        }

        if (printWriter == null) {
            Log.e(TAG, "PrintWriter is null, log lost: [$level/$tag] $message")
            return
        }

        val timestamp = logTimeFormat.format(Date())
        val elapsed = (System.currentTimeMillis() - sessionStartTime) / 1000.0
        val logLine = "[$timestamp][+${String.format(Locale.US, "%.1f", elapsed)}s][$level/$tag] $message"

        logBuffer.add(logLine)
        logCount++

        // Flush immediately for errors/warnings, every 5 for others
        val isUrgent = (level == "E" || level == "W")
        if (isUrgent || logCount % 5 == 0) {
            flushAsync()
        }
    }

    private fun flushAsync() {
        executor.execute {
            try {
                while (logBuffer.isNotEmpty()) {
                    val line = logBuffer.poll()
                    if (line != null) {
                        printWriter?.println(line)
                    }
                }
                printWriter?.flush()
                checkFileSize()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing log: ${e.message}")
            }
        }
    }

    fun flush() {
        try {
            while (logBuffer.isNotEmpty()) {
                printWriter?.println(logBuffer.poll())
            }
            printWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing: ${e.message}")
        }
    }

    private fun checkFileSize() {
        val file = logFile ?: return
        if (file.length() > MAX_LOG_SIZE_BYTES) {
            printWriter?.close()

            val ctx = appContext ?: return
            val logDir = getLogDirectory(ctx) ?: return
            val fileName = "orientar_${fileNameFormat.format(Date())}.log"
            logFile = File(logDir, fileName)
            logFile?.let { printWriter = PrintWriter(FileWriter(it, true), true) } //printWriter = PrintWriter(FileWriter(logFile!!, true), true)

            writeHeader()
            printWriter?.println("[LOG ROTATED]")
            cleanOldLogs(logDir)
        }
    }

    // ========================================================================================
    // UTILITY METHODS
    // ========================================================================================

    fun getLogFilePath(): String? = logFile?.absolutePath

    /**
     * Share log file via Android share sheet.
     */
    fun shareLogFile(activity: Activity) {
        flush()

        val file = logFile
        if (file == null || !file.exists()) {
            Toast.makeText(activity, "No log file available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(Intent.createChooser(intent, "Share Log File"))

        } catch (e: Exception) {
            Log.e(TAG, "Error sharing: ${e.message}")
            Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Copy recent logs to clipboard.
     */
    fun copyToClipboard(activity: Activity, lastNLines: Int = 100) {
        flush()

        try {
            val file = logFile ?: return
            val lines = file.readLines().takeLast(lastNLines)
            val content = lines.joinToString("\n")

            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OrientAR Log", content)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(activity, "Copied last $lastNLines lines", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error copying: ${e.message}")
        }
    }

    /**
     * Write session summary and close.
     */
    fun shutdown() {
        if (!isInitialized) return

        val duration = (System.currentTimeMillis() - sessionStartTime) / 1000
        val minutes = duration / 60
        val seconds = duration % 60

        val separator = "========================================================================"
        val summary = buildString {
            appendLine()
            appendLine(separator)
            appendLine("  SESSION ENDED")
            appendLine(separator)
            appendLine("  Duration: ${minutes}m ${seconds}s")
            appendLine("  Total Logs: $logCount")
            appendLine(separator)
        }

        try {
            flush()
            printWriter?.print(summary)
            printWriter?.flush()
            printWriter?.close()
            executor.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down: ${e.message}")
        }

        isInitialized = false
    }
}