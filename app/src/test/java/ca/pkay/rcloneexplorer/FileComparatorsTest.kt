package ca.pkay.rcloneexplorer

import ca.pkay.rcloneexplorer.Dialogs.SortDialog
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileComparatorsTest {

    private val remote = RemoteItem("test", "drive")

    private fun item(name: String, size: Long = 100L, modTime: Long = 1000L, isDir: Boolean = false): FileItem {
        return FileItem(remote, name, name, size, modTime, "application/octet-stream", isDir, false)
    }

    @Test
    fun naturalSortOrdersNumbersNumerically() {
        val items = mutableListOf(
            item("file10.txt"),
            item("file2.txt"),
            item("file1.txt"),
            item("File20.txt")
        )
        FileComparators.sortFileItems(items, SortDialog.ALPHA_ASCENDING)
        assertEquals(
            listOf("file1.txt", "file2.txt", "file10.txt", "File20.txt"),
            items.map { it.name }
        )
    }

    @Test
    fun foldersAlwaysSortBeforeFiles() {
        val items = mutableListOf(
            item("a_file.txt", isDir = false),
            item("z_folder", isDir = true)
        )
        FileComparators.sortFileItems(items, SortDialog.ALPHA_ASCENDING)
        assertTrue(items[0].isDir)
        assertEquals("z_folder", items[0].name)

        FileComparators.sortFileItems(items, SortDialog.ALPHA_DESCENDING)
        assertTrue(items[0].isDir)
        assertEquals("z_folder", items[0].name)
    }

    @Test
    fun sizeSortOrdersAscendingAndDescendingDeterministically() {
        val items = mutableListOf(
            item("medium.bin", size = 500L),
            item("large.bin", size = 5000L),
            item("small.bin", size = 50L),
            item("folder", size = 0L, isDir = true)
        )
        FileComparators.sortFileItems(items, SortDialog.SIZE_ASCENDING)
        assertEquals(listOf("folder", "small.bin", "medium.bin", "large.bin"), items.map { it.name })

        FileComparators.sortFileItems(items, SortDialog.SIZE_DESCENDING)
        assertEquals(listOf("folder", "large.bin", "medium.bin", "small.bin"), items.map { it.name })
    }

    @Test
    fun modTimeSortPutsUnknownLast() {
        val items = mutableListOf(
            item("unknown.bin", modTime = -1L),
            item("older.bin", modTime = 1000L),
            item("newer.bin", modTime = 5000L)
        )
        FileComparators.sortFileItems(items, SortDialog.MOD_TIME_DESCENDING)
        assertEquals(listOf("newer.bin", "older.bin", "unknown.bin"), items.map { it.name })

        FileComparators.sortFileItems(items, SortDialog.MOD_TIME_ASCENDING)
        assertEquals(listOf("older.bin", "newer.bin", "unknown.bin"), items.map { it.name })
    }

    @Test
    fun resolveSortOrderMapsIdsCorrectly() {
        assertEquals(
            SortDialog.SIZE_DESCENDING,
            FileComparators.resolveSortOrder(R.id.radio_sort_size, R.id.radio_sort_descending)
        )
        assertEquals(
            SortDialog.MOD_TIME_ASCENDING,
            FileComparators.resolveSortOrder(R.id.radio_sort_date, R.id.radio_sort_ascending)
        )
        assertEquals(
            SortDialog.ALPHA_ASCENDING,
            FileComparators.resolveSortOrder(R.id.radio_sort_name, R.id.radio_sort_ascending)
        )
    }
}
