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
 * When [enabled] is set, every line is kept in a bounded in-memory ring buffer
 * and appended to a text file inside the app-specific external storage, which
 * makes it reachable without any runtime permission. When [enabled] is false,
 * nothing is collected:
 *
 *     Android/data/io.github.mrksey.softsstp/files/connection_log.txt
 */
internal object ConnectionLog {
    private const val FILE_NAME = "connection_log.txt"
    private const val MAX_LINES = 1000
    private const val MAX_FILE_BYTES = 512 * 1024

    /**
     * Length of the "=" rule that frames a session header. Chosen so the whole
     * line fits on one row of the log viewer (monospace, ~11sp) on a typical
     * phone, without wrapping onto a second line.
     */
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
        // When logging is turned off nothing is collected at all, neither in
        // the in-memory buffer (which backs the "View connection log" screen)
        // nor in the file. Previously the buffer was always filled, so the UI
        // kept showing fresh entries even with the toggle disabled.
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
        writeLine("=== NEW SESSION  ${sessionTimestamp()} ===")
        if (details.isNotBlank()) {
            writeLine("    $details")
        }
        writeLine(separator)
    }

    /**
     * Shared writer used by [append] and [startSession]: keeps the bounded
     * in-memory ring buffer and the on-disk file in sync.
     */
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

    /**
     * Second-precision timestamp for session headers, so the "=== NEW SESSION ==="
     * line stays short and fits on a single row in the log viewer.
     */
    private fun sessionTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
