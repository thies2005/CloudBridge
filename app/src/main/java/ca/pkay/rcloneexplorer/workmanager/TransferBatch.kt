package ca.pkay.rcloneexplorer.workmanager

import java.text.Normalizer
import java.util.Locale

internal object TransferBatch {
    data class Selection(val path: String, val isFile: Boolean) {
        val parent = path.substring(0, path.lastIndexOf('/') + 1)
        val name = path.substringAfterLast('/')
    }

    fun groups(selection: List<Selection>): List<List<Int>> {
        val nameCounts = selection.groupingBy { nameKey(it.name) }.eachCount()
        return selection.indices.groupBy { index ->
            val item = selection[index]
            if (item.isFile && item.name.isNotEmpty() && item.name != "." && item.name != ".." &&
                item.path.none { it == '\n' || it == '\r' || it == '\u0000' } &&
                nameCounts[nameKey(item.name)] == 1
            ) {
                item.parent
            } else {
                index
            }
        }.values.toList()
    }

    // rclone can return a zero exit status for an omitted source in --files-from-raw.
    // Require a per-file comparison result as well as a successful process exit.
    fun missingSources(names: List<String>, report: Sequence<String>): Set<String> {
        val compared = report
            .filter { it.length > 2 && it[1] == ' ' && it[0] in "+=*" }
            .map { nameKey(it.substring(2)) }
            .toHashSet()
        return names.filterNot { nameKey(it) in compared }.toSet()
    }

    private fun nameKey(name: String): String {
        return Normalizer.normalize(name.uppercase(Locale.ROOT), Normalizer.Form.NFC)
    }
}
