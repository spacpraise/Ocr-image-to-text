package com.example.util

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.BuildConfig
import com.example.R

/**
 * User-visible status for SnapText downloads and OCR work.
 * APK transfers are delegated to Android DownloadManager so they can continue
 * independently of the Compose sheet and activity lifecycle.
 */
object SnapTextNotifications {
    const val CHANNEL_UPDATES = "snaptext_updates"
    const val CHANNEL_SCANNING = "snaptext_scanning"
    private const val PREFS = "snaptext_update_state"
    private const val KEY_DOWNLOAD_ID = "active_update_download_id"
    private const val KEY_UPDATE_VERSION = "active_update_version"
    private const val KEY_NOTIFIED_VERSION = "last_notified_update_version"
    private const val KEY_NOTIFIED_AT = "last_notified_update_at"
    private const val KEY_INSTALLED_VERSION = "last_observed_installed_version"
    private const val NOTIFICATION_UPDATE = 4101
    private const val NOTIFICATION_SCAN = 4102
    private const val NOTIFICATION_UPDATE_AVAILABLE = 4103
    private const val NOTIFICATION_UPDATE_INSTALLED = 4105

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New versions, download progress, and installation status"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SCANNING, "Text extraction", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and results while SnapText extracts text"
                setShowBadge(false)
            }
        )
    }

    private fun appPendingIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun notifyInstalledVersionIfChanged(context: Context, packageReplaced: Boolean = false) {
        ensureChannels(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentVersion = BuildConfig.VERSION_NAME
        val previousVersion = prefs.getString(KEY_INSTALLED_VERSION, null)
        if (packageReplaced || (previousVersion != null && previousVersion != currentVersion)) {
            val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("SnapText updated successfully")
                .setContentText("You're now using SnapText $currentVersion.")
                .setContentIntent(appPendingIntent(context, NOTIFICATION_UPDATE_INSTALLED))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_INSTALLED, notification)
        }
        prefs.edit().putString(KEY_INSTALLED_VERSION, currentVersion).apply()
    }

    fun notifyUpdateAvailable(context: Context, version: String) {
        ensureChannels(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastVersion = prefs.getString(KEY_NOTIFIED_VERSION, null)
        val lastNotifiedAt = prefs.getLong(KEY_NOTIFIED_AT, 0L)
        val oneDayMillis = 24L * 60L * 60L * 1000L
        if (lastVersion == version && System.currentTimeMillis() - lastNotifiedAt < oneDayMillis) return
        prefs.edit().putString(KEY_NOTIFIED_VERSION, version).putLong(KEY_NOTIFIED_AT, System.currentTimeMillis()).apply()
        val openApp = PendingIntent.getActivity(
            context, 4103,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse("snaptext://update")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SnapText update available")
            .setContentText("Version $version is ready. Tap to review the update.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("SnapText $version is available. Open the app to review and download the update."))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_AVAILABLE, notification)
    }

    fun hasCompletedDownload(context: Context): Boolean {
        val id = activeDownloadId(context)
        if (id < 0L) return false
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL && manager.getUriForDownloadedFile(id) != null
        }
    }

    fun isDownloadRunning(context: Context): Boolean {
        val id = activeDownloadId(context)
        if (id < 0L) return false
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) false else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
        }
    }

    fun enqueueUpdate(context: Context, apkUrl: String, version: String): Long {
        require(apkUrl.startsWith("https://")) { "The update link must use HTTPS." }
        ensureChannels(context)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("SnapText $version")
            .setDescription("Downloading app update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "snaptext-update-$version.apk")
        val id = manager.enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_UPDATE_VERSION, version)
            .putString(KEY_INSTALLED_VERSION, BuildConfig.VERSION_NAME)
            .apply()
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_UPDATE,
            NotificationCompat.Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("SnapText update downloading")
                .setContentText("Downloading version $version. You can keep using SnapText.")
                .setContentIntent(appPendingIntent(context, 4101))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
        return id
    }

    fun activeDownloadId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1L)

    fun completedDownloadUri(context: Context): Uri? {
        val id = activeDownloadId(context)
        if (id < 0L) return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        manager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return null
        }
        return manager.getUriForDownloadedFile(id)
    }

    fun showScanStarted(context: Context) {
        ensureChannels(context)
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_SCAN,
            NotificationCompat.Builder(context, CHANNEL_SCANNING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Extracting text")
                .setContentText("SnapText is processing your image.")
                .setContentIntent(appPendingIntent(context, 4102))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    fun showScanCompleted(context: Context, wordCount: Int) {
        ensureChannels(context)
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_SCAN,
            NotificationCompat.Builder(context, CHANNEL_SCANNING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Text extraction complete")
                .setContentText(if (wordCount == 1) "Extracted 1 word. Your result is ready in SnapText." else "Extracted $wordCount words. Your result is ready in SnapText.")
                .setContentIntent(appPendingIntent(context, 4102))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    fun showScanFailed(context: Context) {
        ensureChannels(context)
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_SCAN,
            NotificationCompat.Builder(context, CHANNEL_SCANNING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Text extraction needs attention")
                .setContentText("SnapText couldn't finish this scan. Open the app to retry.")
                .setContentIntent(appPendingIntent(context, 4102))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    internal fun handleDownloadComplete(context: Context, completedId: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (completedId != prefs.getLong(KEY_DOWNLOAD_ID, -1L)) return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val status = manager.query(DownloadManager.Query().setFilterById(completedId)).use { cursor ->
            if (!cursor.moveToFirst()) return
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_UPDATE)
        val version = prefs.getString(KEY_UPDATE_VERSION, "latest") ?: "latest"
        val success = status == DownloadManager.STATUS_SUCCESSFUL
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (success) intent.setData(Uri.parse("snaptext://update-install"))
        val pending = PendingIntent.getActivity(
            context, 4104, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (success) "Update ready to install" else "Update download failed")
            .setContentText(if (success) "SnapText $version has downloaded. Tap to continue installation." else "SnapText couldn't download the update. Open the app to try again.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE, notification)
    }
}

class SnapTextPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SnapTextNotifications.notifyInstalledVersionIfChanged(context, packageReplaced = true)
        }
    }
}

class SnapTextDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id >= 0L) SnapTextNotifications.handleDownloadComplete(context, id)
        }
    }
}
