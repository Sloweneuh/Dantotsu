package ani.dantotsu.media.savedfilters

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object SavedFiltersStore {

    // ---- AniMangaSearchResults presets (split per type, anime vs manga) ----

    fun loadAniManga(type: String): List<SavedAniMangaFilter> =
        PrefManager.getVal<List<SavedAniMangaFilter>>(PrefName.SavedAniMangaFilters)
            .filter { it.type == type }

    fun saveAniManga(preset: SavedAniMangaFilter) {
        val all = PrefManager.getVal<List<SavedAniMangaFilter>>(PrefName.SavedAniMangaFilters)
            .toMutableList()
        all.removeAll { it.type == preset.type && it.name == preset.name }
        all.add(preset)
        PrefManager.setVal(PrefName.SavedAniMangaFilters, all.toList())
    }

    fun deleteAniManga(type: String, name: String) {
        val all = PrefManager.getVal<List<SavedAniMangaFilter>>(PrefName.SavedAniMangaFilters)
            .toMutableList()
        all.removeAll { it.type == type && it.name == name }
        PrefManager.setVal(PrefName.SavedAniMangaFilters, all.toList())
    }

    fun renameAniManga(type: String, oldName: String, newName: String) {
        val all = PrefManager.getVal<List<SavedAniMangaFilter>>(PrefName.SavedAniMangaFilters)
            .toMutableList()
        val idx = all.indexOfFirst { it.type == type && it.name == oldName }
        if (idx < 0) return
        all[idx] = all[idx].copy(name = newName)
        PrefManager.setVal(PrefName.SavedAniMangaFilters, all.toList())
    }

    // ---- MangaUpdates presets ----

    fun loadMU(): List<SavedMUFilter> =
        PrefManager.getVal<List<SavedMUFilter>>(PrefName.SavedMUFilters)

    fun saveMU(preset: SavedMUFilter) {
        val all = loadMU().toMutableList()
        all.removeAll { it.name == preset.name }
        all.add(preset)
        PrefManager.setVal(PrefName.SavedMUFilters, all.toList())
    }

    fun deleteMU(name: String) {
        val all = loadMU().toMutableList()
        all.removeAll { it.name == name }
        PrefManager.setVal(PrefName.SavedMUFilters, all.toList())
    }

    fun renameMU(oldName: String, newName: String) {
        val all = loadMU().toMutableList()
        val idx = all.indexOfFirst { it.name == oldName }
        if (idx < 0) return
        all[idx] = all[idx].copy(name = newName)
        PrefManager.setVal(PrefName.SavedMUFilters, all.toList())
    }

    // ---- Comick presets ----

    fun loadComick(): List<SavedComickFilter> =
        PrefManager.getVal<List<SavedComickFilter>>(PrefName.SavedComickFilters)

    fun saveComick(preset: SavedComickFilter) {
        val all = loadComick().toMutableList()
        all.removeAll { it.name == preset.name }
        all.add(preset)
        PrefManager.setVal(PrefName.SavedComickFilters, all.toList())
    }

    fun deleteComick(name: String) {
        val all = loadComick().toMutableList()
        all.removeAll { it.name == name }
        PrefManager.setVal(PrefName.SavedComickFilters, all.toList())
    }

    fun renameComick(oldName: String, newName: String) {
        val all = loadComick().toMutableList()
        val idx = all.indexOfFirst { it.name == oldName }
        if (idx < 0) return
        all[idx] = all[idx].copy(name = newName)
        PrefManager.setVal(PrefName.SavedComickFilters, all.toList())
    }

    // ---- User list filter presets (split per anime/manga) ----

    fun loadList(isAnime: Boolean): List<SavedListFilter> =
        PrefManager.getVal<List<SavedListFilter>>(PrefName.SavedListFilters)
            .filter { it.isAnime == isAnime }

    fun saveList(preset: SavedListFilter) {
        val all = PrefManager.getVal<List<SavedListFilter>>(PrefName.SavedListFilters)
            .toMutableList()
        all.removeAll { it.isAnime == preset.isAnime && it.name == preset.name }
        all.add(preset)
        PrefManager.setVal(PrefName.SavedListFilters, all.toList())
    }

    fun deleteList(isAnime: Boolean, name: String) {
        val all = PrefManager.getVal<List<SavedListFilter>>(PrefName.SavedListFilters)
            .toMutableList()
        all.removeAll { it.isAnime == isAnime && it.name == name }
        PrefManager.setVal(PrefName.SavedListFilters, all.toList())
    }

    fun renameList(isAnime: Boolean, oldName: String, newName: String) {
        val all = PrefManager.getVal<List<SavedListFilter>>(PrefName.SavedListFilters)
            .toMutableList()
        val idx = all.indexOfFirst { it.isAnime == isAnime && it.name == oldName }
        if (idx < 0) return
        all[idx] = all[idx].copy(name = newName)
        PrefManager.setVal(PrefName.SavedListFilters, all.toList())
    }

    // ---- Extension presets (bundled by source.id under one PrefName) ----
    //
    // Stored as a single List<SavedExtensionFilterBundle> at Location.General so
    // the existing backup/restore (which only exports `exportable` Locations)
    // includes them. Earlier versions kept these under per-source custom keys
    // in Location.Irrelevant — [migrateLegacyExtensionPresets] folds those in
    // on first access.

    private const val LEGACY_EXTENSION_KEY_PREFIX = "saved_ext_filters_"

    private fun loadAllExtensionBundles(): List<SavedExtensionFilterBundle> {
        migrateLegacyExtensionPresets()
        return PrefManager.getVal<List<SavedExtensionFilterBundle>>(PrefName.SavedExtensionFilters)
    }

    private fun saveAllExtensionBundles(bundles: List<SavedExtensionFilterBundle>) {
        // Drop empty bundles so we don't accumulate cruft for sources the user
        // experimented with and then cleared.
        PrefManager.setVal(
            PrefName.SavedExtensionFilters,
            bundles.filter { it.presets.isNotEmpty() },
        )
    }

    fun loadExtension(sourceId: Long): List<SavedExtensionFilter> =
        loadAllExtensionBundles().firstOrNull { it.sourceId == sourceId }?.presets ?: emptyList()

    fun saveExtension(sourceId: Long, preset: SavedExtensionFilter) {
        val bundles = loadAllExtensionBundles().toMutableList()
        val idx = bundles.indexOfFirst { it.sourceId == sourceId }
        val updated = if (idx >= 0) {
            val merged = bundles[idx].presets.filter { it.name != preset.name } + preset
            bundles[idx] = bundles[idx].copy(presets = merged)
            bundles
        } else {
            bundles + SavedExtensionFilterBundle(sourceId, listOf(preset))
        }
        saveAllExtensionBundles(updated)
    }

    fun deleteExtension(sourceId: Long, name: String) {
        val bundles = loadAllExtensionBundles().toMutableList()
        val idx = bundles.indexOfFirst { it.sourceId == sourceId }
        if (idx < 0) return
        bundles[idx] = bundles[idx].copy(presets = bundles[idx].presets.filter { it.name != name })
        saveAllExtensionBundles(bundles)
    }

    fun renameExtension(sourceId: Long, oldName: String, newName: String) {
        val bundles = loadAllExtensionBundles().toMutableList()
        val bIdx = bundles.indexOfFirst { it.sourceId == sourceId }
        if (bIdx < 0) return
        val pIdx = bundles[bIdx].presets.indexOfFirst { it.name == oldName }
        if (pIdx < 0) return
        val newPresets = bundles[bIdx].presets.toMutableList().also {
            it[pIdx] = it[pIdx].copy(name = newName)
        }
        bundles[bIdx] = bundles[bIdx].copy(presets = newPresets)
        saveAllExtensionBundles(bundles)
    }

    /**
     * Folds any pre-existing per-source custom keys (`saved_ext_filters_<id>`)
     * stored in the non-exportable Irrelevant location into the new bundled
     * pref, then deletes them. Idempotent: a no-op once the legacy keys are
     * gone, so it's safe to call from every load/save.
     */
    @Suppress("UNCHECKED_CAST")
    private fun migrateLegacyExtensionPresets() {
        val legacy = PrefManager.getAllCustomValsForMedia(LEGACY_EXTENSION_KEY_PREFIX)
        if (legacy.isEmpty()) return
        val incoming = mutableListOf<SavedExtensionFilterBundle>()
        legacy.keys.forEach { key ->
            val sourceId = key.removePrefix(LEGACY_EXTENSION_KEY_PREFIX).toLongOrNull()
                ?: return@forEach
            val presets = PrefManager.getNullableCustomVal(
                key, null as List<SavedExtensionFilter>?,
                List::class.java as Class<List<SavedExtensionFilter>>
            )
            if (!presets.isNullOrEmpty()) {
                incoming.add(SavedExtensionFilterBundle(sourceId, presets))
            }
        }
        if (incoming.isNotEmpty()) {
            // The new pref wins on collisions — its data is what the user has
            // most recently interacted with through this version.
            val current = PrefManager
                .getVal<List<SavedExtensionFilterBundle>>(PrefName.SavedExtensionFilters)
            val byId = LinkedHashMap<Long, SavedExtensionFilterBundle>()
            incoming.forEach { byId[it.sourceId] = it }
            current.forEach { byId[it.sourceId] = it }
            PrefManager.setVal(
                PrefName.SavedExtensionFilters,
                byId.values.filter { it.presets.isNotEmpty() }.toList(),
            )
        }
        legacy.keys.forEach { PrefManager.removeCustomVal(it) }
    }

    // ---- Extension filter snapshot helpers ----

    fun snapshotMangaFilters(fl: FilterList): List<Any?> {
        val out = mutableListOf<Any?>()
        fl.list.forEach { collectMangaState(it, out) }
        return out
    }

    fun snapshotAnimeFilters(fl: AnimeFilterList): List<Any?> {
        val out = mutableListOf<Any?>()
        fl.list.forEach { collectAnimeState(it, out) }
        return out
    }

    /** Returns true if states were applied; false if shape mismatch. */
    fun applyMangaFilters(fl: FilterList, states: List<Any?>): Boolean {
        val cursor = intArrayOf(0)
        return try {
            fl.list.forEach { applyMangaState(it, states, cursor) }
            cursor[0] == states.size
        } catch (_: Throwable) {
            false
        }
    }

    fun applyAnimeFilters(fl: AnimeFilterList, states: List<Any?>): Boolean {
        val cursor = intArrayOf(0)
        return try {
            fl.list.forEach { applyAnimeState(it, states, cursor) }
            cursor[0] == states.size
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectMangaState(f: Filter<*>, out: MutableList<Any?>) {
        when (f) {
            is Filter.Header, is Filter.Separator -> Unit
            is Filter.Group<*> -> (f.state as? List<*>)?.forEach { child ->
                if (child is Filter<*>) collectMangaState(child, out)
            }
            is Filter.Select<*> -> out.add((f as Filter<Int>).state)
            is Filter.Text -> out.add((f as Filter<String>).state)
            is Filter.CheckBox -> out.add((f as Filter<Boolean>).state)
            is Filter.TriState -> out.add((f as Filter<Int>).state)
            is Filter.Sort -> {
                val s = (f as Filter<Filter.Sort.Selection?>).state
                out.add(s?.let { SavedSortSelection(it.index, it.ascending) })
            }
            else -> out.add(null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectAnimeState(f: AnimeFilter<*>, out: MutableList<Any?>) {
        when (f) {
            is AnimeFilter.Header, is AnimeFilter.Separator -> Unit
            is AnimeFilter.Group<*> -> (f.state as? List<*>)?.forEach { child ->
                if (child is AnimeFilter<*>) collectAnimeState(child, out)
            }
            is AnimeFilter.Select<*> -> out.add((f as AnimeFilter<Int>).state)
            is AnimeFilter.Text -> out.add((f as AnimeFilter<String>).state)
            is AnimeFilter.CheckBox -> out.add((f as AnimeFilter<Boolean>).state)
            is AnimeFilter.TriState -> out.add((f as AnimeFilter<Int>).state)
            is AnimeFilter.Sort -> {
                val s = (f as AnimeFilter<AnimeFilter.Sort.Selection?>).state
                out.add(s?.let { SavedSortSelection(it.index, it.ascending) })
            }
            else -> out.add(null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyMangaState(f: Filter<*>, states: List<Any?>, cursor: IntArray) {
        when (f) {
            is Filter.Header, is Filter.Separator -> Unit
            is Filter.Group<*> -> (f.state as? List<*>)?.forEach { child ->
                if (child is Filter<*>) applyMangaState(child, states, cursor)
            }
            is Filter.Select<*> -> {
                (states.getOrNull(cursor[0]) as? Int)?.let { (f as Filter<Int>).state = it }
                cursor[0]++
            }
            is Filter.Text -> {
                (states.getOrNull(cursor[0]) as? String)?.let { (f as Filter<String>).state = it }
                cursor[0]++
            }
            is Filter.CheckBox -> {
                (states.getOrNull(cursor[0]) as? Boolean)?.let { (f as Filter<Boolean>).state = it }
                cursor[0]++
            }
            is Filter.TriState -> {
                (states.getOrNull(cursor[0]) as? Int)?.let { (f as Filter<Int>).state = it }
                cursor[0]++
            }
            is Filter.Sort -> {
                val v = states.getOrNull(cursor[0])
                (f as Filter<Filter.Sort.Selection?>).state = when (v) {
                    is SavedSortSelection -> Filter.Sort.Selection(v.index, v.ascending)
                    null -> null
                    else -> f.state
                }
                cursor[0]++
            }
            else -> cursor[0]++
        }
    }

    /**
     * Produces one chip label per active extension filter by walking the live
     * FilterList in parallel with the saved snapshot. Falls back to the model's
     * own placeholder chips on shape mismatch.
     */
    fun chipsForExtension(states: List<Any?>, fl: Any): List<String> {
        val out = mutableListOf<String>()
        val cursor = intArrayOf(0)
        try {
            when (fl) {
                is FilterList -> fl.list.forEach { addMangaSummary(it, states, cursor, out) }
                is AnimeFilterList -> fl.list.forEach { addAnimeSummary(it, states, cursor, out) }
            }
        } catch (_: Throwable) {
            return SavedExtensionFilter("", states).chips()
        }
        return out
    }

    private fun addMangaSummary(
        f: Filter<*>, states: List<Any?>, cursor: IntArray, out: MutableList<String>,
    ) {
        when (f) {
            is Filter.Header, is Filter.Separator -> Unit
            is Filter.Group<*> -> @Suppress("UNCHECKED_CAST")
                (f.state as? List<*>)?.forEach { c ->
                    if (c is Filter<*>) addMangaSummary(c, states, cursor, out)
                }
            is Filter.Select<*> -> {
                val v = states.getOrNull(cursor[0]) as? Int
                if (v != null && v != 0) {
                    val label = f.values.getOrNull(v)?.toString()
                    if (label != null) out += "${f.name}: $label"
                }
                cursor[0]++
            }
            is Filter.Text -> {
                val v = states.getOrNull(cursor[0]) as? String
                if (!v.isNullOrEmpty()) out += "${f.name}: $v"
                cursor[0]++
            }
            is Filter.CheckBox -> {
                if (states.getOrNull(cursor[0]) == true) out += f.name
                cursor[0]++
            }
            is Filter.TriState -> {
                val v = states.getOrNull(cursor[0]) as? Int
                when (v) {
                    Filter.TriState.STATE_INCLUDE -> out += "+${f.name}"
                    Filter.TriState.STATE_EXCLUDE -> out += "-${f.name}"
                }
                cursor[0]++
            }
            is Filter.Sort -> {
                val v = states.getOrNull(cursor[0]) as? SavedSortSelection
                if (v != null) {
                    val label = f.values.getOrNull(v.index) ?: ""
                    out += "${f.name}: $label ${if (v.ascending) "↑" else "↓"}"
                }
                cursor[0]++
            }
            else -> cursor[0]++
        }
    }

    private fun addAnimeSummary(
        f: AnimeFilter<*>, states: List<Any?>, cursor: IntArray, out: MutableList<String>,
    ) {
        when (f) {
            is AnimeFilter.Header, is AnimeFilter.Separator -> Unit
            is AnimeFilter.Group<*> -> @Suppress("UNCHECKED_CAST")
                (f.state as? List<*>)?.forEach { c ->
                    if (c is AnimeFilter<*>) addAnimeSummary(c, states, cursor, out)
                }
            is AnimeFilter.Select<*> -> {
                val v = states.getOrNull(cursor[0]) as? Int
                if (v != null && v != 0) {
                    val label = f.values.getOrNull(v)?.toString()
                    if (label != null) out += "${f.name}: $label"
                }
                cursor[0]++
            }
            is AnimeFilter.Text -> {
                val v = states.getOrNull(cursor[0]) as? String
                if (!v.isNullOrEmpty()) out += "${f.name}: $v"
                cursor[0]++
            }
            is AnimeFilter.CheckBox -> {
                if (states.getOrNull(cursor[0]) == true) out += f.name
                cursor[0]++
            }
            is AnimeFilter.TriState -> {
                val v = states.getOrNull(cursor[0]) as? Int
                when (v) {
                    AnimeFilter.TriState.STATE_INCLUDE -> out += "+${f.name}"
                    AnimeFilter.TriState.STATE_EXCLUDE -> out += "-${f.name}"
                }
                cursor[0]++
            }
            is AnimeFilter.Sort -> {
                val v = states.getOrNull(cursor[0]) as? SavedSortSelection
                if (v != null) {
                    val label = f.values.getOrNull(v.index) ?: ""
                    out += "${f.name}: $label ${if (v.ascending) "↑" else "↓"}"
                }
                cursor[0]++
            }
            else -> cursor[0]++
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyAnimeState(f: AnimeFilter<*>, states: List<Any?>, cursor: IntArray) {
        when (f) {
            is AnimeFilter.Header, is AnimeFilter.Separator -> Unit
            is AnimeFilter.Group<*> -> (f.state as? List<*>)?.forEach { child ->
                if (child is AnimeFilter<*>) applyAnimeState(child, states, cursor)
            }
            is AnimeFilter.Select<*> -> {
                (states.getOrNull(cursor[0]) as? Int)?.let { (f as AnimeFilter<Int>).state = it }
                cursor[0]++
            }
            is AnimeFilter.Text -> {
                (states.getOrNull(cursor[0]) as? String)?.let { (f as AnimeFilter<String>).state = it }
                cursor[0]++
            }
            is AnimeFilter.CheckBox -> {
                (states.getOrNull(cursor[0]) as? Boolean)?.let { (f as AnimeFilter<Boolean>).state = it }
                cursor[0]++
            }
            is AnimeFilter.TriState -> {
                (states.getOrNull(cursor[0]) as? Int)?.let { (f as AnimeFilter<Int>).state = it }
                cursor[0]++
            }
            is AnimeFilter.Sort -> {
                val v = states.getOrNull(cursor[0])
                (f as AnimeFilter<AnimeFilter.Sort.Selection?>).state = when (v) {
                    is SavedSortSelection -> AnimeFilter.Sort.Selection(v.index, v.ascending)
                    null -> null
                    else -> f.state
                }
                cursor[0]++
            }
            else -> cursor[0]++
        }
    }
}
