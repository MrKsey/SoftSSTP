package vn.unlimit.softether

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects a readable log of the whole SoftEther connection lifecycle so the
 * application (SoftSSTP) can show it to the user for diagnostics.
 *
 * Mirrors the SSTP plugin's ConnectionLog: when [enabled] is set, every line is
 * kept in a bounded in-memory ring buffer and appended to a text file inside the
 * app-specific external storage, which is reachable without any runtime
 * permission. When [enabled] is false nothing is collected.
 *
 *     Android/data/<app>/files/softether_log.txt
 */
object SoftEtherLog {
    private const val FILE_NAME = "softether_log.txt"
    private const val MAX_LINES = 1000
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val SESSION_SEPARATOR_LENGTH = 40

    private val buffer = ArrayDeque<String>()

    @Volatile
    var enabled: Boolean = false

    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, FILE_NAME)
    }

    fun filePath(): String = logFile?.absolutePath ?: ""

    @Synchronized
    fun append(tag: String, message: String) {
        if (!enabled) return
        writeLine("${timestamp()} [$tag] $message")
    }

    /**
     * Writes a clearly delimited header at the start of a new connection
     * session, so the log makes it obvious where one session ends and the next
     * begins.
     */
    @Synchronized
    fun startSession(details: String = "") {
        if (!enabled) return
        val separator = "=".repeat(SESSION_SEPARATOR_LENGTH)
        writeLine("")
        writeLine(separator)
        writeLine("=== NEW SOFTETHER SESSION  ${sessionTimestamp()} ===")
        if (details.isNotBlank()) {
            writeLine("    $details")
        }
        writeLine(separator)
    }

    private fun writeLine(line: String) {
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) {
            buffer.removeFirst()
        }
        try {
            val file = logFile ?: return
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                file.writeText("")
            }
            file.appendText(line + "\n")
        } catch (_: Throwable) {
            // Logging must never break the running connection.
        }
    }

    @Synchronized
    fun read(): String = buffer.joinToString("\n")

    @Synchronized
    fun clear() {
        buffer.clear()
        try {
            logFile?.writeText("")
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    private fun sessionTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
