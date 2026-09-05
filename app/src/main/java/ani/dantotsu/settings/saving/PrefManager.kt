package ani.dantotsu.settings.saving

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import ani.dantotsu.R
import ani.dantotsu.settings.saving.internal.Compat
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

object PrefManager {

    private var generalPreferences: SharedPreferences? = null
    private var uiPreferences: SharedPreferences? = null
    private var playerPreferences: SharedPreferences? = null
    private var readerPreferences: SharedPreferences? = null
    private var irrelevantPreferences: SharedPreferences? = null
    private var animeDownloadsPreferences: SharedPreferences? = null
    private var protectedPreferences: SharedPreferences? = null

    fun init(context: Context) {  //must be called in Application class or will crash
        if (generalPreferences != null) return
        generalPreferences =
            context.getSharedPreferences(Location.General.location, Context.MODE_PRIVATE)
        uiPreferences =
            context.getSharedPreferences(Location.UI.location, Context.MODE_PRIVATE)
        playerPreferences =
            context.getSharedPreferences(Location.Player.location, Context.MODE_PRIVATE)
        readerPreferences =
            context.getSharedPreferences(Location.Reader.location, Context.MODE_PRIVATE)
        irrelevantPreferences =
            context.getSharedPreferences(Location.Irrelevant.location, Context.MODE_PRIVATE)
        animeDownloadsPreferences =
            context.getSharedPreferences(Location.AnimeDownloads.location, Context.MODE_PRIVATE)
        protectedPreferences =
            context.getSharedPreferences(Location.Protected.location, Context.MODE_PRIVATE)
        Compat.importOldPrefs(context)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> setVal(prefName: PrefName, value: T?) {
        val pref = getPrefLocation(prefName.data.prefLocation)
        with(pref.edit()) {
            when (value) {
                is Boolean -> putBoolean(prefName.name, value)
                is Int -> putInt(prefName.name, value)
                is Float -> putFloat(prefName.name, value)
                is Long -> putLong(prefName.name, value)
                is String -> putString(prefName.name, value)
                is Set<*> -> putStringSet(prefName.name, value as Set<String>)
                null -> remove(prefName.name)
                else -> serializeClass(prefName.name, value, prefName.data.prefLocation)
            }
            apply()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getVal(prefName: PrefName, default: T): T {
        return try {
            val pref = getPrefLocation(prefName.data.prefLocation)
            when (prefName.data.type) {
                Boolean::class -> pref.getBoolean(prefName.name, default as Boolean) as T
                Int::class -> pref.getInt(prefName.name, default as Int) as T
                Float::class -> pref.getFloat(prefName.name, default as Float) as T
                Long::class -> pref.getLong(prefName.name, default as Long) as T
                String::class -> pref.getString(prefName.name, default as String?) as T
                Set::class -> pref.getStringSet(prefName.name, default as Set<String>) as T

                List::class -> deserializeClass(
                    prefName.name,
                    default,
                    prefName.data.prefLocation
                ) as T

                else -> throw IllegalArgumentException("Type not supported")
            }
        } catch (e: Exception) {
            default
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getVal(prefName: PrefName): T {
        return try {
            val pref = getPrefLocation(prefName.data.prefLocation)
            when (prefName.data.type) {
                Boolean::class -> pref.getBoolean(
                    prefName.name,
                    prefName.data.default as Boolean
                ) as T

                Int::class -> pref.getInt(prefName.name, prefName.data.default as Int) as T
                Float::class -> pref.getFloat(prefName.name, prefName.data.default as Float) as T
                Long::class -> pref.getLong(prefName.name, prefName.data.default as Long) as T
                String::class -> pref.getString(
                    prefName.name,
                    prefName.data.default as String?
                ) as T

                Set::class -> pref.getStringSet(
                    prefName.name,
                    prefName.data.default as Set<String>
                ) as T

                List::class -> deserializeClass(
                    prefName.name,
                    prefName.data.default,
                    prefName.data.prefLocation
                ) as T

                else -> throw IllegalArgumentException("Type not supported")
            }
        } catch (e: Exception) {
            prefName.data.default as T
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getNullableVal(
        prefName: PrefName,
        default: T?
    ): T? {  //Strings don't necessarily need to use this one
        return try {
            val pref = getPrefLocation(prefName.data.prefLocation)
            when (prefName.data.type) {
                Boolean::class -> pref.getBoolean(prefName.name, default as Boolean) as T?
                Int::class -> pref.getInt(prefName.name, default as Int) as T?
                Float::class -> pref.getFloat(prefName.name, default as Float) as T?
                Long::class -> pref.getLong(prefName.name, default as Long) as T?
                String::class -> pref.getString(prefName.name, default as String?) as T?
                Set::class -> pref.getStringSet(prefName.name, default as Set<String>) as T?

                else -> deserializeClass(prefName.name, default, prefName.data.prefLocation)
            }
        } catch (e: Exception) {
            default
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getCustomVal(key: String, default: T): T {
        return try {
            when (default) {
                is Boolean -> irrelevantPreferences!!.getBoolean(key, default) as T
                is Int -> irrelevantPreferences!!.getInt(key, default) as T
                is Float -> irrelevantPreferences!!.getFloat(key, default) as T
                is Long -> irrelevantPreferences!!.getLong(key, default) as T
                is String -> irrelevantPreferences!!.getString(key, default) as T
                is Set<*> -> irrelevantPreferences!!.getStringSet(key, default as Set<String>) as T
                else -> throw IllegalArgumentException("Type not supported")
            }
        } catch (e: Exception) {
            default
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getNullableCustomVal(key: String, default: T?, clazz: Class<T>): T? {
        return try {
            when {
                clazz.isAssignableFrom(Boolean::class.java) -> irrelevantPreferences!!.getBoolean(
                    key,
                    default as? Boolean ?: false
                ) as T?

                clazz.isAssignableFrom(Int::class.java) -> irrelevantPreferences!!.getInt(
                    key,
                    default as? Int ?: 0
                ) as T?

                clazz.isAssignableFrom(Float::class.java) -> irrelevantPreferences!!.getFloat(
                    key,
                    default as? Float ?: 0f
                ) as T?

                clazz.isAssignableFrom(Long::class.java) -> irrelevantPreferences!!.getLong(
                    key,
                    default as? Long ?: 0L
                ) as T?

                clazz.isAssignableFrom(String::class.java) -> irrelevantPreferences!!.getString(
                    key,
                    default as? String
                ) as T?

                clazz.isAssignableFrom(Set::class.java) -> irrelevantPreferences!!.getStringSet(
                    key,
                    default as? Set<String> ?: setOf()
                ) as T?

                else -> deserializeClass(key, default, Location.Irrelevant)
            }
        } catch (e: Exception) {
            default
        }
    }


    fun removeVal(prefName: PrefName) {
        val pref = getPrefLocation(prefName.data.prefLocation)
        with(pref.edit()) {
            remove(prefName.name)
            apply()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> setCustomVal(key: String, value: T?) {
        //for custom force irrelevant
        when (value) {
            is Boolean -> irrelevantPreferences!!.edit().putBoolean(key, value as Boolean).apply()
            is Int -> irrelevantPreferences!!.edit().putInt(key, value as Int).apply()
            is Float -> irrelevantPreferences!!.edit().putFloat(key, value as Float).apply()
            is Long -> irrelevantPreferences!!.edit().putLong(key, value as Long).apply()
            is String -> irrelevantPreferences!!.edit().putString(key, value as String).apply()
            is Set<*> -> irrelevantPreferences!!.edit().putStringSet(key, value as Set<String>).apply()
            null -> irrelevantPreferences!!.edit().remove(key).apply()
            else -> {
                // serializeClass handles apply internally
                serializeClass(key, value, Location.Irrelevant)
            }
        }
    }

    fun removeCustomVal(key: String) {
        //for custom force irrelevant
        with(irrelevantPreferences!!.edit()) {
            remove(key)
            apply()
        }
    }

    /** Whether a custom (irrelevant-location) key has ever been written. Lets callers tell an
     *  explicit stored choice apart from a getter falling back to its default. */
    fun customValExists(key: String): Boolean = irrelevantPreferences?.contains(key) ?: false

    /**
     * Key prefixes for the trackers' id-resolution caches, none of which are read from here any
     * more — they live in [ani.dantotsu.connections.IdCache] now, which bounds them and keeps them
     * out of the preferences map entirely. See that class for why they had to move.
     *
     * The first three were already dead before the move: each was retired by bumping the prefix in
     * its own file, which left every key the old prefix had written behind for good, because
     * nothing ever deleted them.
     *
     * Everything here is derived data, so dropping it costs a lookup the next time it is wanted and
     * nothing more — there is deliberately no migration of the old values into the new store.
     */
    private val STALE_CUSTOM_PREFIXES = listOf(
        "kitsu_media2_",        // retired generation of kitsu_media3_
        "simkl_id_",            // retired generation of simkl_id2_
        "simkl_eps_",           // retired generation of simkl_eps2_
        "kitsu_media3_",        // the rest moved to IdCache
        "kitsu_total2_",
        "simkl_id2_",
        "simkl_eps2_",
        "mangabaka_series_",
        "mangabaka_al_",
        "mangabaka_mal_",
        "mangabaka_mu_",
        "mu_mal_id_",
    )

    /** Bump alongside [STALE_CUSTOM_PREFIXES] to sweep a newly retired set of keys. */
    private const val STALE_PRUNE_VERSION = 2

    /**
     * Drops the keys listed in [STALE_CUSTOM_PREFIXES], once per version.
     *
     * They hold one key per media and never expired, so the Irrelevant file grew into tens of
     * thousands of entries on a device that syncs its lists — and that whole map is resident for
     * the life of the process, re-serialised in full on every write, cloned in full by
     * `SharedPreferences.apply()` whenever a write is already in flight, packaged into every backup
     * and walked by every cloud progress sync. All of that for keys nothing here consults now.
     *
     * Must not run on the main thread: reading `all` copies the entire map.
     */
    fun pruneStaleCustomVals() {
        val prefs = irrelevantPreferences ?: return
        if (getVal<Int>(PrefName.CustomValPruneVersion) >= STALE_PRUNE_VERSION) return
        val dead = prefs.all.keys.filter { key ->
            STALE_CUSTOM_PREFIXES.any { key.startsWith(it) }
        }
        if (dead.isNotEmpty()) {
            with(prefs.edit()) {
                dead.forEach { remove(it) }
                apply()
            }
        }
        setVal(PrefName.CustomValPruneVersion, STALE_PRUNE_VERSION)
        Logger.log("PrefManager: pruned ${dead.size} stale cached ids")
    }

    /**
     * Batched form of [setCustomVal]/[removeCustomVal]: writes every entry through a single
     * [SharedPreferences.Editor] and one `apply()` call.
     *
     * Each `apply()` queues a task in Android's `QueuedWork`, and *any* Activity/Service stop
     * across the whole process blocks the main thread until that queue drains. A caller writing
     * many keys in a loop (e.g. a sync pull touching hundreds of per-media entries) via the
     * single-key setters was firing hundreds of separate `apply()`s and caused background ANRs
     * when a service happened to stop mid-flush. Batching collapses that to one flush.
     */
    @Suppress("UNCHECKED_CAST")
    fun applyCustomVals(sets: Map<String, Any> = emptyMap(), removes: Set<String> = emptySet()) {
        if (sets.isEmpty() && removes.isEmpty()) return
        with(irrelevantPreferences!!.edit()) {
            removes.forEach { remove(it) }
            sets.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is Long -> putLong(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value as Set<String>)
                }
            }
            apply()
        }
    }

    /**
     * Retrieves all SharedPreferences entries with keys starting with the specified prefix.
     *
     * @param prefix The prefix to filter keys.
     * @return A map containing key-value pairs that match the prefix.
     */
    fun getAllCustomValsForMedia(prefix: String): Map<String, Any?> {
        val prefs = irrelevantPreferences ?: return emptyMap()
        val allEntries = mutableMapOf<String, Any?>()

        prefs.all.forEach { (key, value) ->
            if (key.startsWith(prefix)) {
                allEntries[key] = value
            }
        }

        return allEntries
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getLiveVal(prefName: PrefName, default: T): SharedPreferenceLiveData<T> {
        val pref = getPrefLocation(prefName.data.prefLocation)
        return when (prefName.data.type) {
            Boolean::class -> SharedPreferenceBooleanLiveData(
                pref,
                prefName.name,
                default as Boolean
            ) as SharedPreferenceLiveData<T>

            Int::class -> SharedPreferenceIntLiveData(
                pref,
                prefName.name,
                default as Int
            ) as SharedPreferenceLiveData<T>

            Float::class -> SharedPreferenceFloatLiveData(
                pref,
                prefName.name,
                default as Float
            ) as SharedPreferenceLiveData<T>

            Long::class -> SharedPreferenceLongLiveData(
                pref,
                prefName.name,
                default as Long
            ) as SharedPreferenceLiveData<T>

            String::class -> SharedPreferenceStringLiveData(
                pref,
                prefName.name,
                default as String
            ) as SharedPreferenceLiveData<T>

            Set::class -> SharedPreferenceStringSetLiveData(
                pref,
                prefName.name,
                default as Set<String>
            ) as SharedPreferenceLiveData<T>

            else -> SharedPreferenceClassLiveData(
                pref,
                prefName.name,
                default
            )
        }
    }

    fun SharedPreferenceLiveData<*>.asLiveBool(): SharedPreferenceBooleanLiveData =
        this as? SharedPreferenceBooleanLiveData
            ?: throw ClassCastException("Cannot cast to SharedPreferenceLiveData<Boolean>")

    fun SharedPreferenceLiveData<*>.asLiveInt(): SharedPreferenceIntLiveData =
        this as? SharedPreferenceIntLiveData
            ?: throw ClassCastException("Cannot cast to SharedPreferenceLiveData<Int>")

    fun SharedPreferenceLiveData<*>.asLiveFloat(): SharedPreferenceFloatLiveData =
        this as? SharedPreferenceFloatLiveData
            ?: throw ClassCastException("Cannot cast to SharedPreferenceLiveData<Float>")

    fun SharedPreferenceLiveData<*>.asLiveLong(): SharedPreferenceLongLiveData =
        this as? SharedPreferenceLongLiveData
            ?: throw ClassCastException("Cannot cast to SharedPreferenceLiveData<Long>")

    fun SharedPreferenceLiveData<*>.asLiveString(): SharedPreferenceStringLiveData =
        this as? SharedPreferenceStringLiveData
            ?: throw ClassCastException("Cannot cast to SharedPreferenceLiveData<String>")

    fun SharedPreferenceLiveData<*>.asLiveStringSet(): SharedPreferenceStringSetLiveData =
        this as? SharedPreferenceStringSetLiveData
            ?: throw ClassCastException("Cannot cast to SharedPreferenceLiveData<Set<String>>")

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T> SharedPreferenceLiveData<*>.asLiveClass(): SharedPreferenceClassLiveData<T> =
        this as? SharedPreferenceClassLiveData<T>
            ?: throw ClassCastException("Cannot cast to SharedPreferenceLiveData<T>")

    fun getAnimeDownloadPreferences(): SharedPreferences =
        animeDownloadsPreferences!!  //needs to be used externally

    /**
     * [Location.Reader] and [Location.NovelReader] share one [SharedPreferences] file, so naively
     * mapping every location would pack that file twice — doubling the payload and importing it
     * twice on the way back in. Keep only the first location per distinct file.
     */
    private fun distinctLocations(prefLocation: List<Location>): Map<Location, SharedPreferences> =
        prefLocation.distinctBy { getPrefLocation(it) }.associateWith { getPrefLocation(it) }

    fun exportAllPrefs(prefLocation: List<Location>): String {
        return PreferencePackager.pack(distinctLocations(prefLocation))
    }

    fun exportSelectedPrefs(prefLocation: List<Location>, includeKeys: Set<String>): String {
        return PreferencePackager.pack(distinctLocations(prefLocation), includeKeys)
    }

    /**
     * Packs the given locations for cloud sync, keeping only the keys [keyFilter] accepts. Unlike
     * [exportAllPrefs] the caller is expected to leave out [Location.Protected] so secrets never
     * leave the device.
     */
    fun exportSyncablePrefs(
        prefLocation: List<Location>,
        keyFilter: (Location, String) -> Boolean,
    ): String {
        return PreferencePackager.pack(
            distinctLocations(prefLocation),
            includeKeys = null,
            keyFilter = keyFilter,
        )
    }

    /**
     * Applies a packed-prefs JSON string produced by [exportSyncablePrefs]/[exportAllPrefs].
     * Silent: this runs on the cloud-sync background path, where import toasts are just noise.
     *
     * @param filter see [importAllPrefs]; pass the same filter used to build the payload.
     */
    fun importPackedPrefs(
        json: String,
        filter: ((Location, String) -> Boolean)? = null,
    ): Boolean = PreferencePackager.unpack(json, silent = true, filter = filter)


    /**
     * @param prefs Map of preferences to import
     * @param prefLocation Location to import to
     * @param silent skips the result snackbars (background cloud sync)
     * @param filter when set, the key set this import may touch, in both directions:
     *   - keys it *rejects* are never written. The payload decides which keys it contains, and on
     *     the cloud path that payload is whatever was in the database — so without this, a node
     *     naming [Location.Protected] would write straight into the secrets store, and a key this
     *     build has since reclassified as device-local would still arrive from an older peer.
     *   - keys it *accepts* that are absent from [prefs] are deleted. Import is otherwise purely
     *     additive, so a pref deleted on another device would linger here forever — and this
     *     device's next push would resurrect it there.
     *
     *   Pass the same predicate used to build the payload, so the set that can be written is
     *   exactly the set that would have been uploaded: secrets, caches and the sync's own
     *   bookkeeping are outside it and never touched.
     * @return true if successful, false if error
     */

    @Suppress("UNCHECKED_CAST")
    fun importAllPrefs(
        prefs: Map<String, *>,
        prefLocation: Location,
        silent: Boolean = false,
        filter: ((Location, String) -> Boolean)? = null,
    ): Boolean {
        if (prefs.isEmpty()) return true
        val pref = getPrefLocation(prefLocation)
        var hadError = false
        // What little of the credential store syncs is identity hints — the display names of
        // accounts signed in elsewhere — and those follow a different rule from settings. A device
        // that isn't signed in to a tracker has *nothing to say* about it, rather than an
        // instruction to forget it, so its payload must not clear a name the receiving device knows
        // from its own login. Hence: never pruned here, and never overwritten with a blank.
        val hintsOnly = prefLocation == Location.Protected
        with(pref.edit()) {
            if (filter != null && !hintsOnly) {
                pref.all.keys.forEach { key ->
                    if (key !in prefs && filter(prefLocation, key)) {
                        remove(key)
                        Logger.log("importAllPrefs: pruned $key from $prefLocation")
                    }
                }
            }
            prefs.forEach { (key, value) ->
                if (filter != null && !filter(prefLocation, key)) {
                    Logger.log("importAllPrefs: rejected $key in $prefLocation")
                    return@forEach
                }
                if (hintsOnly && (value as? String).isNullOrBlank()) return@forEach
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is Long -> putLong(key, value)
                    is String -> putString(key, value)
                    is HashSet<*> -> putStringSet(key, value as Set<String>)
                    is ArrayList<*> -> putStringSet(key, arrayListToSet(value))
                    is Set<*> -> putStringSet(key, value as Set<String>)
                    else -> hadError = true
                }
            }
            apply()
            return if (hadError) {
                if (!silent) snackString(R.string.error_importing_preferences)
                else Logger.log("importAllPrefs: dropped unsupported value(s) in $prefLocation")
                false
            } else {
                if (!silent) snackString(R.string.preferences_imported)
                true
            }
        }
    }

    private fun arrayListToSet(arrayList: ArrayList<*>): Set<String> {
        return arrayList.map { it.toString() }.toSet()
    }

    private fun getPrefLocation(prefLoc: Location): SharedPreferences {
        return when (prefLoc) {
            Location.General -> generalPreferences
            Location.UI -> uiPreferences
            Location.Player -> playerPreferences
            Location.Reader -> readerPreferences
            Location.NovelReader -> readerPreferences
            Location.Irrelevant -> irrelevantPreferences
            Location.AnimeDownloads -> animeDownloadsPreferences
            Location.Protected -> protectedPreferences
        }!!
    }

    private fun <T> serializeClass(key: String, value: T, location: Location) {
        val pref = getPrefLocation(location)
        try {
            val bos = ByteArrayOutputStream()
            ObjectOutputStream(bos).use { oos ->
                oos.writeObject(value)
            }

            val serialized = Base64.encodeToString(bos.toByteArray(), Base64.DEFAULT)
            val editor = pref.edit()
            editor.putString(key, serialized)
            editor.apply() // Asynchronous write to disk to prevent ANR
        } catch (e: Exception) {
            snackString(R.string.error_serializing_preference, null, e.message)
            Logger.log(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> deserializeClass(key: String, default: T?, location: Location): T? {
        return try {
            val pref = getPrefLocation(location)
            val serialized = pref.getString(key, null)
            if (serialized != null) {
                val data = Base64.decode(serialized, Base64.DEFAULT)
                val bis = ByteArrayInputStream(data)
                val ois = ObjectInputStream(bis)
                ois.readObject() as T?
            } else {
                Logger.log("Serialized data is null (key: $key)")
                default
            }
        } catch (e: java.io.InvalidClassException) {
            // The stored blob was written by a build whose version of this class doesn't match
            // ours. Fall back to the default for now, but *keep* what's stored: this used to
            // delete it, which turned a mismatch that another build (or a later one, once the
            // class settles) can still read into permanent loss of the user's saved filters,
            // search history or home layout. A subsequent write replaces it normally.
            Logger.log("deserializeClass: $key was written by an incompatible build; keeping it")
            Logger.log(e)
            default
        } catch (e: Exception) {
            Logger.log(e)
            default
        }
    }
}

// Entries in media-ID sets are stored as "id" or "id||name".
fun Set<String>.containsMediaId(id: String) = any { it == id || it.startsWith("$id||") }
fun Set<String>.removeMediaId(id: String) = filter { it != id && !it.startsWith("$id||") }.toSet()