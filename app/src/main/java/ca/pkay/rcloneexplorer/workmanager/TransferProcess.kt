package ca.pkay.rcloneexplorer.workmanager

/** Owns the current subprocess, including when WorkManager stops the worker during launch. */
internal class TransferProcess {
    private var stopped = false
    private var current: Process? = null

    fun <T> run(launch: () -> Process?, use: (Process) -> T): T? {
        if (synchronized(this) { stopped }) return null
        val process = launch() ?: return null
        val accepted = synchronized(this) {
            if (stopped) {
                false
            } else {
                current = process
                true
            }
        }
        if (!accepted) {
            process.destroy()
            return null
        }

        try {
            return use(process)
        } finally {
            val owned = synchronized(this) {
                if (current !== process) {
                    false
                } else {
                    current = null
                    true
                }
            }
            if (owned) process.destroy()
        }
    }

    fun stop() {
        val process = synchronized(this) {
            stopped = true
            current.also { current = null }
        }
        process?.destroy()
    }
}
