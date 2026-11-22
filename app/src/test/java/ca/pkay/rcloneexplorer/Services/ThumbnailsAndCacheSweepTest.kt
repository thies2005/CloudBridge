package ca.pkay.rcloneexplorer.Services

import ca.pkay.rcloneexplorer.CloudBridgeApp
import ca.pkay.rcloneexplorer.Items.RemoteItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ThumbnailsAndCacheSweepTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun thumbnailServeValidationFailsClosedOnMissingPortOrToken() {
        val remote = RemoteItem("gdrive", "drive")
        assertFalse(ThumbnailsLoadingService.isValidServeRequest(null, "tok", 12345))
        assertFalse(ThumbnailsLoadingService.isValidServeRequest(remote, null, 12345))
        assertFalse(ThumbnailsLoadingService.isValidServeRequest(remote, "", 12345))
        assertFalse(ThumbnailsLoadingService.isValidServeRequest(remote, "tok", -1))
        assertFalse(ThumbnailsLoadingService.isValidServeRequest(remote, "tok", 0))
        assertFalse(ThumbnailsLoadingService.isValidServeRequest(remote, "tok", 70000))
        assertTrue(ThumbnailsLoadingService.isValidServeRequest(remote, "secret-token", 43210))
    }

    @Test
    fun sweepStaleCacheEntriesDeletesOnlyExpiredFilesAndEmptyDirs() {
        val cacheDir = tempFolder.newFolder("cache")
        val previewDir = File(cacheDir, "preview").apply { mkdirs() }
        val shareDir = File(cacheDir, "share_staging/job-1").apply { mkdirs() }
        val stalePreview = File(previewDir, "old.jpg").apply { writeText("old") }
        val freshPreview = File(previewDir, "fresh.jpg").apply { writeText("new") }
        val staleShare = File(shareDir, "leak.pdf").apply { writeText("secret") }

        val nowMs = 1_700_000_000_000L
        val maxAgeMs = TimeUnit.HOURS.toMillis(24)
        stalePreview.setLastModified(nowMs - maxAgeMs - 10_000L)
        staleShare.setLastModified(nowMs - maxAgeMs - 10_000L)
        freshPreview.setLastModified(nowMs - 60_000L)

        CloudBridgeApp.sweepStaleCacheEntries(cacheDir, maxAgeMs, nowMs)

        assertFalse(stalePreview.exists())
        assertTrue(freshPreview.exists())
        assertFalse(staleShare.exists())
        assertFalse(shareDir.exists())
        assertTrue(previewDir.exists())
    }
}
