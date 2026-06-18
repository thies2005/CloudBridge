package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger
import java.util.Random

class SyncManager(private var mContext: Context) {

    companion object {
        private val SYNC_WORK_TAG = "sync_work"
    }

    fun queue(trigger: Trigger) {
        queue(trigger.triggerTarget)
    }

    fun queue(task: Task) {
        queue(task.id)
    }

    fun queue(taskID: Long) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
        data.putLong(SyncWorker.TASK_ID, taskID)

        uploadWorkRequest.setInputData(data.build())
        uploadWorkRequest.addTag(taskID.toString())
        uploadWorkRequest.addTag(SYNC_WORK_TAG)
        work(uploadWorkRequest.build())
    }

    fun queueEphemeral(task: Task) {

        task.id = Random().nextLong()
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
        data.putString(SyncWorker.TASK_EPHEMERAL, task.asJSON().toString())

        uploadWorkRequest.setInputData(data.build())
        uploadWorkRequest.addTag(task.id.toString())
        uploadWorkRequest.addTag(SYNC_WORK_TAG)
        work(uploadWorkRequest.build())
    }

    private fun work(request: WorkRequest) {
        WorkManager.getInstance(mContext)
            .enqueue(request)
    }

    fun cancel() {
        WorkManager.getInstance(mContext)
            .cancelAllWorkByTag(SYNC_WORK_TAG)
    }
    fun cancel(tag: String) {

        //Intent syncIntent = new Intent(context, SyncService.class);
        //syncIntent.setAction(TASK_CANCEL_ACTION);
        //syncIntent.putExtra(EXTRA_TASK_ID, intent.getLongExtra(EXTRA_TASK_ID, -1));
        //context.startService(syncIntent);
        Log.e("TAG", "CANCEL"+tag)
        WorkManager
            .getInstance(mContext)
            .cancelAllWorkByTag(tag)
    }
}