package ani.dantotsu.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangabaka.MangaBakaStacks
import ani.dantotsu.databinding.ActivityMediaListViewBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.initActivity
import ani.dantotsu.px
import ani.dantotsu.settings.enableSettingsLongPress
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where one source's stacks come from, for a page that shows several sources' together. */
sealed interface StackFeed : MergedFeed {
    /** Every stack is known already. */
    class Loaded(val stacks: List<InterestStack>, val isAnime: Boolean = false) : StackFeed

    /** One series' stacks, which are paged in. */
    class MangaBaka(val seriesId: Long, val seriesTitle: String?, val coverUrl: String?) : StackFeed
}

/**
 * A full list of interest stacks. MAL stacks arrive ready-made through [passedStacks]. MangaBaka
 * stacks are paged in here, under a row of tabs: the stacks with one series (when opened from a
 * series page), every public stack, staff picks, and — logged in — the user's own, saved and
 * followed stacks, plus a button to start a new one. A custom tab's merged stacks ([mergedIntent])
 * can be both at once, and skip the tabs and filters, which only apply to MangaBaka's.
 */
class StackListViewActivity : AppCompatActivity(), StackSearchSheet.Host {
    private lateinit var binding: ActivityMediaListViewBinding

    /** What the adapters draw. MangaBaka pages are appended to it in place. */
    private val stacks = ArrayList<InterestStack>()
    private var isAnime = false
    private var mode = 0
    private var screenWidth = 0f

    // MangaBaka paging; unused for MAL stacks.
    private var isMangaBaka = false
    private var seriesId: Long? = null
    private var tab = Tab.ALL
    private var nextPage = 1
    private var hasMore = false
    private var loadJob: Job? = null
    /** The stack search's filters, for one series' stacks and the "All" tab; see [StackSearchSheet]. */
    private var search = MangaBakaStacks.StackSearch()

    /** What [search] goes back to on Reset: one series' stacks start by votes, all of them by trending. */
    private val searchDefaults: MangaBakaStacks.StackSearch
        get() = if (seriesId != null) MangaBakaStacks.StackSearch(sort = MangaBakaStacks.StackSort.VOTES)
        else MangaBakaStacks.StackSearch()


    private var merged = false

    /** Merged only: the ready-made stacks of sources listed after MangaBaka, which stay below its pages. */
    private var loadedAfter = 0

    private enum class Tab(val label: Int) {
        SERIES(R.string.stack_tab_series),
        ALL(R.string.stack_tab_all),
        FEATURED(R.string.stack_tab_featured),
        MINE(R.string.stack_tab_mine),
        SAVED(R.string.stack_tab_saved),
        FOLLOWED(R.string.stack_tab_followed),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme first — this screen shares its layout (and so its `?attr/` colours) with
        // [MediaListViewActivity]; see the note there.
        ThemeManager(this).applyTheme()
        binding = ActivityMediaListViewBinding.inflate(layoutInflater)
        initActivity(this)

        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            this.window.statusBarColor = ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true
        } else {
            binding.root.fitsSystemWindows = false
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }

