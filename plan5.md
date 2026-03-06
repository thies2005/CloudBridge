I'll create the `set_plan` with this approach.
1. Update `SyncManager.kt` to allow queueing a task with `isSilent` parameter.
2. Update `SyncWorker.kt` to read `EXTRA_TASK_SILENT` from `inputData` into `silentRun`.
3. Update `SyncService.kt` to calculate `isSilent` from the intent extra, and use the constants `SyncWorker.TASK_SYNC_ACTION`, `SyncWorker.EXTRA_TASK_ID`, and `SyncWorker.EXTRA_TASK_SILENT`. Then pass `isSilent` to `SyncManager.queue`.

Wait, what about `queue(trigger: Trigger)`? A trigger has a `triggerTarget` which is a task id. Do triggers run silently? Let's check `TriggerService.java` to see if it queues silently or not.
