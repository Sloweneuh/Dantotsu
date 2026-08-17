package ani.dantotsu.parsers.novel.lnreader

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.json.JSONArray
import org.json.JSONObject

/**
 * An LNReader plugin's declared filters, expressed as an Aniyomi [FilterList].
 *
 * A plugin declares filters as a JSON object keyed by name, where each entry carries its label, its
 * type, its options and — in the same field — its current value. The app already has a filter sheet,
 * saved presets and active-filter chips built against [Filter], so rather than growing a second
 * filter UI for novels the declaration is translated into that vocabulary and translated back on the
 * way out.
 *
 * The round trip matters more than it looks: plugins read straight through to `filters.key.value`,
 * so what goes back has to be the *whole* declaration with values replaced, not just the values.
 * That is why the original object is kept and copied per call.
 */
class LNReaderFilterSet private constructor(
    private val declaration: JSONObject,
    private val entries: List<Entry>,
) {

    /** A live list: the sheet mutates these filters in place, and [valuesJson] reads them back. */
    val filterList: FilterList = FilterList(entries.map { it.filter })

    /**
     * The declaration with every value replaced by what the user selected, ready to hand to
     * `popularNovels`.
     */
    fun valuesJson(): String {
        val out = JSONObject(declaration.toString())
        entries.forEach { entry ->
            val spec = out.optJSONObject(entry.key) ?: return@forEach
            spec.put("value", valueOf(entry.filter))
        }
        return out.toString()
    }

    private fun valueOf(filter: Filter<*>): Any = when (filter) {
        is PickerFilter -> filter.raw.getOrNull(filter.state) ?: ""
        is TextFilter -> filter.state
        is SwitchFilter -> filter.state
        is CheckGroup -> JSONArray().apply {
            filter.state.filter { it.state }.forEach { put(it.raw) }
        }
        is TriGroup -> JSONObject()
            .put("include", JSONArray().apply {
                filter.state.filter { it.isIncluded() }.forEach { put(it.raw) }
            })
            .put("exclude", JSONArray().apply {
                filter.state.filter { it.isExcluded() }.forEach { put(it.raw) }
            })
        else -> JSONObject.NULL
    }

    private class Entry(val key: String, val filter: Filter<*>)

    // The option value a plugin expects back is whatever it declared — usually a string, sometimes
    // a number — so it is carried alongside the label rather than being re-derived from it.
    private class PickerFilter(name: String, labels: Array<String>, val raw: List<Any>, state: Int) :
        Filter.Select<String>(name, labels, state)

    private class TextFilter(name: String, state: String) : Filter.Text(name, state)
    private class SwitchFilter(name: String, state: Boolean) : Filter.CheckBox(name, state)
    private class OptionCheck(name: String, val raw: Any, state: Boolean) :
        Filter.CheckBox(name, state)

    private class OptionTri(name: String, val raw: Any, state: Int) : Filter.TriState(name, state)
    private class CheckGroup(name: String, state: List<OptionCheck>) :
        Filter.Group<OptionCheck>(name, state)

    private class TriGroup(name: String, state: List<OptionTri>) :
        Filter.Group<OptionTri>(name, state)

    companion object {

        // The values of `FilterTypes` in `@libs/filterInputs`, which is what a bundle compiles to.
        private const val TEXT = "Text"
        private const val PICKER = "Picker"
        private const val CHECKBOX = "Checkbox"
        private const val SWITCH = "Switch"
        private const val EXCLUDABLE = "XCheckbox"

        /**
         * Builds a set from a plugin's declaration, or null when there is nothing to show — an
         * absent `filters`, an empty one, or one whose entries are all of types this does not know.
         */
        fun from(json: String?): LNReaderFilterSet? {
            if (json.isNullOrBlank()) return null
            val declaration = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val entries = mutableListOf<Entry>()
            declaration.keys().forEach { key ->
                val spec = declaration.optJSONObject(key) ?: return@forEach
                val label = spec.optString("label").ifBlank { key }
                val options = optionsOf(spec)
                val filter = when (spec.optString("type")) {
                    TEXT -> TextFilter(label, spec.optString("value"))
                    SWITCH -> SwitchFilter(label, spec.optBoolean("value"))
                    PICKER -> {
                        if (options.isEmpty()) return@forEach
                        val selected = options.indexOfFirst {
                            it.second.toString() == spec.opt("value")?.toString()
                        }
                        PickerFilter(
                            label,
                            options.map { it.first }.toTypedArray(),
                            options.map { it.second },
                            selected.coerceAtLeast(0),
                        )
                    }
                    CHECKBOX -> {
                        if (options.isEmpty()) return@forEach
                        val checked = stringsOf(spec.optJSONArray("value"))
                        CheckGroup(label, options.map { (optLabel, value) ->
                            OptionCheck(optLabel, value, value.toString() in checked)
                        })
                    }
                    EXCLUDABLE -> {
                        if (options.isEmpty()) return@forEach
                        val value = spec.optJSONObject("value")
                        val included = stringsOf(value?.optJSONArray("include"))
                        val excluded = stringsOf(value?.optJSONArray("exclude"))
                        TriGroup(label, options.map { (optLabel, raw) ->
                            val state = when (raw.toString()) {
                                in included -> Filter.TriState.STATE_INCLUDE
                                in excluded -> Filter.TriState.STATE_EXCLUDE
                                else -> Filter.TriState.STATE_IGNORE
                            }
                            OptionTri(optLabel, raw, state)
                        })
                    }
                    // An unknown type still has a declared value, and the copy in `valuesJson`
                    // carries it through untouched — it just gets no control in the sheet.
                    else -> return@forEach
                }
                entries.add(Entry(key, filter))
            }
            return if (entries.isEmpty()) null else LNReaderFilterSet(declaration, entries)
        }

        private fun optionsOf(spec: JSONObject): List<Pair<String, Any>> {
            val array = spec.optJSONArray("options") ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val option = array.optJSONObject(i) ?: return@mapNotNull null
                val value = option.opt("value") ?: return@mapNotNull null
                option.optString("label").ifBlank { value.toString() } to value
            }
        }

        private fun stringsOf(array: JSONArray?): Set<String> {
            if (array == null) return emptySet()
            return (0 until array.length()).mapNotNull { array.opt(it)?.toString() }.toSet()
        }
    }
}
