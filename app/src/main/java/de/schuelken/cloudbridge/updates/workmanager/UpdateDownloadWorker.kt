package de.schuelken.cloudbridge.updates.workmanager

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.BuildConfig
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.PermissionManager
import de.schuelken.cloudbridge.extensions.tag
import de.schuelken.cloudbridge.notifications.AppUpdateNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateDownloadWorker(
    private val mContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(mContext, workerParams) {

    companion object {
        private const val REPO_OWNER = "thies2005"
        private const val REPO_NAME = "CloudBridge"
        private const val UPDATE_APK_NAME = "cloudbridge_update.apk"
        private const val HEADER_ACCEPT = "application/vnd.github+json"
        private const val HEADER_USER_AGENT = "CloudBridge-Updater"
        private const val DIGEST_PREFIX = "sha256:"
        private const val UNIVERSAL_ABI = "universal"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferenceManager = PreferenceManager.getDefaultSharedPreferences(mContext)
        val versionKey = mContext.getString(R.string.pref_key_app_updates_found_update_for_version)
        val version = preferenceManager.getString(versionKey, "") ?: ""
        if (version.isEmpty()) {
            Log.e(tag(), "No target update version stored, aborting")
            return@withContext Result.failure()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        try {
            val asset = resolveAsset(client, version)
            if (asset == null) {
                Log.e(tag(), "No matching release asset for version $version")
                notifyFailure()
                return@withContext Result.failure()
            }

            val targetDir = mContext.externalCacheDir
            if (targetDir == null) {
                Log.e(tag(), "externalCacheDir is null, cannot download update")
                notifyFailure()
                return@withContext Result.failure()
            }

            val downloaded = downloadAsset(client, asset, targetDir)
            if (!verifyAsset(downloaded, asset)) {
                Log.e(tag(), "Asset verification failed for ${asset.name}")
                if (!downloaded.delete()) {
                    Log.w(tag(), "Failed to delete corrupt download ${downloaded.absolutePath}")
                }
                notifyFailure()
                return@withContext Result.failure()
            }

            if (!launchInstaller(downloaded)) {
                return@withContext Result.failure()
            }
            Result.success()
        } catch (e: IOException) {
            Log.e(tag(), "Network error downloading update", e)
            notifyFailure()
            Result.failure()
        } catch (e: Exception) {
            Log.e(tag(), "Failed to download/verify update", e)
            notifyFailure()
            Result.failure()
        }
    }

    private fun resolveAsset(client: OkHttpClient, version: String): Asset? {
        val url = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/tags/${Uri.encode(version)}"
        Log.e(tag(), "Fetching release metadata: $url")
        val request = Request.Builder()
            .url(url)
            .header("Accept", HEADER_ACCEPT)
            .header("User-Agent", HEADER_USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(tag(), "Release API returned HTTP ${response.code}")
                return null
            }
            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                Log.e(tag(), "Release API returned empty body")
                return null
            }
            val assetsArray = JSONObject(body).optJSONArray("assets")
            if (assetsArray == null) {
                Log.e(tag(), "Release JSON has no assets array")
                return null
            }

            val abiTokens = ArrayList<String>()
            Build.SUPPORTED_ABIS.forEach { abi ->
                if (abi.isNotEmpty() && abi !in abiTokens) abiTokens.add(abi)
            }
            if (UNIVERSAL_ABI !in abiTokens) abiTokens.add(UNIVERSAL_ABI)

            for (token in abiTokens) {
                for (i in 0 until assetsArray.length()) {
                    val obj = assetsArray.getJSONObject(i)
                    val name = obj.optString("name")
                    if (name.isNotEmpty() && name.contains("-$token-", ignoreCase = true)) {
                        Log.e(tag(), "Matched asset '$name' for ABI token '$token'")
                        return Asset(
                            name = name,
                            downloadUrl = obj.optString("browser_download_url"),
                            size = obj.optLong("size", -1L),
                            digest = obj.optString("digest").ifEmpty { null }
                        )
                    }
                }
            }
            Log.e(tag(), "No asset matched any supported ABI")
            return null
        }
    }

    @Throws(IOException::class)
    private fun downloadAsset(client: OkHttpClient, asset: Asset, targetDir: File): File {
        val target = File(targetDir, UPDATE_APK_NAME)
        if (target.exists() && !target.delete()) {
            Log.w(tag(), "Could not delete previous download ${target.absolutePath}")
        }
        if (asset.downloadUrl.isEmpty()) {
            throw IOException("Asset has empty browser_download_url")
        }
        Log.e(tag(), "Downloading ${asset.downloadUrl} -> ${target.absolutePath}")
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("User-Agent", HEADER_USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download HTTP ${response.code}")
            }
            val source = response.body?.byteStream()
                ?: throw IOException("Download response body is null")
            target.outputStream().use { sink ->
                source.copyTo(sink)
            }
        }
        return target
    }

    private fun verifyAsset(file: File, asset: Asset): Boolean {
        val digest = asset.digest
        if (digest != null) {
            if (!digest.startsWith(DIGEST_PREFIX, ignoreCase = true)) {
                Log.w(tag(), "Unrecognized digest format '$digest'; skipping hash check")
            } else {
                val expected = digest.substring(DIGEST_PREFIX.length).trim().lowercase()
                val actual = sha256(file).lowercase()
                if (actual != expected) {
                    Log.e(tag(), "SHA-256 mismatch: expected=$expected actual=$actual")
                    return false
                }
                Log.e(tag(), "SHA-256 verified for ${asset.name}")
                return true
            }
        }
        if (asset.size > 0L) {
            if (file.length() != asset.size) {
                Log.e(tag(), "Size mismatch: expected=${asset.size} actual=${file.length()}")
                return false
            }
            Log.e(tag(), "Size verified for ${asset.name} (${file.length()} bytes)")
        } else {
            Log.w(tag(), "No digest or size available; accepting unverified download")
        }
        return true
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun launchInstaller(file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                mContext,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            mContext.startActivity(intent)
            Log.e(tag(), "Launched installer for ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(tag(), "No activity to handle installer intent", e)
            notifyFailure()
            false
        }
    }

    @SuppressLint("MissingPermission") // Guarded by PermissionManager.grantedNotifications()
    private fun notifyFailure() {
        if (!PermissionManager(mContext).grantedNotifications()) {
            Log.w(tag(), "Notification permission not granted; cannot show failure notification")
            return
        }
        ensureChannel()
        val notification = NotificationCompat.Builder(mContext, AppUpdateNotification.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.appicon)
            .setContentTitle("Update download failed")
            .setContentText("CloudBridge could not download or verify the update.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(mContext)
            .notify(AppUpdateNotification.NOTIFICATION_ID + 1, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = mContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.notificationChannels.any { it.id == AppUpdateNotification.NOTIFICATION_CHANNEL_ID }) return
        val channel = NotificationChannel(
            AppUpdateNotification.NOTIFICATION_CHANNEL_ID,
            "Update Notifications",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows notifications related to app updates" }
        manager.createNotificationChannel(channel)
    }

    private data class Asset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
        val digest: String?
    )
}
