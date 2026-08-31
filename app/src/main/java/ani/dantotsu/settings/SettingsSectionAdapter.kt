package ani.dantotsu.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemSettingsSectionBinding
import ani.dantotsu.settings.saving.PrefManager

/**
 * One collapsible group on a settings screen.
 *
 * [rows] and [summary] are lambdas rather than values because a group's contents and its collapsed
 * one-line summary both depend on preferences the rows themselves change — expanding a card has to
 * read the current state, not the state at the moment the screen was built.
 *
 * @param key      stable id, independent of the (localized, possibly parameterised) title. Used by
 *                 [SettingsSectionAdapter.expand] to land a settings-search result on a group.
 * @param summary  what the group reads as while collapsed, e.g. an interval. Blank hides the line.
 */
data class SettingsSection(
    val key: String,
    val title: String,
    val icon: Int,
    val summary: () -> String,
    val rows: () -> List<Settings>,
)

/**
 * A vertical list of collapsible section cards, each expanding into its own [SettingsAdapter] list.
 *
 * The same idea as [AccountCardAdapter], minus everything specific to an account: it exists so a
 * section with a lot of settings can carry internal structure inside one screen instead of spending
 * a separate Activity per group.
 */
class SettingsSectionAdapter(
    private val sections: List<SettingsSection>,
    /**
     * Where to remember which cards are open, or null not to.
     *
     * Worth persisting rather than holding in memory: a lot of these settings rebuild the screen —
     * the theme rows call [ani.dantotsu.reloadActivity], and everything from banner animations to
     * blur radius calls [ani.dantotsu.restartApp], both of which `finish()` and start a fresh
     * Activity. Neither carries saved instance state. An in-memory set would therefore collapse
     * every card on each change, dropping the user out of the group they were working in at exactly
     * the moment they are most likely to adjust the setting next to it.
     */
    private val stateKey: String? = null,
    /**
     * Whether this launch should restore the cards that were open.
     *
     * True when the screen was opened *at* something — a search result naming a group — where
     * collapsing what the user already had open would be the wrong greeting. A plain entry from the
     * settings list is false, and starts collapsed.
     *
     * A relaunch the screen triggered itself counts as restore too, but is detected separately via
     * [markRelaunch]: [ani.dantotsu.restartApp] and [ani.dantotsu.reloadActivity] both start a
     * *fresh* Intent with no extras, so from the outside a blur-radius change is indistinguishable
     * from opening the screen for the first time.
     */
    private val keepExpanded: Boolean = false,
) : RecyclerView.Adapter<SettingsSectionAdapter.Holder>() {

    private val expanded: MutableSet<String> = loadExpanded()

    private fun prefKey() = "$EXPANDED_PREF_PREFIX$stateKey"
    private fun relaunchKey() = "$RELAUNCH_PREF_PREFIX$stateKey"

    private fun loadExpanded(): MutableSet<String> {
        if (stateKey == null) return mutableSetOf()

        // Consumed on read: one relaunch, one restore. Leaving it set would make every later entry
        // to the screen restore too, which is the behaviour this replaces.
        val relaunched = PrefManager.getCustomVal(relaunchKey(), false)
        if (relaunched) PrefManager.setCustomVal(relaunchKey(), false)

        if (!relaunched && !keepExpanded) {
            PrefManager.setCustomVal(prefKey(), "")
            return mutableSetOf()
        }

        // Stored as one joined string rather than a string set: section keys are plain identifiers,
        // and a set would need defensive copying on every read to avoid the documented
        // SharedPreferences trap of mutating the instance it hands back.
        return PrefManager.getCustomVal(prefKey(), "")
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
            .toMutableSet()
    }

    /**
     * Call immediately before a restart or reload this screen triggers, so the groups the user has
     * open survive it. Without it they would be collapsed by the next launch, dropping the user out
     * of the group they were working in at the moment they are most likely to adjust the setting
     * beside the one they just changed.
     */
    fun markRelaunch() {
        if (stateKey != null) PrefManager.setCustomVal(relaunchKey(), true)
    }

    private fun saveExpanded() {
        if (stateKey == null) return
        PrefManager.setCustomVal(prefKey(), expanded.joinToString(SEPARATOR))
    }

    inner class Holder(val b: ItemSettingsSectionBinding) : RecyclerView.ViewHolder(b.root)

    /** Where [key]'s card sits, or -1 if there is no such section. */
    fun positionOf(key: String): Int = sections.indexOfFirst { it.key == key }

    /** Expands [key]'s card, a no-op if it is already open — used to land a search result on a
     *  setting that lives inside a collapsed group. */
    fun expand(key: String) {
        if (expanded.add(key)) {
            saveExpanded()
            val pos = positionOf(key)
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    /** Re-reads the summary of every collapsed card. Cheap, and the only way a card that was
     *  changed from inside another one (the MangaUpdates interval follows MALSync's) catches up. */
    @SuppressWarnings("NotifyDataSetChanged")
    fun refreshSummaries() = notifyDataSetChanged()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSettingsSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = sections.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val section = sections[position]
        val b = holder.b

        b.sectionIcon.setImageResource(section.icon)
        b.sectionTitle.text = section.title
        val summary = section.summary()
        b.sectionSummary.text = summary
        b.sectionSummary.isVisible = summary.isNotBlank()

        val open = section.key in expanded
        b.sectionChevron.animate().cancel()
        b.sectionChevron.rotation = if (open) 180f else 0f
        bindBody(b, section, open)

        b.sectionHeader.setOnClickListener { toggle(b, section) }
    }

    private fun toggle(b: ItemSettingsSectionBinding, section: SettingsSection) {
        val nowOpen = if (section.key in expanded) {
            expanded.remove(section.key); false
        } else {
            expanded.add(section.key); true
        }
        saveExpanded()
        b.sectionChevron.animate().rotation(if (nowOpen) 180f else 0f).setDuration(200).start()
        bindBody(b, section, nowOpen)
    }

    private fun bindBody(b: ItemSettingsSectionBinding, section: SettingsSection, open: Boolean) {
        if (!open) {
            b.sectionBody.isVisible = false
            b.sectionBodyDivider.isVisible = false
            return
        }
        val rows = section.rows()
        if (b.sectionBody.layoutManager == null) {
            b.sectionBody.layoutManager = LinearLayoutManager(b.root.context)
        }
        b.sectionBody.adapter = SettingsAdapter(ArrayList(rows))
        b.sectionBody.isVisible = rows.isNotEmpty()
        b.sectionBodyDivider.isVisible = rows.isNotEmpty()
    }

    companion object {
        private const val EXPANDED_PREF_PREFIX = "settings_expanded_"
        private const val RELAUNCH_PREF_PREFIX = "settings_relaunch_"
        private const val SEPARATOR = ","

        /** Section state keys, so a screen and its stored expansion set can't drift apart. */
        const val STATE_NOTIFICATIONS = "notifications"
        const val STATE_APPEARANCE = "appearance"
    }
}
