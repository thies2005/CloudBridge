package de.schuelken.cloudbridge.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import de.schuelken.cloudbridge.search.SearchFilter
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class CacheManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dbHelper = CacheDatabaseHelper(appContext)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    private val cacheRootDir: File get() = File(appContext.cacheDir, "cb")

    data class CachedListing(
        val items: List<FileItem>,
        val isStale: Boolean,
        val fetchedAt: Long
    )

    fun getMaxCacheBytes(): Long {
        val key = appContext.getString(R.string.pref_key_cache_max_size)
        return prefs.getLong(key, DEFAULT_MAX_CACHE_BYTES).coerceAtLeast(MIN_CACHE_BYTES)
    }

    fun setMaxCacheBytes(bytes: Long) {
        val key = appContext.getString(R.string.pref_key_cache_max_size)
        prefs.edit().putLong(key, bytes.coerceAtLeast(MIN_CACHE_BYTES)).apply()
        evictDataCacheIfNeeded()
    }

    fun getCachedFile(remoteName: String, path: String, size: Long, modTime: Long, fileName: String): File? {
        val file = buildCacheFile(remoteName, path, size, modTime, fileName)
        if (file.exists() && file.isFile && file.canRead() && (size <= 0 || file.length() == size)) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        return null
    }

    fun putCachedFile(
        remoteName: String,
        path: String,
        size: Long,
        modTime: Long,
        fileName: String,
        sourceFile: File
    ): File? {
        if (!sourceFile.exists() || !sourceFile.isFile) return null
        val target = buildCacheFile(remoteName, path, size, modTime, fileName)
        target.parentFile?.mkdirs()
        if (target.exists()) {
            target.setWritable(true)
            target.delete()
        }
        return try {
            sourceFile.copyTo(target, overwrite = true)
            target.setReadOnly()
            target.setLastModified(System.currentTimeMillis())
            evictDataCacheIfNeeded()
            target
        } catch (e: Exception) {
            null
        }
    }

    private fun buildCacheFile(
        remoteName: String,
        path: String,
        size: Long,
        modTime: Long,
        fileName: String
    ): File {
        val remoteHash = sha1(remoteName)
        val keyHash = sha1("$remoteName|$path|$size|$modTime")
        val safeName = sanitizeFileName(fileName)
        return File(File(File(cacheRootDir, remoteHash), keyHash), safeName)
    }

    @Synchronized
    fun evictDataCacheIfNeeded() {
        val maxBytes = getMaxCacheBytes()
        val files = collectDataCacheFiles()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        val sortedOldestFirst = files.sortedBy { it.lastModified() }
        for (file in sortedOldestFirst) {
            if (total <= maxBytes) break
            val len = file.length()
            file.setWritable(true)
            if (file.delete()) {
                total -= len
                pruneEmptyParents(file.parentFile)
            }
        }
    }

    fun getDataCacheSizeBytes(): Long {
        var total = 0L
        for (file in collectDataCacheFiles()) {
            total += file.length()
        }
        val previewDir = File(appContext.cacheDir, "preview")
        if (previewDir.exists()) {
            previewDir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        }
        val vcpDir = File(appContext.cacheDir, "vcp")
        if (vcpDir.exists()) {
            vcpDir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        }
        return total
    }

    fun getTotalCacheSizeBytes(): Long {
        val dbFile = appContext.getDatabasePath(CacheDatabaseHelper.DATABASE_NAME)
        val dbSize = if (dbFile.exists()) dbFile.length() else 0L
        return getDataCacheSizeBytes() + dbSize
    }

    @Synchronized
    fun clearAllCache() {
        cacheRootDir.deleteRecursively()
        File(appContext.cacheDir, "preview").deleteRecursively()
        File(appContext.cacheDir, "vcp").deleteRecursively()
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(CacheDatabaseHelper.TABLE_FILE_FTS, null, null)
            db.delete(CacheDatabaseHelper.TABLE_FILE_ENTRIES, null, null)
            db.delete(CacheDatabaseHelper.TABLE_DIR_LISTINGS, null, null)
            db.delete(CacheDatabaseHelper.TABLE_REMOTE_CAPABILITIES, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun clearRemoteCache(remoteName: String) {
        File(cacheRootDir, sha1(remoteName)).deleteRecursively()
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val args = arrayOf(remoteName)
            db.delete(CacheDatabaseHelper.TABLE_FILE_FTS, "remote_name = ?", args)
            db.delete(CacheDatabaseHelper.TABLE_FILE_ENTRIES, "remote_name = ?", args)
            db.delete(CacheDatabaseHelper.TABLE_DIR_LISTINGS, "remote_name = ?", args)
            db.delete(CacheDatabaseHelper.TABLE_REMOTE_CAPABILITIES, "remote_name = ?", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        setTier3SearchEnabled(remoteName, false)
    }

    // --- Directory Listing Cache ---

    fun getDirectoryListing(remote: RemoteItem, path: String): CachedListing? {
        val normalizedPath = normalizePath(path)
        val db = dbHelper.readableDatabase
        val fetchedAt: Long
        db.rawQuery(
            "SELECT fetched_at FROM ${CacheDatabaseHelper.TABLE_DIR_LISTINGS} WHERE remote_name = ? AND path = ?",
            arrayOf(remote.name, normalizedPath)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            fetchedAt = cursor.getLong(0)
        }

        val items = ArrayList<FileItem>()
        db.rawQuery(
            """
            SELECT path, name, size, mod_time, mime_type, is_dir
            FROM ${CacheDatabaseHelper.TABLE_FILE_ENTRIES}
            WHERE remote_name = ? AND parent_path = ?
            """.trimIndent(),
            arrayOf(remote.name, normalizedPath)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items.add(
                    FileItem(
                        remote,
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getLong(3),
                        cursor.getString(4),
                        cursor.getInt(5) == 1,
                        false
                    )
                )
            }
        }
        val isStale = System.currentTimeMillis() - fetchedAt > LISTING_TTL_MS
        return CachedListing(items, isStale, fetchedAt)
    }

    @Synchronized
    fun saveDirectoryListing(remoteName: String, path: String, items: List<FileItem>) {
        val normalizedPath = normalizePath(path)
        val now = System.currentTimeMillis()
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val listingValues = ContentValues().apply {
                put("remote_name", remoteName)
                put("path", normalizedPath)
                put("fetched_at", now)
                put("is_recursive", 0)
            }
            db.insertWithOnConflict(
                CacheDatabaseHelper.TABLE_DIR_LISTINGS,
                null,
                listingValues,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            val deleteArgs = arrayOf(remoteName, normalizedPath)
            db.delete(CacheDatabaseHelper.TABLE_FILE_ENTRIES, "remote_name = ? AND parent_path = ?", deleteArgs)
            db.delete(CacheDatabaseHelper.TABLE_FILE_FTS, "remote_name = ? AND parent_path = ?", deleteArgs)

            insertEntries(db, remoteName, normalizedPath, items)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun invalidateDirectory(remoteName: String, path: String?) {
        val normalized = normalizePath(path ?: "")
        val parent = parentOf(normalized)
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                CacheDatabaseHelper.TABLE_DIR_LISTINGS,
                "remote_name = ? AND (path = ? OR path = ?)",
                arrayOf(remoteName, normalized, parent)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- Remote Capabilities Cache (PublicLink) ---

    fun getPublicLinkSupport(remoteName: String): Boolean? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT public_link, fetched_at FROM ${CacheDatabaseHelper.TABLE_REMOTE_CAPABILITIES} WHERE remote_name = ?",
            arrayOf(remoteName)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val fetchedAt = cursor.getLong(1)
            if (System.currentTimeMillis() - fetchedAt > CAPABILITY_TTL_MS) return null
            return cursor.getInt(0) == 1
        }
    }

    @Synchronized
    fun setPublicLinkSupport(remoteName: String, supported: Boolean) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("remote_name", remoteName)
            put("public_link", if (supported) 1 else 0)
            put("fetched_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            CacheDatabaseHelper.TABLE_REMOTE_CAPABILITIES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // --- Tier 3 Search Index (Opt-in per remote) ---

    fun isTier3SearchEnabled(remoteName: String): Boolean {
        return prefs.getBoolean(TIER3_PREF_PREFIX + remoteName, false)
    }

    fun setTier3SearchEnabled(remoteName: String, enabled: Boolean) {
        prefs.edit().putBoolean(TIER3_PREF_PREFIX + remoteName, enabled).apply()
        if (!enabled) {
            clearRecursiveIndex(remoteName)
        }
    }

    fun getTier3EnabledRemotes(): Set<String> {
        val result = HashSet<String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(TIER3_PREF_PREFIX) && value == true) {
                result.add(key.removePrefix(TIER3_PREF_PREFIX))
            }
        }
        return result
    }

    fun getLastIndexTime(remoteName: String): Long? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT fetched_at FROM ${CacheDatabaseHelper.TABLE_DIR_LISTINGS} WHERE remote_name = ? AND path = ? AND is_recursive = 1",
            arrayOf(remoteName, RECURSIVE_ROOT_MARKER)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    @Synchronized
    fun saveRecursiveIndex(remoteName: String, items: List<FileItem>) {
        val now = System.currentTimeMillis()
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val args = arrayOf(remoteName)
            db.delete(CacheDatabaseHelper.TABLE_FILE_FTS, "remote_name = ?", args)
            db.delete(CacheDatabaseHelper.TABLE_FILE_ENTRIES, "remote_name = ?", args)

            val marker = ContentValues().apply {
                put("remote_name", remoteName)
                put("path", RECURSIVE_ROOT_MARKER)
                put("fetched_at", now)
                put("is_recursive", 1)
            }
            db.insertWithOnConflict(
                CacheDatabaseHelper.TABLE_DIR_LISTINGS,
                null,
                marker,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            insertEntries(db, remoteName, null, items)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    private fun clearRecursiveIndex(remoteName: String) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val args = arrayOf(remoteName)
            db.delete(CacheDatabaseHelper.TABLE_FILE_FTS, "remote_name = ?", args)
            db.delete(CacheDatabaseHelper.TABLE_FILE_ENTRIES, "remote_name = ?", args)
            db.delete(CacheDatabaseHelper.TABLE_DIR_LISTINGS, "remote_name = ?", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun searchIndexed(
        query: String,
        remotesByName: Map<String, RemoteItem>,
        filter: SearchFilter,
        limit: Int = 300
    ): List<FileItem> {
        val enabledRemotes = getTier3EnabledRemotes().filter { remotesByName.containsKey(it) }
        if (enabledRemotes.isEmpty()) return emptyList()

        val placeholders = enabledRemotes.joinToString(",") { "?" }
        val args = ArrayList<String>(enabledRemotes)

        val trimmed = query.trim()
        val useFts = trimmed.isNotEmpty()
        val results = ArrayList<FileItem>()
        val db = dbHelper.readableDatabase

        val sql = if (useFts) {
            val ftsQuery = buildFtsMatchQuery(trimmed)
            args.add(ftsQuery)
            """
            SELECT e.remote_name, e.path, e.name, e.size, e.mod_time, e.mime_type, e.is_dir
            FROM ${CacheDatabaseHelper.TABLE_FILE_ENTRIES} e
            INNER JOIN ${CacheDatabaseHelper.TABLE_FILE_FTS} f
                ON e.remote_name = f.remote_name AND e.path = f.path
            WHERE e.remote_name IN ($placeholders)
              AND ${CacheDatabaseHelper.TABLE_FILE_FTS} MATCH ?
            LIMIT $limit
            """.trimIndent()
        } else {
            """
            SELECT remote_name, path, name, size, mod_time, mime_type, is_dir
            FROM ${CacheDatabaseHelper.TABLE_FILE_ENTRIES}
            WHERE remote_name IN ($placeholders)
            LIMIT $limit
            """.trimIndent()
        }

        try {
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val remoteName = cursor.getString(0)
                    val remote = remotesByName[remoteName] ?: continue
                    val item = FileItem(
                        remote,
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getLong(3),
                        cursor.getLong(4),
                        cursor.getString(5),
                        cursor.getInt(6) == 1,
                        false
                    )
                    if (filter.matches(item)) {
                        results.add(item)
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to LIKE query if FTS syntax rejects special characters
            val likeArgs = ArrayList<String>(enabledRemotes)
            likeArgs.add("%${trimmed.lowercase(Locale.ROOT)}%")
            val likeSql = """
                SELECT remote_name, path, name, size, mod_time, mime_type, is_dir
                FROM ${CacheDatabaseHelper.TABLE_FILE_ENTRIES}
                WHERE remote_name IN ($placeholders)
                  AND LOWER(name) LIKE ?
                LIMIT $limit
            """.trimIndent()
            db.rawQuery(likeSql, likeArgs.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val remoteName = cursor.getString(0)
                    val remote = remotesByName[remoteName] ?: continue
                    val item = FileItem(
                        remote,
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getLong(3),
                        cursor.getLong(4),
                        cursor.getString(5),
                        cursor.getInt(6) == 1,
                        false
                    )
                    if (filter.matches(item)) {
                        results.add(item)
                    }
                }
            }
        }

        return results
    }

    private fun insertEntries(
        db: SQLiteDatabase,
        remoteName: String,
        fixedParentPath: String?,
        items: List<FileItem>
    ) {
        val entryStmt = db.compileStatement(
            """
            INSERT OR REPLACE INTO ${CacheDatabaseHelper.TABLE_FILE_ENTRIES}
            (remote_name, parent_path, path, name, size, mod_time, mime_type, is_dir)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        val ftsStmt = db.compileStatement(
            """
            INSERT INTO ${CacheDatabaseHelper.TABLE_FILE_FTS}
            (remote_name, parent_path, path, name)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        )

        for (item in items) {
            val itemPath = normalizePath(item.path)
            val parent = fixedParentPath ?: parentOf(itemPath)
            val isDirInt = if (item.isDir) 1L else 0L

            entryStmt.clearBindings()
            entryStmt.bindString(1, remoteName)
            entryStmt.bindString(2, parent)
            entryStmt.bindString(3, itemPath)
            entryStmt.bindString(4, item.name ?: "")
            entryStmt.bindLong(5, item.size)
            entryStmt.bindLong(6, item.modTime)
            entryStmt.bindString(7, item.mimeType ?: "application/octet-stream")
            entryStmt.bindLong(8, isDirInt)
            entryStmt.executeInsert()

            ftsStmt.clearBindings()
            ftsStmt.bindString(1, remoteName)
            ftsStmt.bindString(2, parent)
            ftsStmt.bindString(3, itemPath)
            ftsStmt.bindString(4, item.name ?: "")
            ftsStmt.executeInsert()
        }
    }

    private fun buildFtsMatchQuery(raw: String): String {
        val tokens = raw.split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}_]"), "") }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return raw
        return tokens.joinToString(" ") { "$it*" }
    }

    private fun collectDataCacheFiles(): List<File> {
        val root = cacheRootDir
        if (!root.exists()) return emptyList()
        return root.walkTopDown().filter { it.isFile }.toList()
    }

    private fun pruneEmptyParents(dir: File?) {
        var cur = dir
        val rootPath = cacheRootDir.absolutePath
        while (cur != null && cur.absolutePath != rootPath) {
            val children = cur.list()
            if (children != null && children.isEmpty()) {
                cur.delete()
                cur = cur.parentFile
            } else {
                break
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val base = File(name.replace('\\', '/')).name.trim()
        return if (base.isEmpty() || base == "." || base == "..") "file" else base
    }

    private fun normalizePath(path: String): String {
        return path.trim('/')
    }

    private fun parentOf(normalizedPath: String): String {
        val idx = normalizedPath.lastIndexOf('/')
        return if (idx <= 0) "" else normalizedPath.substring(0, idx)
    }

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MAX_CACHE_BYTES = 500L * 1024L * 1024L // 500 MB
        const val MIN_CACHE_BYTES = 10L * 1024L * 1024L // 10 MB floor
        private const val LISTING_TTL_MS = 5L * 60L * 1000L // 5 minutes
        private const val CAPABILITY_TTL_MS = 24L * 60L * 60L * 1000L // 24 hours
        private const val TIER3_PREF_PREFIX = "tier3_search_enabled_"
        private const val RECURSIVE_ROOT_MARKER = "__recursive_index__"

        @Volatile
        private var instance: CacheManager? = null

        @JvmStatic
        fun getInstance(context: Context): CacheManager {
            return instance ?: synchronized(this) {
                instance ?: CacheManager(context).also { instance = it }
            }
        }
    }
}
