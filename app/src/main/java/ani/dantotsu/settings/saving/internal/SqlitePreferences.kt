package ani.dantotsu.settings.saving.internal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ani.dantotsu.util.Logger

/**
 * A [SharedPreferences] kept in SQLite, one row per key, for the Irrelevant location.
 *
 * That file holds an entry per media per source, plus resume positions per episode, and the
 * platform implementation rewrites the whole XML on every `apply()` — so every progress save cost
 * a full re-serialisation of tens of thousands of keys, and `QueuedWork` blocked the main thread on
 * each Activity/Service stop until that flush finished. Here a write touches only the rows it
 * changed, on a background thread, and nothing waits on it.
 *
 * Reads, edits and listeners behave as the platform's do ([MapBackedPreferences]); writes reach
 * the database in the order they were applied.
 *
 * The first open copies the existing XML file in, inside one transaction. That file is left on
 * disk untouched, so a build from before this change still finds its data — as it was at upgrade.
 */
class SqlitePreferences private constructor(
    private val db: SQLiteDatabase,
    initial: Map<String, Any>,
) : MapBackedPreferences(initial, "SqlitePreferences") {

    override fun persist(cleared: Boolean, writes: Map<String, Any?>): Boolean = try {
        db.beginTransaction()
        try {
            if (cleared) db.delete(TABLE, null, null)
            for ((key, value) in writes) {
                if (value == null) {
                    db.delete(TABLE, "$COL_KEY = ?", arrayOf(key))
                } else {
                    val encoded = PrefValueCodec.encode(value) ?: continue
                    db.insertWithOnConflict(TABLE, null, row(key, encoded), SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        true
    } catch (e: Exception) {
        Logger.log("SqlitePreferences: write failed: ${e.message}")
        Logger.log(e)
        false
    }

    private class Helper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, DB_VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($COL_KEY TEXT PRIMARY KEY NOT NULL, " +
                    "$COL_TYPE INTEGER NOT NULL, $COL_VALUE TEXT NOT NULL)"
            )
            db.execSQL("CREATE TABLE $META ($COL_KEY TEXT PRIMARY KEY NOT NULL, $COL_VALUE TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val DB_VERSION = 1
        private const val TABLE = "prefs"
        private const val META = "meta"
        private const val COL_KEY = "key"
        private const val COL_TYPE = "type"
        private const val COL_VALUE = "value"
        private const val MIGRATED = "migrated_from_xml"

        private fun row(key: String, encoded: Pair<Int, String>) = ContentValues(3).apply {
            put(COL_KEY, key)
            put(COL_TYPE, encoded.first)
            put(COL_VALUE, encoded.second)
        }

        /**
         * Opens `<name>.db`, importing the SharedPreferences file [name] on the first open.
         * Throws if either fails; the caller falls back to the XML file, which is still intact.
         */
        fun open(context: Context, name: String): SqlitePreferences {
            val db = Helper(context.applicationContext, "$name.db").writableDatabase
            migrate(context, db, name)
            return SqlitePreferences(db, load(db))
        }

        private fun migrate(context: Context, db: SQLiteDatabase, name: String) {
            // Exclusive transaction: every process runs App.onCreate, and two of them may get here
            // together. The second waits, then sees the marker.
            db.beginTransaction()
            try {
                val done = db.rawQuery(
                    "SELECT 1 FROM $META WHERE $COL_KEY = ?", arrayOf(MIGRATED)
                ).use { it.moveToFirst() }
                if (!done) {
                    val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    var copied = 0
                    var dropped = 0
                    for ((key, value) in legacy) {
                        val encoded = value?.let { PrefValueCodec.encode(it) }
                        if (encoded == null) {
                            dropped++
                            continue
                        }
                        db.insertWithOnConflict(TABLE, null, row(key, encoded), SQLiteDatabase.CONFLICT_REPLACE)
                        copied++
                    }
                    db.insertWithOnConflict(
                        META, null,
                        ContentValues(2).apply {
                            put(COL_KEY, MIGRATED)
                            put(COL_VALUE, System.currentTimeMillis().toString())
                        },
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                    Logger.log("SqlitePreferences: imported $copied keys from $name.xml ($dropped unsupported)")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        private fun load(db: SQLiteDatabase): Map<String, Any> {
            val out = HashMap<String, Any>()
            db.query(TABLE, arrayOf(COL_KEY, COL_TYPE, COL_VALUE), null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0)
                    try {
                        out[key] = PrefValueCodec.decode(c.getInt(1), c.getString(2))
                    } catch (e: Exception) {
                        Logger.log("SqlitePreferences: skipping unreadable $key: ${e.message}")
                    }
                }
            }
            return out
        }
    }
}
