package org.jellyfin.mobile.crash

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import org.jellyfin.mobile.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TV build: crash reporter.
 *
 * - Java/Kotlin crashes: caught by an uncaught-exception handler and written to a file.
 * - Everything else (Fire OS killing the app for memory, a frozen app, a native crash in
 *   the video decoder or WebView): read from Android's exit records (Android 11+ / Fire OS 8).
 *
 * The next time the app opens, a dialog shows the reason so it can be photographed and sent on.
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"
    private const val PREFS = "nexus_crash_reporter"
    private const val KEY_LAST_EXIT = "last_seen_exit_ms"
    private const val MAX_FRAMES = 12
    private const val MAX_TRACE_CHARS = 3000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(appContext.filesDir, FILE_NAME).writeText(formatThrowable(thread, error))
            } catch (_: Throwable) {
                // Never let the reporter itself get in the way of the crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Shows a report for the previous run, if it ended badly. Call from the main activity. */
    fun showPendingReport(activity: Activity) {
        val parts = mutableListOf<String>()

        val file = File(activity.filesDir, FILE_NAME)
        val javaCrash = file.takeIf { it.exists() }?.readText()
        if (javaCrash != null) {
            parts += javaCrash
            file.delete()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            exitReport(activity, skipJavaCrash = javaCrash != null)?.let { parts += it }
        }

        if (parts.isEmpty()) return
        val text = parts.joinToString("\n\n")

        val textView = TextView(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            isFocusable = true
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(activity)
            .setTitle("Nexus Cinema closed unexpectedly last time")
            .setView(textView)
            .setPositiveButton("OK", null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReport(context: Context, skipJavaCrash: Boolean): String? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0L)

        val exits = try {
            am.getHistoricalProcessExitReasons(null, 0, 10)
        } catch (_: Exception) {
            return null
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(KEY_LAST_EXIT, it).apply() }

        // First run with the reporter: also look back over the last day, so a crash that
        // happened before this update is shown straight away
        val since = if (lastSeen == 0L) System.currentTimeMillis() - DAY_MS else lastSeen
        val exit = exits
            .filter { it.timestamp > since && describe(it.reason, skipJavaCrash) != null }
            .maxByOrNull { it.timestamp } ?: return null
        val reason = describe(exit.reason, skipJavaCrash) ?: return null

        return buildString {
            appendLine("Reason: $reason")
            exit.description?.takeIf { it.isNotBlank() }?.let { appendLine("Detail: $it") }
            appendLine("When: ${formatTime(exit.timestamp)}")
            appendLine("Memory: ${exit.pss / 1024} MB PSS, ${exit.rss / 1024} MB RSS")
            appendLine(deviceLine())
            readTrace(exit)?.let {
                appendLine()
                appendLine("Trace:")
                append(it)
            }
        }.trimEnd()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(reason: Int, skipJavaCrash: Boolean): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> if (skipJavaCrash) null else "App crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (video decoder / WebView / system library)"
        ApplicationExitInfo.REASON_ANR -> "App froze (not responding) and was closed"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Fire OS / Android to free memory"
        ApplicationExitInfo.REASON_SIGNALED -> "Killed by the system (signal)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed for using too many resources"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "A component it depends on (e.g. WebView) died"
        else -> null // normal exits: closed by the user, app updated, swiped away, etc.
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(exit: ApplicationExitInfo): String? = try {
        exit.traceInputStream?.bufferedReader()?.use { reader ->
            // For an ANR, the main thread's stack is what matters
            val lines = reader.readLines()
            val main = lines.indexOfFirst { it.startsWith("\"main\"") }
            val slice = if (main >= 0) lines.drop(main).take(MAX_FRAMES + 2) else lines.take(MAX_FRAMES * 2)
            slice.joinToString("\n").take(MAX_TRACE_CHARS)
        }
    } catch (_: Exception) {
        null
    }

    private fun formatThrowable(thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        // Keep it photographable: exception line(s) plus the first frames, and every "Caused by"
        val lines = sw.toString().lines()
        val kept = mutableListOf<String>()
        var framesInBlock = 0
        for (line in lines) {
            val isFrame = line.trimStart().startsWith("at ")
            if (!isFrame) {
                framesInBlock = 0
                if (line.isNotBlank() && !line.trimStart().startsWith("...")) kept += line
            } else if (framesInBlock < MAX_FRAMES) {
                kept += line
                framesInBlock++
            }
        }
        return buildString {
            appendLine("Reason: App crash on thread \"${thread.name}\"")
            appendLine("When: ${formatTime(System.currentTimeMillis())}")
            appendLine(deviceLine())
            appendLine()
            append(kept.joinToString("\n").take(MAX_TRACE_CHARS))
        }
    }

    private fun deviceLine() =
        "App ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    private fun formatTime(ms: Long) = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK).format(Date(ms))
}
