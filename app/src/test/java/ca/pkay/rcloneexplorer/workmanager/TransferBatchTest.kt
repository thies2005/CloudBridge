package ca.pkay.rcloneexplorer.workmanager

import ca.pkay.rcloneexplorer.workmanager.TransferBatch.Selection
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferBatchTest {
    @Test
    fun groupsOnlyCompatibleFilesFromTheSameParent() {
        val selected = listOf(
            Selection("/a/photo.jpg", true),
            Selection("/a/ #notes ", true),
            Selection("/b/clip.mp4", true),
            Selection("/a/subdir", false),
            Selection("/a/new\nline", true),
            Selection("/b/-leading", true),
            Selection("root-file", true),
            Selection("root-other", true)
        )
        assertEquals(
            listOf(listOf(0, 1), listOf(2, 5), listOf(3), listOf(4), listOf(6, 7)),
            TransferBatch.groups(selected)
        )
        assertEquals("/", Selection("/root-file", true).parent)
    }

    @Test
    fun leavesPotentialDestinationCollisionsOnTheLegacyPath() {
        val selected = listOf(
            Selection("/a/Clash.txt", true),
            Selection("/b/clash.txt", true),
            Selection("/a/Å", true),
            Selection("/a/A\u030a", false),
            Selection("/a/bad\rname", true),
            Selection("/a/bad\u0000name", true),
            Selection("/a/one", true),
            Selection("/a/two", true)
        )
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2), listOf(3), listOf(4), listOf(5), listOf(6, 7)),
            TransferBatch.groups(selected)
        )
    }

    @Test
    fun detectsOmittedSourcesAndAcceptsFilesAlreadyAtTheDestination() {
        val selected = listOf(" #notes ", "photo.jpg", "clip.mp4", "vanished", "broken")
        val report = sequenceOf("+  #notes ", "= PHOTO.JPG", "* clip.mp4", "- vanished", "! broken")
        assertEquals(setOf("vanished", "broken"), TransferBatch.missingSources(selected, report))
    }
}
