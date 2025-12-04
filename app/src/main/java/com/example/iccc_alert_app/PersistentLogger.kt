package com.example.iccc_alert_app

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Persistent file-based logger that survives app kills and doze mode
 * Writes critical events to files for debugging
 */
object PersistentLogger {

    private const val TAG = "PersistentLogger"
    private const val LOG_DIR_NAME = "iccc_logs"
    private const val MAX_LOG_FILES = 7 // Keep last 7 days
    private const val MAX_FILE_SIZE = 10 * 1024 * 1024 // 10MB per file

    private lateinit var context: Context
    private lateinit var logDir: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val isInitialized = AtomicBoolean(false)

    // Buffer for async writing
    private val logQueue = ConcurrentLinkedQueue<String>()
    private var writerThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    fun initialize(ctx: Context) {
        if (isInitialized.getAndSet(true)) {
            return
        }

        context = ctx.applicationContext

        // Use app-specific external storage (doesn't require WRITE_EXTERNAL_STORAGE permission)
        logDir = File(context.getExternalFilesDir(null), LOG_DIR_NAME)

        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        Log.d(TAG, "Log directory: ${logDir.absolutePath}")

        // Clean old logs
        cleanOldLogs()

        // Start writer thread
        startWriterThread()

        // Log initialization
        logEvent("SYSTEM", "PersistentLogger initialized - Log dir: ${logDir.absolutePath}")
    }

    private fun startWriterThread() {
        isRunning.set(true)
        writerThread = thread(name = "LogWriter") {
            while (isRunning.get()) {
                try {
                    val entries = mutableListOf<String>()
                    while (logQueue.isNotEmpty() && entries.size < 100) {
                        logQueue.poll()?.let { entries.add(it) }
                    }

                    if (entries.isNotEmpty()) {
                        writeToFile(entries)
                    }

                    Thread.sleep(1000) // Write every second
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Writer thread error: ${e.message}")
                }
            }
        }
    }

    private fun writeToFile(entries: List<String>) {
        try {
            val logFile = getCurrentLogFile()

            // Check file size
            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                rotateLogFile(logFile)
            }

            FileWriter(logFile, true).use { writer ->
                entries.forEach { entry ->
                    writer.write(entry)
                    writer.write("\n")
                }
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file: ${e.message}")
        }
    }

    private fun getCurrentLogFile(): File {
        val date = dateFormat.format(Date())
        return File(logDir, "iccc_$date.log")
    }

    private fun rotateLogFile(file: File) {
        try {
            val timestamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val rotatedFile = File(logDir, "${file.nameWithoutExtension}_$timestamp.log")
            file.renameTo(rotatedFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log: ${e.message}")
        }
    }

    private fun cleanOldLogs() {
        try {
            val files = logDir.listFiles() ?: return
            val cutoffDate = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -MAX_LOG_FILES)
            }.time

            files.forEach { file ->
                if (file.isFile && file.lastModified() < cutoffDate.time) {
                    file.delete()
                    Log.d(TAG, "Deleted old log: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old logs: ${e.message}")
        }
    }

    /**
     * Log a critical event
     */
    fun logEvent(category: String, message: String) {
        try {
            val time = timeFormat.format(Date())
            val entry = "$time [$category] $message"

            // Also log to Android logcat
            Log.i(TAG, entry)

            // Queue for file writing
            logQueue.offer(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log event: ${e.message}")
        }
    }

    /**
     * Log connection state change
     */
    fun logConnection(state: String, details: String = "") {
        val msg = if (details.isEmpty()) state else "$state - $details"
        logEvent("CONNECTION", msg)
    }

    /**
     * Log doze mode change
     */
    fun logDozeMode(entering: Boolean, details: String = "") {
        val state = if (entering) "ENTERED DOZE" else "EXITED DOZE"
        val msg = if (details.isEmpty()) state else "$state - $details"
        logEvent("DOZE", msg)
    }

    /**
     * Log event processing
     */
    fun logEventProcessing(action: String, eventId: String, details: String = "") {
        val msg = "$action - Event: $eventId" + if (details.isEmpty()) "" else " - $details"
        logEvent("EVENT", msg)
    }

    /**
     * Log service lifecycle
     */
    fun logServiceLifecycle(state: String, details: String = "") {
        val msg = if (details.isEmpty()) state else "$state - $details"
        logEvent("SERVICE", msg)
    }

    /**
     * Log catch-up activity
     */
    fun logCatchUp(message: String) {
        logEvent("CATCHUP", message)
    }

    /**
     * Log error
     */
    fun logError(category: String, error: String, exception: Throwable? = null) {
        var msg = error
        if (exception != null) {
            msg += " - ${exception.javaClass.simpleName}: ${exception.message}"
        }
        logEvent("ERROR_$category", msg)

        // Log stack trace if provided
        exception?.let {
            val sw = java.io.StringWriter()
            it.printStackTrace(PrintWriter(sw))
            logQueue.offer("  Stack: ${sw.toString().take(500)}") // First 500 chars of stack
        }
    }

    /**
     * Force flush all pending logs
     */
    fun flush() {
        try {
            val entries = mutableListOf<String>()
            while (logQueue.isNotEmpty()) {
                logQueue.poll()?.let { entries.add(it) }
            }
            if (entries.isNotEmpty()) {
                writeToFile(entries)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush: ${e.message}")
        }
    }

    /**
     * Get all log files
     */
    fun getLogFiles(): List<File> {
        return try {
            logDir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get log files: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get log directory path
     */
    fun getLogDirectory(): String = logDir.absolutePath

    /**
     * Get current log file path
     */
    fun getCurrentLogFilePath(): String = getCurrentLogFile().absolutePath

    /**
     * Read recent logs (last N lines)
     */
    fun getRecentLogs(lines: Int = 100): List<String> {
        return try {
            val file = getCurrentLogFile()
            if (!file.exists()) return emptyList()

            file.readLines().takeLast(lines)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read recent logs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Export logs to a single file for sharing
     */
    fun exportLogs(): File? {
        return try {
            val exportFile = File(context.getExternalFilesDir(null), "iccc_logs_export_${System.currentTimeMillis()}.txt")
            val writer = FileWriter(exportFile)

            writer.write("ICCC Alert App - Log Export\n")
            writer.write("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            writer.write("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            writer.write("Android: ${android.os.Build.VERSION.RELEASE}\n")
            writer.write("=".repeat(80) + "\n\n")

            getLogFiles().forEach { file ->
                writer.write("\n\n=== ${file.name} ===\n\n")
                file.readLines().forEach { line ->
                    writer.write(line)
                    writer.write("\n")
                }
            }

            writer.close()
            exportFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs: ${e.message}")
            null
        }
    }

    /**
     * Clear all logs
     */
    fun clearLogs() {
        try {
            logDir.listFiles()?.forEach { it.delete() }
            logEvent("SYSTEM", "Logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs: ${e.message}")
        }
    }

    /**
     * Shutdown logger
     */
    fun shutdown() {
        isRunning.set(false)
        writerThread?.interrupt()
        flush()
        logEvent("SYSTEM", "PersistentLogger shutdown")
    }
}