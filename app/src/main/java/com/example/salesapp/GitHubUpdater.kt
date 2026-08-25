package com.example.salesapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object GitHubUpdater {
    private const val PREFS = "salesapp_update"
    private const val PENDING_URL = "pending_url"
    private const val EXTRA_URL = "salesapp_update_url"
    private const val EXTRA_VERSION = "salesapp_update_version"
    private const val EXTRA_NOTES = "salesapp_update_notes"
    private const val LAST_VERSION = "last_notified_version"
    private const val LAST_AT = "last_notified_at"
    private const val NOTIFICATION_ID = 1330
    private const val REMIND_MS = 6L * 60L * 60L * 1000L

    fun checkForUpdate(activity: Activity, silent: Boolean = false) {
        thread {
            try {
                val owner = UpdateConfig.GITHUB_OWNER
                val repo = UpdateConfig.GITHUB_REPO
                val c = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
                    .openConnection() as HttpURLConnection
                c.requestMethod = "GET"
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "CHLE-MAR-SalesAPP-Android")
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val payload = stream.bufferedReader().use { it.readText() }
                c.disconnect()
                if (code !in 200..299) error("GitHub HTTP $code")

                val json = JSONObject(payload)
                val latest = json.optString("tag_name").removePrefix("v").trim()
                val notes = json.optString("body").trim()
                var apkUrl = ""
                json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").equals(UpdateConfig.APK_ASSET_NAME, true)) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (latest.isBlank()) error("Brak numeru wersji w release")
                if (apkUrl.isBlank()) error("Brak ${UpdateConfig.APK_ASSET_NAME} w release")

                val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val current = packageInfo.versionName?.toString() ?: "0"
                val available = compareVersions(latest, current) > 0

                activity.runOnUiThread {
                    if (available) {
                        notifyUpdate(activity, latest, apkUrl, notes)
                        if (!silent || !canNotify(activity)) {
                            showDialog(activity, latest, apkUrl, notes)
                        }
                    } else {
                        clearNotification(activity)
                        if (!silent) {
                            Toast.makeText(activity, "Masz najnowszą wersję SalesAPP ($current)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (!silent) Toast.makeText(activity, "Błąd aktualizacji: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun handleUpdateIntent(activity: Activity, intent: Intent?): Boolean {
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) return false
        val version = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
        val notes = intent?.getStringExtra(EXTRA_NOTES).orEmpty()
        intent?.removeExtra(EXTRA_URL)
        intent?.removeExtra(EXTRA_VERSION)
        intent?.removeExtra(EXTRA_NOTES)
        showDialog(activity, version.ifBlank { "nowa" }, url, notes)
        return true
    }

    fun resumePendingInstall(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val url = prefs.getString(PENDING_URL, "").orEmpty()
        if (url.isBlank()) return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
        prefs.edit().remove(PENDING_URL).apply()
        downloadAndInstall(activity, url)
    }

    private fun showDialog(activity: Activity, latest: String, url: String, notes: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Dostępna nowa wersja SalesAPP")
            .setMessage(buildString {
                append("Nowa wersja: ").append(latest)
                if (notes.isNotBlank()) append("\n\n").append(notes.take(1400))
            })
            .setPositiveButton("AKTUALIZUJ") { _, _ -> downloadAndInstall(activity, url) }
            .setNegativeButton("PÓŹNIEJ", null)
            .show()
    }

    private fun canNotify(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notifyUpdate(activity: Activity, latest: String, url: String, notes: String) {
        if (!canNotify(activity)) return

        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (prefs.getString(LAST_VERSION, "") == latest &&
            now - prefs.getLong(LAST_AT, 0L) < REMIND_MS) return

        val manager = activity.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    UpdateConfig.UPDATE_CHANNEL_ID,
                    "Aktualizacje SalesAPP",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Powiadomienia o nowych wersjach aplikacji SalesAPP"
                }
            )
        }

        val open = Intent(activity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_VERSION, latest)
            putExtra(EXTRA_NOTES, notes.take(1800))
        }
        val pending = PendingIntent.getActivity(
            activity, NOTIFICATION_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(activity, UpdateConfig.UPDATE_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Dostępna nowa wersja SalesAPP")
                .setContentText("Wersja $latest — dotknij, aby zaktualizować.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Wersja $latest jest gotowa. Dotknij, aby pobrać i zainstalować aktualizację SalesAPP."
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )
        prefs.edit().putString(LAST_VERSION, latest).putLong(LAST_AT, now).apply()
    }

    private fun clearNotification(activity: Activity) {
        try { activity.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        catch (_: Exception) {}
    }

    private fun downloadAndInstall(activity: Activity, apkUrl: String) {
        thread {
            try {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Pobieranie nowej wersji SalesAPP…", Toast.LENGTH_SHORT).show()
                }
                val dir = File(activity.cacheDir, "salesapp_updates").apply { mkdirs() }
                val apk = File(dir, UpdateConfig.APK_ASSET_NAME)
                val c = URL(apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 120000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "CHLE-MAR-SalesAPP-Android")
                if (c.responseCode !in 200..299) error("Pobieranie APK HTTP ${c.responseCode}")
                c.inputStream.use { input -> apk.outputStream().use { out -> input.copyTo(out) } }
                c.disconnect()
                if (apk.length() < 100_000) error("Pobrany APK jest nieprawidłowy")

                activity.runOnUiThread {
                    if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                            .edit().putString(PENDING_URL, apkUrl).apply()
                        Toast.makeText(
                            activity,
                            "Zezwól SalesAPP na instalowanie aktualizacji i wróć do aplikacji.",
                            Toast.LENGTH_LONG
                        ).show()
                        activity.startActivity(Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        ))
                    } else {
                        installApk(activity, apk)
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Nie udało się zainstalować aktualizacji: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun compareVersions(first: String, second: String): Int {
        fun p(v: String) = v.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val a = p(first); val b = p(second)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
