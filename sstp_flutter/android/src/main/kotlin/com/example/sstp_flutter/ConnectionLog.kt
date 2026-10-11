package com.example.sstp_flutter

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * Collects a readable log of the whole connection lifecycle so that the
 * application (SoftSSTP) can show it to the user for diagnostics.
 *
 * When [enabled] is set, every line is appended to a text file inside the
 * app-specific external storage, which makes it reachable without any runtime
 * permission. When [enabled] is false, nothing is collected:
 *
 *     Android/data/io.github.mrksey.softsstp/files/connection_log.txt
 *
 * The SoftEther plugin writes to the SAME file (see its SoftEtherLog), so the
 * app shows a single chronological log even across protocol switches; each
 * session is delimited by a "=== NEW <PROTOCOL> SESSION ===" header.
 */
internal object ConnectionLog {
    // Shared with the SoftEther plugin — keep the two constants in sync.
    private const val FILE_NAME = "connection_log.txt"
    private const val MAX_FILE_BYTES = 512 * 1024

    /**
     * Length of the "=" rule that frames a session header. Chosen so the whole
     * line fits on one row of the log viewer (monospace, ~11sp) on a typical
     * phone, without wrapping onto a second line.
     */
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
        // When logging is turned off nothing is collected at all, neither in
        // the file nor in the "View connection log" screen (which reads it).
        if (!enabled) return

        writeLine("${timestamp()} [$tag] $message")
    }

    /**
     * Writes a clearly delimited header at the start of a new connection
     * session, so the log makes it obvious where one session ends and the next
     * begins. [details] is an optional one-line summary (e.g. the server).
     *
     * The separator is kept short enough to fit on a single line of the app's
     * monospace log viewer even on narrow phones (no wrapping).
     */
    @Synchronized
    fun startSession(details: String = "") {
        if (!enabled) return

        val separator = "=".repeat(SESSION_SEPARATOR_LENGTH)
        writeLine("")
        writeLine(separator)
        writeLine("=== NEW SSTP SESSION  ${sessionTimestamp()} ===")
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

    /**
     * Second-precision timestamp for session headers, so the "=== NEW SESSION ==="
     * line stays short and fits on a single row in the log viewer.
     */
    private fun sessionTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
