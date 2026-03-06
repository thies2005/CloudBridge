Ah, `EphemeralWorker` is used by `EphemeralTaskManager.kt`, which sets up tasks with types DOWNLOAD, UPLOAD, MOVE, DELETE.
There is a comment in `SyncWorker.kt`: "this is currently only a useless mapper. It is supposed to keep this worker in sync with the ephemeral one. when they are merged eventually, this can be easily extracted."
Both workers have `private var silentRun = false`.

I will add the logic to support silent mode in `SyncWorker` and `EphemeralWorker` by reading it from `inputData` (or maybe `SyncWorker.EXTRA_TASK_SILENT`), and making sure `SyncManager.kt` queues it appropriately.

Wait, `SyncService.kt` calls `SyncManager(this).queue(task)`. We should change it to `queue(task, isSilent)` where `isSilent = !intent.getBooleanExtra("notification", true)`.

Let's modify `SyncWorker.kt`:
```kotlin
    override fun doWork(): Result {
        silentRun = inputData.getBoolean(EXTRA_TASK_SILENT, false)
        prepareNotifications()
        // ...
```

Let's modify `SyncManager.kt`:
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
Wait, `SyncManager` also has `queue(trigger: Trigger)`, which should perhaps also support `isSilent`? `TriggerService` or `TriggerReceiver` queues triggers. Do they use silent mode?
Let's check `TriggerService.java` or wherever it queues.
It calls `SyncManager.queue(trigger)`. Let's just leave `queue(trigger)` alone and not pass `isSilent` since it doesn't have an intention of silent mode yet, unless we want to propagate it. We can just add a default `isSilent = false`.

Let's check `SyncService.kt`:
```kotlin
class SyncService: IntentService("ca.pkay.rcexplorer.SYNC_SERCVICE"){
    override fun onHandleIntent(intent: Intent?) {
        if(intent == null){
            return
        }

        val action = intent.action
        val taskId = intent.getIntExtra(SyncWorker.EXTRA_TASK_ID, -1)
        // If "notification" extra is true (the default), silentRun is false.
        val silentRun = !intent.getBooleanExtra(SyncWorker.EXTRA_TASK_SILENT, true)

        if (action.equals(SyncWorker.TASK_SYNC_ACTION)) {
            val db = DatabaseHandler(this)
            for (task in db.allTasks) {
                if (task.id == taskId.toLong()) {
                    SyncManager(this).queue(task, silentRun)
                }
            }
        }
    }
}
```

Is there anything else that needs to pass `isSilent`?
What about `EphemeralWorker`? Let's check `EphemeralTaskManager.kt` and `EphemeralWorker.kt`. They also have `private var silentRun = false`. We can add reading from `inputData` if needed. However, the task specifically says: "Allow SyncWorker to run in silent mode or remove extra".
So I only strictly need to fix `SyncWorker`, `SyncService`, and `SyncManager`.

Let's write a python script to search and replace effectively.
