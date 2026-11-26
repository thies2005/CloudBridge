package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import android.os.Parcel
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.util.FLog
import de.schuelken.cloudbridge.notifications.implementations.DeleteWorkerNotification
import de.schuelken.cloudbridge.notifications.implementations.DownloadWorkerNotification
import de.schuelken.cloudbridge.notifications.implementations.MoveWorkerNotification
import de.schuelken.cloudbridge.notifications.implementations.UploadWorkerNotification
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EphemeralTaskManager(private var mContext: Context) {

    companion object {

        private const val EPHEMERAL_WORK_TAG = "ephemeral_work"
        private const val TRANSFER_BATCH_TAG = "transfer_batch"
        private val batchSweepScheduled = AtomicBoolean()

        fun queueDownload(
            context: Context,
            remote: RemoteItem,
            downloadItem: FileItem,
            selectedPath: String) {

            DownloadWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.DOWNLOAD.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.DOWNLOAD_TARGETPATH, selectedPath)

            addFileItemToData(EphemeralWorker.DOWNLOAD_SOURCE, downloadItem, data)
            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
        }

        @JvmOverloads
        fun queueUpload(
            context: Context,
            remote: RemoteItem,
            file: String,
            targetpath: String,
            deleteStagedUpload: Boolean = false) {

            UploadWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.UPLOAD.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.UPLOAD_TARGETPATH, targetpath)
            data.putString(EphemeralWorker.UPLOAD_FILE, file)
            data.putBoolean(EphemeralWorker.DELETE_STAGED_UPLOAD, deleteStagedUpload)

            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
        }

        fun queueDownloads(context: Context, remote: RemoteItem, files: List<FileItem>, target: String) {
            val selection = files.map { TransferBatch.Selection(it.path, !it.isDir) }
            queueTransfers(context, remote, target, Type.DOWNLOAD, selection) { index ->
                queueDownload(context, remote, files[index], target)
            }
        }

        fun queueUploads(context: Context, remote: RemoteItem, files: List<File>, target: String) {
            val selection = files.map { TransferBatch.Selection(it.path, it.isFile) }
            queueTransfers(context, remote, target, Type.UPLOAD, selection) { index ->
                queueUpload(context, remote, files[index].path, target)
            }
        }

        private fun queueTransfers(
            context: Context,
            remote: RemoteItem,
            target: String,
            type: Type,
            selection: List<TransferBatch.Selection>,
            queueSingle: (Int) -> Unit
        ) {
            for (group in TransferBatch.groups(selection)) {
                val entries = group.map(selection::get)
                if (entries.size == 1 || !queueBatch(context, remote, target, type, entries)) {
                    group.forEach(queueSingle)
                }
            }
        }

        private fun queueBatch(
            context: Context,
            remote: RemoteItem,
            target: String,
            type: Type,
            entries: List<TransferBatch.Selection>
        ): Boolean {
            val directory = File(context.noBackupFilesDir, "transfer-batches")
            val manager = WorkManager.getInstance(context)
            sweepAbandonedBatches(context, manager, directory)
            val workId = UUID.randomUUID()
            val filesFrom = File(directory, "$workId.files")
            try {
                directory.mkdirs()
                filesFrom.writeText(entries.joinToString("\n", postfix = "\n") { it.name })
            } catch (e: IOException) {
                filesFrom.delete()
                FLog.w("EphemeralTaskManager", "Could not stage transfer batch", e)
                return false
            }

            val targetKey = if (type == Type.DOWNLOAD) {
                DownloadWorkerNotification(context).generateChannels()
                EphemeralWorker.DOWNLOAD_TARGETPATH
            } else {
                UploadWorkerNotification(context).generateChannels()
                EphemeralWorker.UPLOAD_TARGETPATH
            }
            val data = Data.Builder()
                .putString(EphemeralWorker.EPHEMERAL_TYPE, type.name)
                .putString(targetKey, target)
                .putString(EphemeralWorker.BATCH_MANIFEST, filesFrom.name)
                .putString(EphemeralWorker.BATCH_SOURCE_PARENT, entries.first().parent)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)
            val request = OneTimeWorkRequestBuilder<EphemeralWorker>()
                .setId(workId)
                .setInputData(data.build())
                .addTag(EPHEMERAL_WORK_TAG)
                .addTag(TRANSFER_BATCH_TAG)
                .build()
            val result = manager.enqueue(request).result
            result.addListener({
                try {
                    result.get()
                    cleanBatchOnCompletion(context, request.id, filesFrom)
                } catch (e: Exception) {
                    filesFrom.delete()
                    FLog.e("EphemeralTaskManager", "Could not enqueue transfer batch", e)
                }
            }, ContextCompat.getMainExecutor(context))
            return true
        }

        private fun sweepAbandonedBatches(context: Context, manager: WorkManager, directory: File) {
            if (!batchSweepScheduled.compareAndSet(false, true)) return
            val snapshot = manager.getWorkInfosByTag(TRANSFER_BATCH_TAG)
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            snapshot.addListener({
                try {
                    val active = snapshot.get().filterNot { it.state.isFinished }.map { it.id.toString() }.toSet()
                    // Files this old were staged before the snapshot. Ignore current staging and
                    // any work that WorkManager can still start or restart.
                    directory.listFiles()?.filter {
                        it.lastModified() < cutoff && it.nameWithoutExtension !in active
                    }?.forEach { it.delete() }
                } catch (e: Exception) {
                    FLog.w("EphemeralTaskManager", "Could not clean abandoned transfer batches", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }

        // The scheduler can restart a stopped worker with the same input, so remove the
        // manifest only after WorkManager records a terminal state. A restarted worker
        // registers again when its previous process cannot observe the transition.
        private val observedBatches = mutableSetOf<UUID>()

        internal fun cleanBatchOnCompletion(context: Context, workId: UUID, manifest: File) {
            val appContext = context.applicationContext
            ContextCompat.getMainExecutor(appContext).execute {
                if (!observedBatches.add(workId)) return@execute
                val work = WorkManager.getInstance(appContext).getWorkInfoByIdLiveData(workId)
                work.observeForever(object : Observer<WorkInfo?> {
                    override fun onChanged(info: WorkInfo?) {
                        if (info?.state?.isFinished == true) {
                            work.removeObserver(this)
                            observedBatches.remove(workId)
                            manifest.delete()
                        }
                    }
                })
            }
        }

        fun queueMove(
            context: Context,
            remote: RemoteItem,
            currentPath: String,
            file: FileItem,
            readablePath: String
        ) {

            MoveWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.MOVE.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            addFileItemToData(EphemeralWorker.MOVE_FILE, file, data)
            data.putString(EphemeralWorker.MOVE_TARGETPATH, currentPath)
            data.putString(EphemeralWorker.MOVE_SOURCEPATH, readablePath)
            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
        }

        /**
         * Queues one delete worker for the whole selection. WorkManager's `Data` is capped at
         * 10 KB, so large selections are handed over via a manifest file in the cache dir.
         */
        fun queueDelete(
            context: Context,
            remote: RemoteItem,
            files: List<FileItem>,
            currentPath: String
        ) {

            DeleteWorkerNotification(context).generateChannels()

            // A queued job can be cancelled or lost (update, hard process kill) before its worker
            // ever runs; sweep leftovers older than a day so the cache dir stays clean.
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            context.cacheDir.listFiles { file ->
                (file.name.startsWith("delete-manifest-") || file.name.startsWith("delete-files-from-"))
                        && file.lastModified() < cutoff
            }?.forEach { it.delete() }

            val manifest = File(context.cacheDir, "delete-manifest-${UUID.randomUUID()}.txt")
            manifest.writeText(DeleteManifest.encode(files.map {
                DeleteEntry(path = it.path, name = it.name, isDir = it.isDir)
            }))

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.DELETE.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.DELETE_MANIFEST, manifest.absolutePath)
            data.putString(EphemeralWorker.DELETE_PATH, currentPath)
            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
        }

        private fun addFileItemToData(key: String, fileItem: FileItem, data: Data.Builder){
            val parcel = Parcel.obtain()
            try {
                fileItem.writeToParcel(parcel, 0)
                data.putByteArray(key, parcel.marshall())
            } finally {
                parcel.recycle()
            }
        }

        private fun addRemoteItemToData(key: String, remote: RemoteItem, data: Data.Builder){
            val parcel = Parcel.obtain()
            try {
                remote.writeToParcel(parcel, 0)
                data.putByteArray(key, parcel.marshall())
            } finally {
                parcel.recycle()
            }
        }
    }


    protected fun work(inputData: Data, tag: String) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<EphemeralWorker>()
        uploadWorkRequest.setInputData(inputData)
        uploadWorkRequest.addTag(tag)
        WorkManager.getInstance(mContext).enqueue(uploadWorkRequest.build())
    }

    fun cancel() {
        WorkManager.getInstance(mContext)
            .cancelAllWorkByTag(EPHEMERAL_WORK_TAG)
    }
    fun cancel(tag: String) {
        WorkManager
            .getInstance(mContext)
            .cancelAllWorkByTag(tag)
    }
}
