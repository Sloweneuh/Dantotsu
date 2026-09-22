package ani.dantotsu.media.user

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * The list screen's tabs.
 *
 * The tab set is not known in one go: the AniList lists arrive first, favourites a moment later as
 * their own query, and MangaUpdates later still. Each of those used to be answered by handing the
 * pager a brand new adapter, which destroys and recreates every fragment — so a list you had
 * already started scrolling was rebuilt from scratch twice, a second or two apart, for tabs whose
 * contents had not changed at all.
 *
 * [FragmentStateAdapter] supports a changing collection as long as items can be identified across
 * the change, which is what [getItemId] and [containsItem] are for: fragments whose id survives are
 * kept, and only genuinely new tabs get built. So the contents are held in `var`s and updated
 * through [update] rather than by replacing the adapter.
 */
class ListViewPagerAdapter(
    /** Original anilist map indices to display, in visual order (before + after MU tabs). */
    private var aniIndices: List<Int>,
    private val calendar: Boolean,
    fragment: FragmentActivity,
    /** Position of the dedicated MangaUpdates aggregate tab, or -1 if not present. */
    private var muTabPosition: Int = -1,
    /** Keys of "Separate" custom MU tabs inserted just before [muTabPosition]. */
    private var muCustomTabs: List<String> = emptyList(),
) :
    FragmentStateAdapter(fragment) {
    private val muSeparateTabsStart: Int
        get() = if (muTabPosition >= 0) muTabPosition - muCustomTabs.size else -1

    /**
     * Swaps in a new tab set, keeping every fragment whose tab is still present.
     *
     * ViewPager2 requires the coarse notification — it refuses the ranged ones — and with
     * [getItemId]/[containsItem] in place that is enough for the adapter to work out which
     * fragments to keep, which to drop and which to create.
     */
    @Suppress("NotifyDataSetChanged")
    fun update(aniIndices: List<Int>, muTabPosition: Int, muCustomTabs: List<String>) {
        if (this.aniIndices == aniIndices &&
            this.muTabPosition == muTabPosition &&
            this.muCustomTabs == muCustomTabs
        ) return
        this.aniIndices = aniIndices
        this.muTabPosition = muTabPosition
        this.muCustomTabs = muCustomTabs
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int =
        aniIndices.size + muCustomTabs.size + (if (muTabPosition >= 0) 1 else 0)

    /**
     * Identity of the tab at [position], stable across [update].
     *
     * An AniList tab is identified by its index into the view model's list map, which only ever
     * grows at the end (favourites are appended past "All"), never by its position in the pager —
     * that shifts as soon as MangaUpdates tabs are spliced in ahead of it. MangaUpdates tabs are
     * identified by their list key, offset into a range the small AniList indices cannot reach.
     */
    private fun idOf(position: Int): Long {
        if (muTabPosition >= 0) {
            if (position == muTabPosition) return MU_AGGREGATE_ID
            if (muCustomTabs.isNotEmpty() && position in muSeparateTabsStart until muTabPosition) {
                return MU_SEPARATE_BASE + muCustomTabs[position - muSeparateTabsStart].hashCode()
            }
            val aniIdx = if (position > muTabPosition) position - muCustomTabs.size - 1 else position
            return aniIndices[aniIdx].toLong()
        }
        return aniIndices[position].toLong()
    }

    override fun getItemId(position: Int): Long = idOf(position)

    override fun containsItem(itemId: Long): Boolean =
        (0 until itemCount).any { idOf(it) == itemId }

    override fun createFragment(position: Int): Fragment {
        if (muTabPosition >= 0) {
            if (position == muTabPosition) return MUOnlyListFragment.newInstance(null)
            if (muCustomTabs.isNotEmpty() && position in muSeparateTabsStart until muTabPosition) {
                return MUOnlyListFragment.newInstance(muCustomTabs[position - muSeparateTabsStart])
            }
            val aniIdx = if (position > muTabPosition) position - muCustomTabs.size - 1 else position
            return ListFragment.newInstance(aniIndices[aniIdx], calendar)
        }
        return ListFragment.newInstance(aniIndices[position], calendar)
    }

    private companion object {
        /** The one aggregate MangaUpdates tab. Negative, so no list index can collide with it. */
        const val MU_AGGREGATE_ID = -1L

        /** Keeps per-key MangaUpdates ids clear of the small AniList list indices. */
        const val MU_SEPARATE_BASE = 1L shl 32
    }
}
