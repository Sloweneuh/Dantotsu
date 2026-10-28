package ani.dantotsu.settings.saving.internal

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import ani.dantotsu.util.Logger
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * A [SharedPreferences] kept in SQLite, one row per key, for the Irrelevant location.
 *
 * That file holds an entry per media per source, plus resume positions per episode, and the
 * platform implementation rewrites the whole XML on every `apply()` — so every progress save cost
 * a full re-serialisation of tens of thousands of keys, and `QueuedWork` blocked the main thread on
 * each Activity/Service stop until that flush finished. Here a write touches only the rows it
 * changed, on a background thread, and nothing waits on it.
 *
 * Behaviour matches the platform's where callers can see it: reads come from an in-memory map,
 * `apply()` updates that map before returning, a getter of the wrong type throws
 * [ClassCastException], `clear()` runs before the editor's other changes, listeners are held weakly
 * and called on the main thread, once per key whose value actually changed (with a null key after
 * a clear). Writes reach the database in the order they were applied; `commit()` waits for its own.
 *
 * The first open copies the existing XML file in, inside one transaction. That file is left on
 * disk untouched, so a build from before this change still finds its data — as it was at upgrade.
 */
class SqlitePreferences private constructor(
    private val db: SQLiteDatabase,
    initial: Map<String, Any>,
) : SharedPreferences {

    private val lock = Any()
    private val map = HashMap(initial)
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Any>()

    // One thread, so writes land in the order apply() queued them.
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SqlitePreferences").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { HashMap(map) }

    override fun getString(key: String, defValue: String?): String? =
        synchronized(lock) { map[key] as String? } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(lock) { (map[key] as Set<*>?)?.let { HashSet(it as Set<String>) } } ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        synchronized(lock) { map[key] as Int? } ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        synchronized(lock) { map[key] as Long? } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        synchronized(lock) { map[key] as Float? } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        synchronized(lock) { map[key] as Boolean? } ?: defValue

    override fun contains(key: String): Boolean = synchronized(lock) { map.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(lock) { listeners[listener] = PRESENT }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(lock) { listeners.remove(listener) }
    }

    /** What one editor changed, in memory, for the database and the listeners. */
    private class Change(
        val cleared: Boolean,
        /** Key to new value; [REMOVED] for a deletion. */
        val writes: Map<String, Any>,
    )

    private inner class Editor : SharedPreferences.Editor {
        private val mods = HashMap<String, Any>()
        private var clear = false

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            synchronized(this) { mods[key] = value ?: REMOVED }
            return this
        }

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.let { HashSet(it) })
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            synchronized(this) { clear = true }
            return this
        }

        override fun apply() {
            val change = synchronized(lock) {
                val change = applyToMap() ?: return
                // Queued under the same lock as the map update, so the database sees writes in
                // exactly the order the map did.
                writer.execute { write(change) }
                change
            }
            notifyListeners(change)
        }

        override fun commit(): Boolean {
            val (change, pending) = synchronized(lock) {
                val change = applyToMap() ?: return true
                change to writer.submit<Boolean> { write(change) }
            }
            val ok = runCatching { pending.get() }.getOrDefault(false)
            notifyListeners(change)
            return ok
        }

        /** Caller holds [lock]. Null when nothing actually changed. */
        private fun applyToMap(): Change? {
            val (mods, clear) = synchronized(this) {
                HashMap(mods).also { this.mods.clear() } to clear.also { this.clear = false }
            }
            var cleared = false
            if (clear) {
                if (map.isNotEmpty()) map.clear()
                cleared = true
            }
            val writes = HashMap<String, Any>()
            for ((key, value) in mods) {
                if (value === REMOVED) {
                    if (map.remove(key) == null && !cleared) continue
                } else {
                    if (map[key] == value) continue
                    map[key] = value
                }
                writes[key] = value
            }
            if (!cleared && writes.isEmpty()) return null
            return Change(cleared, writes)
        }
    }

    private fun write(change: Change): Boolean = try {
        db.beginTransaction()
        try {
            if (change.cleared) db.delete(TABLE, null, null)
            for ((key, value) in change.writes) {
                if (value === REMOVED) {
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

    private fun notifyListeners(change: Change) {
        val targets = synchronized(lock) { listeners.keys.toList() }
        if (targets.isEmpty()) return
        val keys = change.writes.keys.toList()
        val run = Runnable {
            if (change.cleared) targets.forEach { it.onSharedPreferenceChanged(this, null) }
            for (key in keys) targets.forEach { it.onSharedPreferenceChanged(this, key) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
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

        private val PRESENT = Any()
        private val REMOVED = Any()

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
