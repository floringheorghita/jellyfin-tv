package org.jellyfin.mobile.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.BuildConfig
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * TV build: self-updater.
 *
 * Every GitHub build publishes `version.json` and `jellyfin-tv.apk` to the fixed "latest" release.
 * On launch (and from Settings) the app compares that build number with its own and, if newer,
 * downloads the APK and hands it to the system installer. Updates install over the top and keep sign-ins.
 */
object AppUpdater {
    private const val BASE = "https://github.com/floringheorghita/jellyfin-tv/releases/download/latest"
    private const val VERSION_URL = "$BASE/version.json"
    private const val APK_URL = "$BASE/jellyfin-tv.apk"
    private const val TIMEOUT_MS = 15_000
    private const val PERCENT = 100

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RemoteVersion(val build: Int, val versionName: String)

    /** @param manual true when the user asked; then also report "up to date" and errors. */
    fun check(activity: Activity, scope: LifecycleCoroutineScope, manual: Boolean) {
        scope.launch {
            val remote = withContext(Dispatchers.IO) { fetchRemoteVersion() }
            if (activity.isFinishing) return@launch
            when {
                remote == null -> if (manual) toast(activity, "Couldn't check for updates")
                remote.build > BuildConfig.TV_BUILD -> offerUpdate(activity, scope, remote)
                manual -> toast(activity, "Jellyfin TV is up to date (${BuildConfig.VERSION_NAME})")
            }
        }
    }

    private fun fetchRemoteVersion(): RemoteVersion? = try {
        val connection = open(VERSION_URL)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Timber.w("Update check returned HTTP %d", connection.responseCode)
                null
            } else {
                json.decodeFromString<RemoteVersion>(connection.inputStream.bufferedReader().use { it.readText() })
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Timber.w(e, "Update check failed")
        null
    }

    private fun offerUpdate(activity: Activity, scope: LifecycleCoroutineScope, remote: RemoteVersion) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Jellyfin TV ${remote.versionName} is available (you have ${BuildConfig.VERSION_NAME}).")
            .setPositiveButton("Update now") { _, _ -> download(activity, scope) }
            .setNegativeButton("Later", null)
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE)
            ?.requestFocus()
    }

    private fun download(activity: Activity, scope: LifecycleCoroutineScope) {
        if (!canInstall(activity)) return

        val progress = AlertDialog.Builder(activity)
            .setTitle("Downloading update")
            .setMessage("Starting…")
            .setCancelable(false)
            .show()

        scope.launch {
            val apk = withContext(Dispatchers.IO) {
                downloadApk(activity) { percent ->
                    activity.runOnUiThread { progress.setMessage("$percent%") }
                }
            }
            progress.dismiss()
            if (apk == null) {
                toast(activity, "Update download failed")
            } else {
                install(activity, apk)
            }
        }
    }

    private fun downloadApk(activity: Activity, onProgress: (Int) -> Unit): File? = try {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "jellyfin-tv.apk")
        val connection = open(APK_URL)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Timber.w("APK download returned HTTP %d", connection.responseCode)
                null
            } else {
                val total = connection.contentLengthLong
                var lastPercent = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        var read: Int
                        var done = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * PERCENT / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                target
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Timber.w(e, "APK download failed")
        null
    }

    /** Android 8+ needs "Install unknown apps" allowed for this app; send the user there if not. */
    private fun canInstall(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) {
            return true
        }
        AlertDialog.Builder(activity)
            .setTitle("Allow updates")
            .setMessage(
                "To update itself, Jellyfin TV needs permission to install apps.\n\n" +
                    "Turn it on for Jellyfin TV on the next screen, then press Back and choose Update now again.",
            )
            .setPositiveButton("Open settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )
                try {
                    activity.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Timber.w(e)
                    toast(
                        activity,
                        "Open Settings → My Fire TV → Developer options → Install unknown apps and allow Jellyfin TV",
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        return false
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update-provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e)
            toast(activity, "No installer found on this device")
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
