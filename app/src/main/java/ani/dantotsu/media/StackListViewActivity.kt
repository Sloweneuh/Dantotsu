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

/**
 * A full list of interest stacks. MAL stacks arrive ready-made through [passedStacks]. MangaBaka
 * stacks are paged in here, under a row of tabs: the stacks with one series (when opened from a
 * series page), every public stack, staff picks, and — logged in — the user's own, saved and
 * followed stacks, plus a button to start a new one.
 */
class StackListViewActivity : AppCompatActivity() {
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
    private var sort = MangaBakaStacks.SeriesSort.VOTES
    private var spoilers = MangaBakaStacks.Spoilers.INCLUDE

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

        if (isMangaBaka) {
            setUpMangaBaka()
        } else {
            stacks.addAll(passedStacks.orEmpty())
            updateTitle()
        }
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
        binding.mediaRecyclerView.layoutManager =
            GridLayoutManager(this, if (mode == 1) 1 else (screenWidth / 120f).toInt())
    }

    // ---- MangaBaka ----

    private fun setUpMangaBaka() {
        seriesId = intent.getLongExtra(EXTRA_SERIES_ID, -1L).takeIf { it > 0 }
        tab = if (seriesId != null) Tab.SERIES else Tab.ALL
        binding.mediaRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                if (hasMore && loadJob?.isActive != true &&
                    lm.findLastVisibleItemPosition() >= stacks.size - 6
                ) loadPage()
            }
        })
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
     * One series' stacks get the series route's own filters — sort order and spoiler handling — in
     * place of the global tabs, which live behind the search screen's "MangaBaka Stacks" tile.
     */
    private fun buildSeriesFilters() {
        val group = binding.mediaFilterChipGroup
        group.removeAllViews()
        group.isSingleSelection = false
        group.isSelectionRequired = false
        val sortChips = ArrayList<Chip>()
        val spoilerChips = ArrayList<Chip>()
        fun refresh() {
            sortChips.forEach { it.isChecked = it.tag == sort }
            spoilerChips.forEach { it.isChecked = it.tag == spoilers }
        }
        MangaBakaStacks.SeriesSort.entries.forEach { option ->
            sortChips += Chip(this).apply {
                setText(sortLabel(option))
                tag = option
                isCheckable = true
                setOnClickListener {
                    if (sort != option) {
                        sort = option
                        reload()
                    }
                    refresh()
                }
            }
        }
        MangaBakaStacks.Spoilers.entries.forEach { option ->
            spoilerChips += Chip(this).apply {
                setText(spoilerLabel(option))
                tag = option
                isCheckable = true
                setOnClickListener {
                    if (spoilers != option) {
                        spoilers = option
                        reload()
                    }
                    refresh()
                }
            }
        }
        sortChips.forEach { group.addView(it) }
        // A thin rule between the two filters, so they read as two choices rather than one.
        group.addView(View(this).apply {
            layoutParams = ViewGroup.LayoutParams(1f.px, 24f.px)
            setBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorOutline))
        })
        spoilerChips.forEach { group.addView(it) }
        refresh()
        binding.mediaFilterChipsScroll.visibility = View.VISIBLE
    }

    private fun sortLabel(sort: MangaBakaStacks.SeriesSort) = when (sort) {
        MangaBakaStacks.SeriesSort.VOTES -> R.string.stack_sort_votes
        MangaBakaStacks.SeriesSort.SAVES -> R.string.stack_sort_saves
        MangaBakaStacks.SeriesSort.UPDATED -> R.string.stack_sort_updated
        MangaBakaStacks.SeriesSort.NEWEST -> R.string.stack_sort_newest
    }

    private fun spoilerLabel(spoilers: MangaBakaStacks.Spoilers) = when (spoilers) {
        MangaBakaStacks.Spoilers.INCLUDE -> R.string.stack_spoilers_include
        MangaBakaStacks.Spoilers.EXCLUDE -> R.string.stack_spoilers_exclude
        MangaBakaStacks.Spoilers.ONLY -> R.string.stack_spoilers_only
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
                        reload()
                    }
                    isChecked = true
                }
            })
        }
        binding.mediaFilterChipsScroll.visibility = View.VISIBLE
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
                Tab.SERIES -> MangaBakaStacks.getSeriesStacks(
                    seriesId ?: return@launch, page, MangaBakaStacks.PAGE_LIMIT, sort, spoilers,
                )
                Tab.ALL -> MangaBakaStacks.browse(page, limit = MangaBakaStacks.PAGE_LIMIT)
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
            val start = stacks.size
            stacks.addAll(rows)
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
        var passedStacks: ArrayList<out InterestStack>? = null

        private const val EXTRA_MANGABAKA = "mangabaka"
        private const val EXTRA_SERIES_ID = "seriesId"
        private const val EXTRA_SERIES_TITLE = "seriesTitle"
        private const val EXTRA_SERIES_COVER = "seriesCover"

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
