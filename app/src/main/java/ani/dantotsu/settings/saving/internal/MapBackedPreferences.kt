package ani.dantotsu.settings.saving.internal

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * A [SharedPreferences] served from an in-memory map, with each change handed to [persist] on a
 * background thread — the part the app's own stores share, whatever they keep their data in:
 * SQLite ([SqlitePreferences]) or an encrypted file ([EncryptedPreferences]).
 *
 * Behaviour matches the platform's where callers can see it: reads come from the map, `apply()`
 * updates it before returning, a getter of the wrong type throws [ClassCastException], `clear()`
 * runs before the editor's other changes, listeners are held weakly and called on the main thread,
 * once per key whose value actually changed (with a null key after a clear). [persist] sees the
 * changes in the order they were applied; `commit()` waits for its own.
 */
abstract class MapBackedPreferences(
    initial: Map<String, Any>,
    threadName: String,
) : SharedPreferences {

    /**
     * Stores one editor's changes: everything removed first when [cleared], then [writes] — a key
     * to its new value, or to null for a removal. Runs on the writer thread, one call at a time.
     * @return false when it could not; the change stays in memory regardless, as with the platform.
     */
    protected abstract fun persist(cleared: Boolean, writes: Map<String, Any?>): Boolean

    private val lock = Any()
    private val map = HashMap(initial)
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Any>()

    // One thread, so changes are persisted in the order apply() queued them.
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, threadName).apply { isDaemon = true }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }

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

    /** What one editor changed, in memory, for [persist] and the listeners. */
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
                // Queued under the same lock as the map update, so the store sees changes in
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

    private fun write(change: Change): Boolean =
        persist(change.cleared, change.writes.mapValues { (_, v) -> v.takeUnless { it === REMOVED } })

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

    private companion object {
        val PRESENT = Any()
        val REMOVED = Any()
    }
}
