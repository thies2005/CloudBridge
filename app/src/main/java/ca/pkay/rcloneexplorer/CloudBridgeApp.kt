package ca.pkay.rcloneexplorer

import android.app.Application
import android.app.Activity
import android.os.Bundle
import ca.pkay.rcloneexplorer.Activities.OnboardingActivity
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.Configuration
import ca.pkay.rcloneexplorer.util.ActivityHelper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Application entry point.
 *
 * Provides a custom [Configuration] for WorkManager so that more transfer workers
 * (uploads/downloads launched from the file explorer) can be scheduled concurrently than
 * the stock defaults allow. See the transmission-speed audit (item 4).
 *
 * The default `androidx.startup` `WorkManagerInitializer` is disabled in `AndroidManifest.xml`
 * so that this configuration is honored; WorkManager then initializes lazily on first use.
 */
class CloudBridgeApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        ActivityHelper.applyDarkMode(this)
        scheduleCacheSweep()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                // Onboarding owns its immersive window independently.
                if (activity !is OnboardingActivity && !activity.isFinishing) {
                    ActivityHelper.applyWindowInsets(activity)
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setMaxSchedulerLimit(50)
            .build()

    private fun scheduleCacheSweep() {
        val dir = cacheDir ?: return
        val thread = Thread({
            sweepStaleCacheEntries(dir, CACHE_MAX_AGE_MS, System.currentTimeMillis())
        }, "CloudBridge-cache-sweep")
        thread.isDaemon = true
        thread.start()
    }

    companion object {
        private val CACHE_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24)

        @VisibleForTesting
        internal fun sweepStaleCacheEntries(cacheDir: File, maxAgeMs: Long, nowMs: Long) {
            for (subDirName in arrayOf("preview", "share_staging")) {
                val target = File(cacheDir, subDirName)
                if (target.isDirectory) {
                    sweepDirectory(target, maxAgeMs, nowMs, deleteRootIfEmpty = false)
                }
            }
        }

        private fun sweepDirectory(dir: File, maxAgeMs: Long, nowMs: Long, deleteRootIfEmpty: Boolean) {
            val entries = dir.listFiles() ?: return
            for (entry in entries) {
                if (entry.isDirectory) {
                    sweepDirectory(entry, maxAgeMs, nowMs, deleteRootIfEmpty = true)
                } else {
                    val age = nowMs - entry.lastModified()
                    if (age > maxAgeMs) {
                        entry.delete()
                    }
                }
            }
            if (deleteRootIfEmpty) {
                val remaining = dir.list()
                if (remaining != null && remaining.isEmpty()) {
                    dir.delete()
                }
            }
        }
    }
}