        setContentView(binding.root)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = getThemeColor(androidx.appcompat.R.attr.colorPrimary)

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)

        screenWidth = resources.displayMetrics.run { widthPixels / density }
        isAnime = intent.getBooleanExtra("isAnime", false)
        isMangaBaka = intent.getBooleanExtra(EXTRA_MANGABAKA, false)

        mode = PrefManager.getCustomVal("mediaView", 0)
        binding.mediaList.alpha = if (mode == 1) 1f else 0.33f
        binding.mediaGrid.alpha = if (mode == 1) 0.33f else 1f
        binding.mediaList.setOnClickListener { changeView(1) }
        binding.mediaGrid.setOnClickListener { changeView(0) }

        binding.listBack.enableSettingsLongPress()
        binding.listBack.setOnClickListener { finish() }

        merged = intent.getBooleanExtra(EXTRA_MERGED, false)
        loadedAfter = intent.getIntExtra(EXTRA_LOADED_AFTER, 0)
        if (merged || !isMangaBaka) {
            stacks.addAll(passedStacks.orEmpty())
            updateTitle()
        }
        if (isMangaBaka) setUpMangaBaka()
        buildAdapter()
    }

    override fun onRestart() {
        super.onRestart()
        // A stack may have been made, edited or deleted, or saved/followed, while away.
        if (isMangaBaka && tab in setOf(Tab.MINE, Tab.SAVED, Tab.FOLLOWED)) reload()
    }

    private fun updateTitle() {
        val base = intent.getStringExtra("title")
            ?: getString(if (isMangaBaka && seriesId == null) R.string.mangabaka_stacks else R.string.interest_stacks)
        binding.listTitle.text = "$base (${stacks.size})"
    }

    private fun changeView(newMode: Int) {
        mode = newMode
        binding.mediaList.alpha = if (mode == 1) 1f else 0.33f
        binding.mediaGrid.alpha = if (mode == 1) 0.33f else 1f
        PrefManager.setCustomVal("mediaView", mode)
        buildAdapter()
    }

    private fun buildAdapter() {
        binding.mediaRecyclerView.adapter =
            if (mode == 1) StackListAdapter(stacks, isAnime) else StackAdapter(stacks, isAnime)
        val recycler = binding.mediaRecyclerView
        val density = resources.displayMetrics.density
        if (mode == 1) {
            recycler.setPaddingRelative((20 * density).toInt(), recycler.paddingTop, (20 * density).toInt(), recycler.paddingBottom)
            recycler.layoutManager = GridLayoutManager(this, listColumns(this))
        } else {
            // Cards keep their full width, so the back covers' tips clear the next card's: as many
            // columns as fit the screen, the rest left as padding either side to centre the grid.
            val columns = (screenWidth / STACK_CELL_DP).toInt().coerceAtLeast(1)
            val side = ((screenWidth - columns * STACK_CELL_DP) / 2 * density).toInt().coerceAtLeast(0)
            recycler.setPaddingRelative(side, recycler.paddingTop, side, recycler.paddingBottom)
            recycler.layoutManager = GridLayoutManager(this, columns)
        }
    }

    // ---- MangaBaka ----

    private fun setUpMangaBaka() {
        seriesId = intent.getLongExtra(EXTRA_SERIES_ID, -1L).takeIf { it > 0 }
        tab = if (seriesId != null) Tab.SERIES else Tab.ALL
        search = searchDefaults
        binding.mediaRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                if (hasMore && loadJob?.isActive != true &&
                    lm.findLastVisibleItemPosition() >= stacks.size - loadedAfter - 6
                ) loadPage()
            }
        })
        // Reloading for a filter would take the other sources' stacks with it.
        if (merged) {
            hasMore = true
            loadPage()
            return
        }
        lifecycleScope.launch {
            TrackerSessions.await()
            val loggedIn = MangaBaka.token != null
            if (seriesId != null) buildSeriesFilters() else buildTabs(loggedIn)
            if (loggedIn) {
                binding.listStackActions.setImageResource(R.drawable.ic_round_playlist_add_24)
                binding.listStackActions.contentDescription = getString(R.string.stack_new)
                binding.listStackActions.visibility = View.VISIBLE
                binding.listStackActions.setOnClickListener {
                    // Opened from a series: the new stack starts with that series in it.
                    startActivity(
                        MangaBakaStackEditActivity.createIntent(
                            this@StackListViewActivity, seriesId,
                            intent.getStringExtra(EXTRA_SERIES_TITLE), intent.getStringExtra(EXTRA_SERIES_COVER),
                        )
                    )
                }
            }
        }
        reload()
    }

    /**
     * One series' stacks: the filters are behind the top bar's filter button as on every other list
     * screen, the chips only those applied. The global tabs live behind the search screen's
     * "MangaBaka Stacks" tile.
     */
    private fun buildSeriesFilters() {
        binding.mediaFilterChipGroup.removeAllViews()
        updateFilterButton()
        updateSearchChips()
    }

    /**
     * The applied filters as chips, after the tabs if any: tapping one takes it away, as the stack
     * screen's own filter chips do. Each is looked up again when tapped, an earlier tap having
     * already changed the search.
     */
    private fun updateSearchChips() {
        val group = binding.mediaFilterChipGroup
        (0 until group.childCount).map { group.getChildAt(it) }.filter { it.tag == SEARCH_CHIP_TAG }
            .forEach { group.removeView(it) }
        val chips = if (tab == Tab.SERIES || tab == Tab.ALL) {
            StackSearchSheet.activeChips(this, search, searchDefaults)
        } else emptyList()
        chips.forEach { (label, _) ->
            group.addView(Chip(this).apply {
                tag = SEARCH_CHIP_TAG
                text = label
                isCheckable = false
                isCloseIconVisible = true
                chipBackgroundColor = ContextCompat.getColorStateList(context, R.color.chip_background_color)
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
                )
                setTextAppearance(R.style.Suffix)
                textSize = 14f
                val remove = {
                    StackSearchSheet.activeChips(this@StackListViewActivity, search, searchDefaults)
                        .firstOrNull { it.first == label }?.let { onStackSearch(it.second) }
                    Unit
                }
                setOnClickListener { remove() }
                setOnCloseIconClickListener { remove() }
            })
        }
        binding.mediaFilterChipsScroll.visibility =
            if (group.childCount > 0) View.VISIBLE else View.GONE
    }

    override fun openStackSearchManageFilters() {
        val items = StackSearchSheet.activeChips(this, search, searchDefaults).map { (label, without) ->
            ActiveFilterChip(label) { onStackSearch(without) }
        }
        ManageFiltersDialog.show(this, items) { onStackSearch(searchDefaults) }
    }

    /** The top bar's filter button, as MediaListViewActivity's: it opens [StackSearchSheet]. */
    private fun updateFilterButton() {
        binding.listFilter.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(StackSearchSheet.TAG) == null) {
                StackSearchSheet.newInstance(search, searchDefaults)
                    .show(supportFragmentManager, StackSearchSheet.TAG)
            }
        }
        // Only the search tabs have filters.
        binding.listFilter.visibility = if (tab == Tab.SERIES || tab == Tab.ALL) View.VISIBLE else View.GONE
    }

    override fun onStackSearch(search: MangaBakaStacks.StackSearch) {
        if (search == this.search) return
        this.search = search
        updateSearchChips()
        reload()
    }

    private fun buildTabs(loggedIn: Boolean) {
        val tabs = Tab.entries.filter {
            when (it) {
                Tab.SERIES -> false
                Tab.MINE, Tab.SAVED, Tab.FOLLOWED -> loggedIn
                else -> true
            }
        }
        val group = binding.mediaFilterChipGroup
        group.removeAllViews()
        group.isSingleSelection = true
        group.isSelectionRequired = true
        tabs.forEach { t ->
            group.addView(Chip(this).apply {
                setText(t.label)
                isCheckable = true
                isChecked = t == tab
                setOnClickListener {
                    if (tab != t) {
                        tab = t
                        updateFilterButton()
                        updateSearchChips()
                        reload()
                    }
                    isChecked = true
                }
            })
        }
        updateFilterButton()
        updateSearchChips()
    }

    private fun reload() {
        loadJob?.cancel()
        val size = stacks.size
        stacks.clear()
        binding.mediaRecyclerView.adapter?.notifyItemRangeRemoved(0, size)
        nextPage = 1
        hasMore = true
        binding.mediaListEmpty.visibility = View.GONE
        loadPage()
    }

    private fun loadPage() {
        val page = nextPage
        binding.mediaListProgress.visibility = if (stacks.isEmpty()) View.VISIBLE else View.GONE
        loadJob = lifecycleScope.launch {
            val requested = tab
            val result = when (requested) {
                Tab.SERIES -> MangaBakaStacks.search(search, seriesId ?: return@launch, page)
                Tab.ALL -> MangaBakaStacks.search(search, page = page)
                Tab.FEATURED -> MangaBakaStacks.browse(page, featured = true, limit = MangaBakaStacks.PAGE_LIMIT)
                Tab.MINE -> MangaBakaStacks.getMyStacks(page)
                Tab.SAVED -> MangaBakaStacks.getSavedStacks(page)
                Tab.FOLLOWED -> MangaBakaStacks.getFollowedStacks(page)
            }
            if (requested != tab) return@launch
            binding.mediaListProgress.visibility = View.GONE
            val rows = result?.data.orEmpty()
            hasMore = result?.pagination?.next != null && rows.isNotEmpty()
            nextPage = page + 1
            val start = stacks.size - loadedAfter
            stacks.addAll(start, rows)
            binding.mediaRecyclerView.adapter?.notifyItemRangeInserted(start, rows.size)
            updateTitle()
            if (stacks.isEmpty()) {
                binding.mediaListEmpty.text = getString(
                    when {
                        result == null && requested in setOf(Tab.MINE, Tab.SAVED, Tab.FOLLOWED) -> R.string.stack_relogin
                        result == null -> R.string.stack_action_failed
                        else -> R.string.stack_list_empty
                    }
                )
                binding.mediaListEmpty.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Passed via a static field (large lists would blow the Bundle size limit as
        // extras) so it must survive a rotation-triggered recreation; only clear it
        // once this activity is actually going away for good.
        if (!isChangingConfigurations) {
            passedStacks = null
        }
    }

    companion object {
        /**
         * A compact stack card's width, dp: its 108dp cover box and name, plus the 14dp either side
         * its padding leaves past its negative margins — room for the back covers' tips, which lean
         * out past the box. Columns are as many of these as fit.
         */
        private const val STACK_CELL_DP = 136f

        /** Marks the applied-filter chips in the chip row, apart from the tabs. */
        private const val SEARCH_CHIP_TAG = "stackSearchChip"

        var passedStacks: ArrayList<out InterestStack>? = null

        private const val EXTRA_MANGABAKA = "mangabaka"
        private const val EXTRA_SERIES_ID = "seriesId"
        private const val EXTRA_SERIES_TITLE = "seriesTitle"
        private const val EXTRA_SERIES_COVER = "seriesCover"
        private const val EXTRA_MERGED = "merged"
        private const val EXTRA_LOADED_AFTER = "loadedAfter"

        /** The stacks of several sources in one list, in the order of [feeds]. */
        fun mergedIntent(context: Context, title: String, feeds: List<StackFeed>): Intent {
            val loaded = feeds.filterIsInstance<StackFeed.Loaded>()
            passedStacks = ArrayList(loaded.flatMap { it.stacks })
            val intent = Intent(context, StackListViewActivity::class.java)
                .putExtra("title", title)
                .putExtra("isAnime", loaded.any { it.isAnime })
                .putExtra(EXTRA_MERGED, true)
            val paged = feeds.indexOfFirst { it is StackFeed.MangaBaka }
            (feeds.getOrNull(paged) as? StackFeed.MangaBaka)?.let { feed ->
                intent.putExtra(EXTRA_MANGABAKA, true)
                    .putExtra(EXTRA_SERIES_ID, feed.seriesId)
                    .putExtra(EXTRA_SERIES_TITLE, feed.seriesTitle)
                    .putExtra(EXTRA_SERIES_COVER, feed.coverUrl)
                    .putExtra(
                        EXTRA_LOADED_AFTER,
                        feeds.drop(paged + 1).filterIsInstance<StackFeed.Loaded>().sumOf { it.stacks.size },
                    )
            }
            return intent
        }

        /**
         * The MangaBaka stack browser. Without [seriesId] it's the global one (every stack, staff
         * picks, and your own, saved and followed stacks); with it, just the stacks that include
         * that series, and a stack created from there starts with the series ([seriesTitle] and
         * [seriesCover] draw its row in the editor).
         */
        fun mangaBakaIntent(
            context: Context,
            seriesId: Long? = null,
            seriesTitle: String? = null,
            seriesCover: String? = null,
        ): Intent =
            Intent(context, StackListViewActivity::class.java)
                .putExtra(EXTRA_MANGABAKA, true)
                .apply {
                    if (seriesId != null) {
                        putExtra(EXTRA_SERIES_ID, seriesId)
                        putExtra(EXTRA_SERIES_TITLE, seriesTitle)
                        putExtra(EXTRA_SERIES_COVER, seriesCover)
                    }
                }
    }
}
