package de.schuelken.cloudbridge.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.R
import de.schuelken.cloudbridge.extensions.tag
import de.schuelken.cloudbridge.notifications.AppUpdateNotification
import de.schuelken.cloudbridge.updates.workmanager.UpdateDownloadWorker


class UpdateUserchoiceReceiver : BroadcastReceiver() {

    companion object {
        var ACTION_IGNORE = "ACTION_IGNORE"
        var ACTION_DOWNLOAD = "ACTION_DOWNLOAD"
        var IGNORE_VERSION_EXTRA = "IGNORE_VERSION_EXTRA"
        private const val WORK_TAG = "cloudbridge_update_download"
    }

    override fun onReceive(context: Context, intent: Intent) {

        val preferenceManager = PreferenceManager.getDefaultSharedPreferences(context)
        if(intent.action == ACTION_IGNORE) {
            Log.e(tag(), "Ignore current update!")
            val key = context.getString(R.string.pref_key_app_update_dismiss_current_update)
            preferenceManager.edit().putString(key, intent.getStringExtra(IGNORE_VERSION_EXTRA)).apply()
            AppUpdateNotification(context).cancelNotification()
        }

        if(intent.action == ACTION_DOWNLOAD) {
            val versionKey = context.getString(R.string.pref_key_app_updates_found_update_for_version)
            val version = preferenceManager.getString(versionKey,"")?: ""

            if(version.isNotEmpty()) {
                enqueueDownload(context)
            }
            AppUpdateNotification(context).cancelNotification()
        }
    }

    private fun enqueueDownload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_TAG, ExistingWorkPolicy.KEEP, request)
        Log.e(tag(), "Enqueued UpdateDownloadWorker")
    }
}
