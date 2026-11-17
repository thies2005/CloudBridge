package de.schuelken.cloudbridge.search

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import ca.pkay.rcloneexplorer.Items.FileItem
import java.text.Normalizer
import java.util.Locale

data class SearchFilter(
    val type: TypeFilter = TypeFilter.ALL,
    val minSizeBytes: Long = 0L,
    val maxAgeMs: Long = 0L
) {
    enum class TypeFilter {
        ALL, IMAGES, VIDEO, AUDIO, DOCS, ARCHIVES, APK, FOLDERS
    }

    fun isDefault(): Boolean = type == TypeFilter.ALL && minSizeBytes <= 0L && maxAgeMs <= 0L

    fun matches(item: FileItem, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (type != TypeFilter.ALL && !matchesType(item, type)) {
            return false
        }
        if (minSizeBytes > 0L && !item.isDir && item.size < minSizeBytes) {
            return false
        }
        if (minSizeBytes > 0L && item.isDir) {
            return false
        }
        if (maxAgeMs > 0L) {
            val mod = item.modTime
            if (mod <= 0L || (nowMs - mod) > maxAgeMs) {
                return false
            }
        }
        return true
    }

    private fun matchesType(item: FileItem, type: TypeFilter): Boolean {
        if (type == TypeFilter.FOLDERS) return item.isDir
        if (item.isDir) return false
        val mime = (item.mimeType ?: "").lowercase(Locale.ROOT)
        val ext = FileItem.extractExtension(item.name ?: item.path)
        return when (type) {
            TypeFilter.IMAGES -> mime.startsWith("image/")
            TypeFilter.VIDEO -> mime.startsWith("video/")
            TypeFilter.AUDIO -> mime.startsWith("audio/")
            TypeFilter.APK -> ext == "apk" || mime == "application/vnd.android.package-archive"
            TypeFilter.ARCHIVES -> ext in ARCHIVE_EXTENSIONS || mime.contains("zip") || mime.contains("tar") || mime.contains("rar") || mime.contains("7z")
            TypeFilter.DOCS -> ext in DOC_EXTENSIONS || mime.startsWith("text/") || mime == "application/pdf" || mime.contains("document") || mime.contains("spreadsheet") || mime.contains("presentation")
            TypeFilter.ALL, TypeFilter.FOLDERS -> true
        }
    }

    companion object {
        private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst")
        private val DOC_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp", "txt", "md", "csv", "rtf", "epub"
        )
    }
}

object SearchEngine {

    private val DIACRITICS_REGEX = Regex("\\p{Mn}+")

    @JvmStatic
    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "").lowercase(Locale.ROOT)
    }

    /**
     * Parses query into AND-ed terms, respecting `"quoted phrases"`.
     */
    @JvmStatic
    fun parseTerms(query: String?): List<String> {
        if (query.isNullOrBlank()) return emptyList()
        val terms = ArrayList<String>()
        var i = 0
        val len = query.length
        while (i < len) {
            while (i < len && query[i].isWhitespace()) i++
            if (i >= len) break
            if (query[i] == '"') {
                i++
                val start = i
                while (i < len && query[i] != '"') i++
                val phrase = normalize(query.substring(start, i)).trim()
                if (phrase.isNotEmpty()) terms.add(phrase)
                if (i < len && query[i] == '"') i++
            } else {
                val start = i
                while (i < len && !query[i].isWhitespace()) i++
                val word = normalize(query.substring(start, i)).trim()
                if (word.isNotEmpty()) terms.add(word)
            }
        }
        return terms
    }

    @JvmStatic
    fun matchesQuery(candidate: String?, terms: List<String>): Boolean {
        if (terms.isEmpty()) return true
        val norm = normalize(candidate)
        for (term in terms) {
            if (term.contains('*') || term.contains('?')) {
                val regex = globToRegex(term)
                if (!regex.matches(norm)) return false
            } else {
                if (!norm.contains(term)) return false
            }
        }
        return true
    }

    @JvmStatic
    fun filterItems(
        items: List<FileItem>,
        query: String?,
        filter: SearchFilter,
        matchFullPath: Boolean = false
    ): List<FileItem> {
        val terms = parseTerms(query)
        val now = System.currentTimeMillis()
        if (terms.isEmpty() && filter.isDefault()) {
            return items
        }
        val out = ArrayList<FileItem>()
        for (item in items) {
            if (!filter.matches(item, now)) continue
            val target = if (matchFullPath) item.path else item.name
            if (matchesQuery(target, terms)) {
                out.add(item)
            }
        }
        return out
    }

    @JvmStatic
    fun highlightMatch(text: String, query: String?, highlightBgColor: Int): CharSequence {
        if (query.isNullOrBlank() || text.isEmpty()) return text
        val terms = parseTerms(query).filter { !it.contains('*') && !it.contains('?') }
        if (terms.isEmpty()) return text

        val lower = text.lowercase(Locale.ROOT)
        val spannable = SpannableString(text)
        var anyMatch = false
        for (term in terms) {
            var idx = lower.indexOf(term)
            while (idx >= 0 && idx + term.length <= text.length) {
                spannable.setSpan(
                    BackgroundColorSpan(highlightBgColor),
                    idx,
                    idx + term.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    idx,
                    idx + term.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                anyMatch = true
                idx = lower.indexOf(term, idx + term.length)
            }
        }
        return if (anyMatch) spannable else text
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> {
                    sb.append('\\').append(c)
                }
                else -> sb.append(c)
            }
        }
        return Regex(sb.toString())
    }
}
