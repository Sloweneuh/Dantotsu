package ani.dantotsu.media

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import ani.dantotsu.R
import ani.dantotsu.bindScrollToTop
import ani.dantotsu.databinding.ItemChipBinding
import com.google.android.material.chip.ChipGroup
import nl.joery.animatedbottombar.AnimatedBottomBar

/**
 * The Info / Episodes bottom-bar + chip-paginated episode list shared by [KitsuMediaActivity] and
 * [SimklMediaActivity]. Both standalone pages had an identical copy of this.
 *
 * The caller resolves the views from its own binding (the two layouts use different ids) and feeds
 * episode rows once loaded.
 */
class TrackerEpisodesController(
    private val activity: AppCompatActivity,
    private val bottomBar: AnimatedBottomBar,
    private val infoScroll: View,
    private val episodesScroll: NestedScrollView,
    private val episodesContent: ViewGroup,
    private val episodesEmpty: View,
    private val chipScroll: View,
    private val chipGroup: ChipGroup,
    private val scrollTopButton: View,
    private val pagesWidthSource: View,
    startOnEpisodes: Boolean,
) {
    var currentTabIndex = if (startOnEpisodes) 1 else 0
        private set

    /**
     * Falls in for a row with no thumbnail of its own — same as the trackers' own episode lists.
     * Set once the media header (fetched alongside, but separately from, the episode list) loads.
     */
    var coverUrl: String? = null

    private var rows: List<TrackerEpisodeRenderer.EpisodeRow> = emptyList()

    fun setup() {
        bottomBar.addTab(bottomBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info))
        bottomBar.addTab(bottomBar.createTab(R.drawable.ic_round_playlist_play_24, R.string.eps, R.id.watch))
        bottomBar.selectTabAt(currentTabIndex)
        infoScroll.visibility = View.GONE
        episodesScroll.visibility = View.GONE
        scrollTopButton.bindScrollToTop(episodesScroll)
        bottomBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int, lastTab: AnimatedBottomBar.Tab?, newIndex: Int, newTab: AnimatedBottomBar.Tab,
            ) {
                if (newIndex == currentTabIndex) return
                slideToTab(currentTabIndex, newIndex)
                currentTabIndex = newIndex
            }
        })
    }

    /** Reveal whichever tab is current — called once content has loaded. */
    fun revealCurrentTab() {
        (if (currentTabIndex == 0) infoScroll else episodesScroll).visibility = View.VISIBLE
    }

    fun setEpisodes(episodes: List<TrackerEpisodeRenderer.EpisodeRow>) {
        rows = episodes
        episodesContent.removeAllViews()
        chipGroup.removeAllViews()

        if (rows.isEmpty()) {
            chipScroll.visibility = View.GONE
            episodesEmpty.visibility = View.VISIBLE
            return
        }
        episodesEmpty.visibility = View.GONE

        val total = rows.size
        val limit = pageSize(total)
        if (total <= limit) {
            chipScroll.visibility = View.GONE
            showRange(0, total - 1)
            return
        }

        chipScroll.visibility = View.VISIBLE
        val groupCount = (total + limit - 1) / limit
        for (groupIdx in 0 until groupCount) {
            val startIdx = groupIdx * limit
            val endIdx = minOf(startIdx + limit - 1, total - 1)
            val startNum = rows[startIdx].number
            val endNum = rows[endIdx].number
            val chip = ItemChipBinding.inflate(activity.layoutInflater, chipGroup, false).root
            chip.isCheckable = true
            chip.text = activity.getString(R.string.episode_range_format, startNum, endNum)
            chip.setTextColor(ContextCompat.getColorStateList(activity, R.color.chip_text_color))
            chip.setOnClickListener {
                chip.isChecked = true
                showRange(startIdx, endIdx)
            }
            chipGroup.addView(chip)
            if (groupIdx == 0) chip.isChecked = true
        }
        showRange(0, minOf(limit - 1, total - 1))
    }

    private fun showRange(startIdx: Int, endIdx: Int) {
        episodesContent.removeAllViews()
        TrackerEpisodeRenderer.renderList(activity, episodesContent, rows.subList(startIdx, endIdx + 1), coverUrl)
        episodesScroll.smoothScrollTo(0, 0)
    }

    private fun pageSize(total: Int): Int {
        val d = total / 10.0
        return when { d < 25 -> 25; d < 50 -> 50; else -> 100 }
    }

    private fun slideToTab(from: Int, to: Int) {
        val outView = if (from == 0) infoScroll else episodesScroll
        val inView = if (to == 0) infoScroll else episodesScroll
        if (!outView.isVisible) return
        val width = pagesWidthSource.width.toFloat().takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels.toFloat()
        val goRight = to > from
        inView.translationX = if (goRight) width else -width
        inView.visibility = View.VISIBLE
        outView.animate().translationX(if (goRight) -width else width).setDuration(280)
            .withEndAction { outView.visibility = View.GONE; outView.translationX = 0f }.start()
        inView.animate().translationX(0f).setDuration(280).start()
        if (to != 1) scrollTopButton.visibility = View.GONE
    }
}
