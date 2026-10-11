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
 * When [enabled] is set, every line is appended to a text file inside the
 * app-specific external storage, which is reachable without any runtime
 * permission. When [enabled] is false nothing is collected.
 *
 * The SSTP plugin writes to the SAME file (see its ConnectionLog), so the app
 * shows a single chronological log even across protocol switches; each session
 * is delimited by a "=== NEW <PROTOCOL> SESSION ===" header.
 *
 *     Android/data/<app>/files/connection_log.txt
 */
object SoftEtherLog {
    // Shared with the SSTP plugin — keep the two constants in sync.
    private const val FILE_NAME = "connection_log.txt"
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val SESSION_SEPARATOR_LENGTH = 40

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

    /**
     * Appends one line to the shared log file. The file is the single source of
     * truth (the UI reads it back), so there is no separate in-memory buffer.
     */
    private fun writeLine(line: String) {
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
    fun read(): String {
        val file = logFile ?: return ""
        return try {
            if (file.exists()) file.readText() else ""
        } catch (_: Throwable) {
            ""
        }
    }

    @Synchronized
    fun clear() {
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
