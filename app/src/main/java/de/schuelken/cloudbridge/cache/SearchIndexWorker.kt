package de.schuelken.cloudbridge.cache

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Rclone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val items = rclone.getDirectoryContentRecursive(remote, "", false, MAX_INDEX_DEPTH, MAX_INDEX_ITEMS, null)
            ?: return@withContext Result.retry()

        cacheManager.saveRecursiveIndex(remoteName, items)
        Result.success()
    }

    companion object {
        const val KEY_REMOTE_NAME = "remote_name"
        private const val MAX_INDEX_DEPTH = 12
        private const val MAX_INDEX_ITEMS = 25000

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
