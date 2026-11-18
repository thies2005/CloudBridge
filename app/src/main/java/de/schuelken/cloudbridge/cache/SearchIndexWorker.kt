package de.schuelken.cloudbridge.cache

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Activities.MainActivity
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.ProcessRegistry
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.GenericSyncNotification
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications
import ca.pkay.rcloneexplorer.notifications.support.StatusObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class SearchIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val remoteName = inputData.getString(KEY_REMOTE_NAME) ?: return@withContext Result.failure()
        val cacheManager = CacheManager.getInstance(applicationContext)
        if (!cacheManager.isTier3SearchEnabled(remoteName)) {
            return@withContext Result.success()
        }

        val rclone = Rclone(applicationContext)
        val remote: RemoteItem = rclone.remotes.firstOrNull { it.name == remoteName }
            ?: return@withContext Result.failure()
        RemoteItem.prepareDisplay(applicationContext, listOf(remote))
        val displayName = remote.displayName?.takeIf { it.isNotBlank() } ?: remoteName

        val taskKey = taskKeyFor(remoteName)
        val processTitle = applicationContext.getString(R.string.search_index_process_title, displayName)
        val preparingText = applicationContext.getString(R.string.search_index_preparing)
        val notificationId = NOTIFICATION_ID_BASE + (abs(remoteName.hashCode()) % 10000)

        ensureNotificationChannel()
        val statusHelper = StatusObject(applicationContext)
        val startMs = System.currentTimeMillis()
        val processRef = AtomicReference<Process>()

        val jobHandle = coroutineContext[Job]?.invokeOnCompletion {
            processRef.getAndSet(null)?.destroy()
        }

        ProcessRegistry.registerTask(
            taskKey,
            ProcessRegistry.OPERATION_SEARCH_INDEX,
            processTitle,
            0,
            false,
            preparingText,
            "",
            "",
            emptyList()
        )
        try {
            setForeground(
                createForegroundInfo(
                    notificationId,
                    processTitle,
                    preparingText,
                    arrayListOf(preparingText),
                    0,
                    indeterminate = true
                )
            )
        } catch (_: Exception) {
        }

        try {
            val prevStats = cacheManager.getIndexStats(remoteName)
            val about = if (prevStats == null || prevStats.itemCount <= 0) {
                rclone.aboutRemote(remote)
            } else {
                null
            }
            if (isStopped) return@withContext Result.failure()

            val rootItems = cacheManager.getDirectoryListing(remote, "")?.items
                ?: rclone.getDirectoryContent(remote, "", false)
                ?: emptyList()
            val topLevelDirs = rootItems.filter { it.isDir }.mapTo(HashSet()) { it.name }
            val seenTopDirs = HashSet<String>()
            var lastNotifyMs = 0L

            val items = rclone.getDirectoryContentRecursive(
                remote,
                "",
                false,
                MAX_INDEX_DEPTH,
                MAX_INDEX_ITEMS,
                processRef,
                Rclone.RecursiveListProgressListener { itemsFound, bytesFound, currentPath ->
                    processRef.get()?.let { proc ->
                        ProcessRegistry.associateProcessWithTask(proc, taskKey)
                    }
                    if (isStopped) {
                        processRef.get()?.destroy()
                        return@RecursiveListProgressListener
                    }
                    if (!currentPath.isNullOrEmpty()) {
                        val topSeg = currentPath.trimStart('/').substringBefore('/')
                        if (topSeg in topLevelDirs) {
                            seenTopDirs.add(topSeg)
                        }
                    }
                    if (itemsFound <= 0) return@RecursiveListProgressListener

                    val percent = estimateScanPercent(
                        itemsFound = itemsFound,
                        bytesFound = bytesFound,
                        prevItemCount = prevStats?.itemCount ?: 0,
                        aboutUsedBytes = if (about != null && !about.hasFailed()) about.used else 0L,
                        topDirCount = topLevelDirs.size,
                        seenTopDirCount = seenTopDirs.size
                    )
                    val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1000L)
                    val elapsedSeconds = (elapsedMs / 1000L).toInt()
                    val itemsPerSec = ((itemsFound * 1000L) / elapsedMs).toInt().coerceAtLeast(1)
                    val etaSeconds = estimateEtaSeconds(elapsedMs, percent)
                    val etaFormatted = statusHelper.prettyPrintDuration(etaSeconds)
                    val elapsedFormatted = statusHelper.prettyPrintDuration(elapsedSeconds)
                    val speedSummary = "$itemsPerSec items/s"
                    val content = applicationContext.getString(
                        R.string.search_index_progress_short,
                        itemsFound,
                        etaFormatted
                    )
                    val details = arrayListOf(
                        applicationContext.getString(R.string.search_index_items_mapped, itemsFound),
                        applicationContext.getString(R.string.search_index_speed, itemsPerSec),
                        applicationContext.getString(R.string.sync_notification_remaining, etaFormatted),
                        applicationContext.getString(R.string.sync_notification_elapsed, elapsedFormatted)
                    )
                    if (!currentPath.isNullOrEmpty()) {
                        details.add(applicationContext.getString(R.string.search_index_current_path, currentPath))
                    }

                    ProcessRegistry.updateTaskProgress(
                        taskKey,
                        processTitle,
                        percent,
                        content,
                        etaFormatted,
                        speedSummary,
                        details
                    )

                    val now = System.currentTimeMillis()
                    if (now - lastNotifyMs >= 500L) {
                        lastNotifyMs = now
                        setForegroundAsync(
                            createForegroundInfo(
                                notificationId,
                                processTitle,
                                content,
                                details,
                                percent,
                                indeterminate = false
                            )
                        )
                    }
                }
            ) ?: return@withContext if (isStopped) Result.failure() else Result.retry()

            if (isStopped) return@withContext Result.failure()

            cacheManager.saveRecursiveIndex(remoteName, items) { written, total ->
                val percent = if (total <= 0) {
                    100
                } else {
                    (85 + ((written * 15) / total)).coerceIn(85, 100)
                }
                val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1000L)
                val elapsedSeconds = (elapsedMs / 1000L).toInt()
                val etaSeconds = estimateEtaSeconds(elapsedMs, percent)
                val etaFormatted = statusHelper.prettyPrintDuration(etaSeconds)
                val elapsedFormatted = statusHelper.prettyPrintDuration(elapsedSeconds)
                val content = applicationContext.getString(
                    R.string.search_index_saving_short,
                    written,
                    total,
                    etaFormatted
                )
                val details = arrayListOf(
                    applicationContext.getString(R.string.search_index_items_mapped, total),
                    applicationContext.getString(R.string.sync_notification_remaining, etaFormatted),
                    applicationContext.getString(R.string.sync_notification_elapsed, elapsedFormatted)
                )
                ProcessRegistry.updateTaskProgress(
                    taskKey,
                    processTitle,
                    percent,
                    content,
                    etaFormatted,
                    "",
                    details
                )
                val now = System.currentTimeMillis()
                if (written == total || now - lastNotifyMs >= 500L) {
                    lastNotifyMs = now
                    setForegroundAsync(
                        createForegroundInfo(
                            notificationId,
                            processTitle,
                            content,
                            details,
                            percent,
                            indeterminate = false
                        )
                    )
                }
            }

            val updatedIntent = Intent(ACTION_SEARCH_INDEX_UPDATED).apply {
                putExtra(KEY_REMOTE_NAME, remoteName)
            }
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(updatedIntent)
            Result.success()
        } finally {
            jobHandle?.dispose()
            ProcessRegistry.unregisterTask(taskKey)
            NotificationManagerCompat.from(applicationContext).cancel(notificationId)
        }
    }

    private fun ensureNotificationChannel() {
        GenericSyncNotification(applicationContext).setNotificationChannel(
            SyncServiceNotifications.CHANNEL_ID,
            applicationContext.getString(R.string.sync_service_notification_channel_title),
            applicationContext.getString(R.string.sync_service_notification_channel_description),
            SyncServiceNotifications.GROUP_ID,
            applicationContext.getString(R.string.sync_service_notification_group)
        )
    }

    private fun createForegroundInfo(
        notificationId: Int,
        title: String,
        content: String,
        bigText: ArrayList<String>,
        percent: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val builder: NotificationCompat.Builder = GenericSyncNotification(applicationContext)
            .updateGenericNotification(
                title,
                content,
                R.drawable.ic_twotone_rounded_cloud_sync_24,
                bigText,
                percent,
                MainActivity::class.java,
                cancelIntent,
                SyncServiceNotifications.CHANNEL_ID
            )
            .setOngoing(true)
            .setProgress(100, percent, indeterminate)

        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_REMOTE_NAME = "remote_name"
        const val ACTION_SEARCH_INDEX_UPDATED = "de.schuelken.cloudbridge.SEARCH_INDEX_UPDATED"
        private const val MAX_INDEX_DEPTH = 12
        private const val MAX_INDEX_ITEMS = 25000
        private const val NOTIFICATION_ID_BASE = 41000

        @JvmStatic
        fun taskKeyFor(remoteName: String): String = "search_index_$remoteName"

        internal fun estimateScanPercent(
            itemsFound: Int,
            bytesFound: Long,
            prevItemCount: Int,
            aboutUsedBytes: Long,
            topDirCount: Int,
            seenTopDirCount: Int
        ): Int {
            if (itemsFound <= 0) return 0
            val fraction = when {
                prevItemCount > 0 ->
                    (itemsFound.toDouble() / prevItemCount).coerceIn(0.02, 0.98)
                aboutUsedBytes > 0L && bytesFound > 0L ->
                    (bytesFound.toDouble() / aboutUsedBytes).coerceIn(0.02, 0.98)
                topDirCount >= 2 && seenTopDirCount > 0 ->
                    (((seenTopDirCount - 0.5) / topDirCount) * 0.7 +
                        (itemsFound.toDouble() / (itemsFound + 400.0)) * 0.3).coerceIn(0.02, 0.95)
                else ->
                    (itemsFound.toDouble() / (itemsFound + 500.0)).coerceIn(0.02, 0.95)
            }
            return (1 + (fraction * 84.0).toInt()).coerceIn(1, 85)
        }

        internal fun estimateEtaSeconds(elapsedMs: Long, percent: Int): Int {
            if (percent >= 100) return 0
            if (percent <= 0 || elapsedMs <= 0L) return 1
            return ((elapsedMs * (100 - percent)) / percent / 1000L).toInt().coerceAtLeast(1)
        }

        @JvmStatic
        fun enqueueIndexRemote(context: Context, remoteName: String) {
            val data = Data.Builder()
                .putString(KEY_REMOTE_NAME, remoteName)
                .build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SearchIndexWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    "tier3_index_$remoteName",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
