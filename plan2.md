If I check `SyncWorker.kt` again:
```kotlin
        //those Extras do not follow the above schema, because they are exposed to external applications
        //That means shorter values make it easier to use. There is no other technical reason
        const val TASK_SYNC_ACTION = "START_TASK"
        const val TASK_CANCEL_ACTION = "CANCEL_TASK"
        const val EXTRA_TASK_ID = "task"

        // Todo: Allow SyncWorker to run in silent mode, or remove this!
        const val EXTRA_TASK_SILENT = "notification"
```
So external applications can call `SyncService` with `"notification"` set to a boolean.
If `"notification"` is `false`, it means they don't want notifications at the end.

Let's modify `SyncManager.kt` to accept an `isSilent` parameter:

```kotlin
    fun queue(task: Task, isSilent: Boolean = false) {
        queue(task.id, isSilent)
    }

    fun queue(taskID: Long, isSilent: Boolean = false) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
        data.putLong(SyncWorker.TASK_ID, taskID)
        data.putBoolean(SyncWorker.EXTRA_TASK_SILENT, isSilent)

        uploadWorkRequest.setInputData(data.build())
        uploadWorkRequest.addTag(taskID.toString())
        work(uploadWorkRequest.build())
    }
```
Wait, if `"notification"` means "show notification" and defaults to true:
In `SyncService.kt`:
```kotlin
        val showNotification = intent.getBooleanExtra(SyncWorker.EXTRA_TASK_SILENT, true)
        val isSilent = !showNotification
```
And pass `isSilent` to `SyncManager`.

Let's check if `EphemeralWorker` also has `EXTRA_TASK_SILENT` or `silentRun`.
Yes, `EphemeralWorker.kt:82:    private var silentRun = false`
`EphemeralWorker.kt:259:        if (silentRun) {`
Does `EphemeralWorker` have the same issue?
