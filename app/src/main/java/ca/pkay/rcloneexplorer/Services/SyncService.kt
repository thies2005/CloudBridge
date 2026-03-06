package ca.pkay.rcloneexplorer.Services

import android.app.IntentService
import android.content.Intent
import android.util.Log
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.workmanager.SyncManager
import ca.pkay.rcloneexplorer.workmanager.SyncWorker
import de.felixnuesse.extract.extensions.tag


/**
 * This service is only meant to provide other apps
 * the ability to start a task.
 * Do not actually implement any sync changes, they only belong in the SyncManager/Worker!
 */
class SyncService: IntentService("ca.pkay.rcexplorer.SYNC_SERCVICE"){
    override fun onHandleIntent(intent: Intent?) {
        if(intent == null){
            return
        }

        val action = intent.action
        val taskId = intent.getIntExtra(SyncWorker.EXTRA_TASK_ID, -1)

        // The "notification" extra boolean controls if a notification should be shown.
        // Therefore, we run silently if it is false.
        val isSilent = !intent.getBooleanExtra(SyncWorker.EXTRA_TASK_SILENT, true)


        if (action.equals(SyncWorker.TASK_SYNC_ACTION)) {
            val db = DatabaseHandler(this)
            for (task in db.allTasks) {
                if (task.id == taskId.toLong()) {
                    SyncManager(this).queue(task, isSilent)
                }
            }
        }
    }
}