package ani.dantotsu.media.user

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ListViewPagerAdapter(
    /** Original anilist map indices to display, in visual order (before + after MU tabs). */
    private val aniIndices: List<Int>,
    private val calendar: Boolean,
    fragment: FragmentActivity,
    /** Position of the dedicated MangaUpdates aggregate tab, or -1 if not present. */
    private val muTabPosition: Int = -1,
    /** Keys of "Separate" custom MU tabs inserted just before [muTabPosition]. */
    private val muCustomTabs: List<String> = emptyList(),
) :
    FragmentStateAdapter(fragment) {
    private val muSeparateTabsStart: Int =
        if (muTabPosition >= 0) muTabPosition - muCustomTabs.size else -1

    override fun getItemCount(): Int =
        aniIndices.size + muCustomTabs.size + (if (muTabPosition >= 0) 1 else 0)

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
}