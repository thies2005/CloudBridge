Plan:
1. Edit `app/src/main/java/ca/pkay/rcloneexplorer/workmanager/SyncManager.kt`
   - Update `fun queue(task: Task)` to `fun queue(task: Task, isSilent: Boolean = false)` and call `queue(task.id, isSilent)`.
   - Update `fun queue(taskID: Long)` to `fun queue(taskID: Long, isSilent: Boolean = false)`
   - Within `queue(taskID, isSilent)`, add `data.putBoolean(SyncWorker.EXTRA_TASK_SILENT, isSilent)`

2. Edit `app/src/main/java/ca/pkay/rcloneexplorer/workmanager/SyncWorker.kt`
   - In `doWork()`, read `EXTRA_TASK_SILENT`:
     `silentRun = inputData.getBoolean(EXTRA_TASK_SILENT, false)`
   - Maybe replace the `Todo` comment with standard code.

3. Edit `app/src/main/java/ca/pkay/rcloneexplorer/Services/SyncService.kt`
   - Read `val showNotification = intent.getBooleanExtra(SyncWorker.EXTRA_TASK_SILENT, true)`
   - Compute `val isSilent = !showNotification`
   - Pass `isSilent` to `SyncManager(this).queue(task, isSilent)`

This matches the issue description ("Allow SyncWorker to run in silent mode or remove extra") by actually implementing the silent mode support using the existing `EXTRA_TASK_SILENT` extra which external applications can provide.
