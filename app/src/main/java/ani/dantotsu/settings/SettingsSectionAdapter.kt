package ani.dantotsu.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemSettingsSectionBinding

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
) : RecyclerView.Adapter<SettingsSectionAdapter.Holder>() {

    private val expanded = mutableSetOf<String>()

    inner class Holder(val b: ItemSettingsSectionBinding) : RecyclerView.ViewHolder(b.root)

    /** Where [key]'s card sits, or -1 if there is no such section. */
    fun positionOf(key: String): Int = sections.indexOfFirst { it.key == key }

    /** Expands [key]'s card, a no-op if it is already open — used to land a search result on a
     *  setting that lives inside a collapsed group. */
    fun expand(key: String) {
        if (expanded.add(key)) {
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
}
