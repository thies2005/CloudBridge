Wait, the issue description is:
"Allow SyncWorker to run in silent mode or remove extra"

If I check `SyncWorker.kt`, there's a comment:
```kotlin
        // Todo: Allow SyncWorker to run in silent mode, or remove this!
        const val EXTRA_TASK_SILENT = "notification"
```
And `private var silentRun = false` in `SyncWorker.kt` is never modified except when initialized.

In `SyncService.kt`:
```kotlin
        val silentRun = intent.getBooleanExtra("notification", true)
```

The easiest and safest fix is to implement reading `EXTRA_TASK_SILENT` in `SyncWorker` and modifying `SyncManager` to pass the extra, as well as fixing `SyncService` to extract it and pass it to `SyncManager`. Or, just passing `silentRun` inside `SyncManager.queue`. Wait! If `notification` is `true`, it means we *want* notifications, so `silentRun` should be `false`. `getBooleanExtra("notification", true)` defaults to `true`. So `isSilent = !intent.getBooleanExtra("notification", true)`. This means `notification` flag in intent literally means "show notifications".

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

And in `SyncWorker.kt`'s `doWork()`:
```kotlin
    override fun doWork(): Result {

        silentRun = inputData.getBoolean(EXTRA_TASK_SILENT, false)
        // ... rest of doWork
```

And in `SyncService.kt`:
```kotlin
class SyncService: IntentService("ca.pkay.rcexplorer.SYNC_SERCVICE"){
    override fun onHandleIntent(intent: Intent?) {
        if(intent == null){
            return
        }

        val action = intent.action
        val taskId = intent.getIntExtra("task", -1)
        // If "notification" is true (default), then silentRun is false.
        val silentRun = !intent.getBooleanExtra(SyncWorker.EXTRA_TASK_SILENT, true)

        if (action.equals("START_TASK")) {
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

Wait, `SyncService.kt` uses `"task"` directly too. It should use `SyncWorker.EXTRA_TASK_ID` which is `"task"`.
And in `EphemeralWorker.kt`, I can also implement `silentRun` since it has `private var silentRun = false` and checks `if (silentRun)` in `postSync()`.

Let's check if `EphemeralWorker` uses `EXTRA_TASK_SILENT`:
`EphemeralWorker` doesn't have it defined in companion object, but we can pass it similarly from `SyncManager.queueEphemeral`.
Wait, `queueEphemeral` takes `task`, and `SyncWorker.TASK_EPHEMERAL`. `EphemeralWorker` is a different worker? No, `SyncManager` queues `SyncWorker` with `TASK_EPHEMERAL`. Wait! `SyncManager.queueEphemeral` puts `SyncWorker.TASK_EPHEMERAL` into data, and enqueues `SyncWorker`! Wait, it doesn't enqueue `EphemeralWorker`!
Let's check `SyncManager.kt`:
```kotlin
    fun queueEphemeral(task: Task) {

        task.id = Random().nextLong()
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
        data.putString(SyncWorker.TASK_EPHEMERAL, task.asJSON().toString())
```
Wait, `SyncManager` enqueues `SyncWorker` even for ephemeral! Where is `EphemeralWorker` used?
