package vn.unlimit.softether

import android.content.Context
import android.os.Build
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

    /**
     * Whether the periodic (every 5 s) per-link traffic split is written to the
     * log. Off by default: during an idle session the line is identical on every
     * tick and buries everything else. The end-of-session split is written
     * regardless of this flag, so the distribution is never lost.
     */
    @Volatile
    var perLinkStatsEnabled: Boolean = false

    private var logFile: File? = null
    private var appVersion: String = ""

    fun init(context: Context) {
        if (logFile != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, FILE_NAME)
        appVersion = resolveAppVersion(context)
    }

    fun filePath(): String = logFile?.absolutePath ?: ""

    /**
     * Version of the installed app plus when the APK was last updated, e.g.
     * "1.3.4 (9) installed=2026-10-12 19:20". Written into every session header
     * so a collected log can be tied to the exact build that produced it: several
     * test builds share one version name while the native library differs, and
     * the update timestamp is what tells them apart.
     */
    fun appVersion(): String = appVersion.ifEmpty { "unknown" }

    private fun resolveAppVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            val installed = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(info.lastUpdateTime))
            "${info.versionName ?: "?"} ($code) installed=$installed"
        } catch (_: Throwable) {
            "unknown"
        }
    }

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
        writeLine("    appVersion=${appVersion()}")
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
