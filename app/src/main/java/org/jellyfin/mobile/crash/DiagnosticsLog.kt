package org.jellyfin.mobile.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import org.jellyfin.mobile.BuildConfig
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TV build: diagnostics log.
 *
 * Keeps a small log of what the app did this run and last run (lifecycle, player events,
 * memory, errors), plus the app's own logcat lines from the previous run. It can be sent to
 * the Jellyfin server's client-log endpoint and then read in Dashboard > Logs.
 */
object DiagnosticsLog {
    private const val MAX_BYTES = 400_000L
    private const val LOGCAT_LINES = 1500
    private const val MB = 1024 * 1024

    private var appContext: Context? = null
    private var current: File? = null
    private val dir get() = appContext?.let { File(it.filesDir, "diagnostics") }
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)

    fun init(context: Context) {
        appContext = context.applicationContext
        val dir = dir ?: return
        dir.mkdirs()
        val cur = File(dir, "current.log")
        val prev = File(dir, "previous.log")
        if (cur.exists()) {
            prev.delete()
            cur.renameTo(prev)
        }
        current = cur
        log("=== App start · ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log(memoryLine())

        // The previous run's logcat is still in the system buffer; keep a copy (this app's lines only)
        Thread {
            try {
                val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", LOGCAT_LINES.toString())
                    .redirectErrorStream(true)
                    .start()
                File(dir, "previous_logcat.log").outputStream().use { out -> process.inputStream.copyTo(out) }
                process.waitFor()
            } catch (e: Exception) {
                log("logcat capture failed: $e")
            }
        }.start()
    }

    @Synchronized
    fun log(message: String) {
        val file = current ?: return
        try {
            if (file.length() > MAX_BYTES) return
            file.appendText("${timeFormat.format(Date())} $message\n")
        } catch (_: Exception) {
            // diagnostics must never break the app
        }
    }

    fun memoryLine(): String {
        val context = appContext ?: return "memory: n/a"
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) / MB
        val native = Debug.getNativeHeapAllocatedSize() / MB
        val pss = Debug.getPss() / 1024
        return "memory: app PSS ${pss}MB (java ${javaUsed}/${rt.maxMemory() / MB}MB, native ${native}MB) · " +
            "device free ${info.availMem / MB}/${info.totalMem / MB}MB, low=${info.lowMemory}, threshold ${info.threshold / MB}MB"
    }

    fun buildReport(): String {
        val dir = dir ?: return "no diagnostics"
        fun section(title: String, name: String, tailLines: Int? = null): String {
            val f = File(dir, name)
            if (!f.exists()) return "===== $title: (none) =====\n"
            val lines = f.readLines()
            val body = (if (tailLines != null) lines.takeLast(tailLines) else lines).joinToString("\n")
            return "===== $title =====\n$body\n"
        }
        return buildString {
            appendLine("Nexus Cinema diagnostics · ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            appendLine()
            appendLine(section("Previous run (app log)", "previous.log"))
            appendLine(section("Previous run (logcat, this app only)", "previous_logcat.log", LOGCAT_LINES))
            appendLine(section("This run (app log)", "current.log"))
        }
    }

    /**
     * Uploads the report to Jellyfin's client log endpoint.
     * @return the file name shown in Dashboard > Logs, or null on failure (with reason)
     */
    fun upload(baseUrl: String, authorization: String): Pair<String?, String?> = try {
        val connection = (URL("${baseUrl.trimEnd('/')}/ClientLog/Document").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("Authorization", authorization)
        }
        connection.outputStream.use { it.write(buildReport().toByteArray()) }
        val code = connection.responseCode
        if (code in 200..299) {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val name = Regex("\"FileName\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            name to null
        } else {
            null to "server replied HTTP $code" + if (code == 403) " (client log upload may be turned off on the server)" else ""
        }
    } catch (e: Exception) {
        null to e.toString()
    }

    /** Authorization header for the signed-in Jellyfin user, or null if not signed in. */
    fun authorizationFor(apiClient: org.jellyfin.sdk.api.client.ApiClient): String? {
        val token = apiClient.accessToken ?: return null
        return org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder.buildHeader(
            clientName = apiClient.clientInfo.name,
            clientVersion = apiClient.clientInfo.version,
            deviceId = apiClient.deviceInfo.id,
            deviceName = apiClient.deviceInfo.name,
            accessToken = token,
        )
    }

    /**
     * Sends the report automatically if the previous run played a video (and hasn't been sent yet).
     * @return the uploaded file name, or null if nothing was sent
     */
    fun autoUploadIfNeeded(apiClient: org.jellyfin.sdk.api.client.ApiClient): String? {
        val dir = dir ?: return null
        val prev = File(dir, "previous.log")
        val marker = File(dir, "previous.sent")
        if (!prev.exists() || (marker.exists() && marker.lastModified() >= prev.lastModified())) return null
        if (!prev.readText().contains("PlayerFragment created")) return null
        val baseUrl = apiClient.baseUrl ?: return null
        val auth = authorizationFor(apiClient) ?: return null
        val (name, error) = upload(baseUrl, auth)
        if (name != null) {
            marker.writeText(name)
            log("Diagnostics auto-uploaded as $name")
        } else {
            log("Diagnostics auto-upload failed: $error")
        }
        return name
    }

    /** Timber tree that copies INFO and above into the diagnostics log. */
    class Tree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.INFO) return
            val level = when (priority) {
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                else -> "A"
            }
            val error = t?.let { e ->
                " | $e" + (e.cause?.let { c -> " | caused by $c" } ?: "") +
                    e.stackTrace.take(4).joinToString("") { "\n      at $it" }
            } ?: ""
            log("$level/${tag ?: "app"}: $message$error")
        }
    }
}
