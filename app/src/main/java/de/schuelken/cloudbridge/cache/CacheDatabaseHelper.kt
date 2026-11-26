package de.schuelken.cloudbridge.cache

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CacheDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_DIR_LISTINGS (
                remote_name TEXT NOT NULL,
                path TEXT NOT NULL,
                fetched_at INTEGER NOT NULL,
                is_recursive INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (remote_name, path)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_FILE_ENTRIES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                remote_name TEXT NOT NULL,
                parent_path TEXT NOT NULL,
                path TEXT NOT NULL,
                name TEXT NOT NULL,
                size INTEGER NOT NULL,
                mod_time INTEGER NOT NULL,
                mime_type TEXT NOT NULL,
                is_dir INTEGER NOT NULL,
                UNIQUE (remote_name, path)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_file_entries_parent ON $TABLE_FILE_ENTRIES (remote_name, parent_path)"
        )
        db.execSQL(
            "CREATE INDEX idx_file_entries_remote ON $TABLE_FILE_ENTRIES (remote_name)"
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE $TABLE_FILE_FTS USING fts4(
                remote_name,
                parent_path,
                path,
                name
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_REMOTE_CAPABILITIES (
                remote_name TEXT PRIMARY KEY,
                public_link INTEGER NOT NULL,
                fetched_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FILE_FTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FILE_ENTRIES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DIR_LISTINGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_REMOTE_CAPABILITIES")
        onCreate(db)
    }

    companion object {
        const val DATABASE_NAME = "cloudbridge_cache.db"
        const val DATABASE_VERSION = 1

        const val TABLE_DIR_LISTINGS = "dir_listings"
        const val TABLE_FILE_ENTRIES = "file_entries"
        const val TABLE_FILE_FTS = "file_entries_fts"
        const val TABLE_REMOTE_CAPABILITIES = "remote_capabilities"
    }
}
