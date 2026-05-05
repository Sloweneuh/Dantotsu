package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityExtensionBrowseBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.LanguageMapper.Companion.getLanguageName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.awaitSingle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import ani.dantotsu.settings.SettingsExtensionsActivity

class ExtensionBrowseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TYPE = "type"
        const val TYPE_ANIME = "anime"
        const val TYPE_MANGA = "manga"
    }

    private lateinit var binding: ActivityExtensionBrowseBinding
    private lateinit var type: String
    private var animeExtension: AnimeExtension.Installed? = null
    private var mangaExtension: MangaExtension.Installed? = null
    private var sourceIndex = 0

    private val adapter = BrowseMediaAdapter(::openInfo, ::openInBrowser)
    private val activeFilterAdapter = ActiveFilterChipAdapter()
    private var currentMode: Mode = Mode.POPULAR
    private var currentPage = 1
    private var endReached = false
    private var loadJob: Job? = null
    private var currentFilters: Any? = null // FilterList or AnimeFilterList
    private var defaultFilters: Any? = null // Snapshot of source defaults; FilterList or AnimeFilterList
    private var currentQuery: String = ""

    private enum class Mode { POPULAR, LATEST, FILTER, SEARCH }

    private data class ActiveFilterChip(val label: String, val onRemove: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityExtensionBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.extensionBrowseToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.extensionBrowseRecycler.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.fragmentExtensionsContainer.setPadding(
            binding.fragmentExtensionsContainer.paddingLeft,
            statusBarHeight,
            binding.fragmentExtensionsContainer.paddingRight,
            binding.fragmentExtensionsContainer.paddingBottom,
        )

        type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_MANGA
        val pkg = intent.getStringExtra(EXTRA_PKG)
        if (pkg == null) {
            finish()
            return
        }

        if (type == TYPE_ANIME) {
            val mgr: AnimeExtensionManager = Injekt.get()
            animeExtension = mgr.installedExtensionsFlow.value.find { it.pkgName == pkg }
        } else {
            val mgr: MangaExtensionManager = Injekt.get()
            mangaExtension = mgr.installedExtensionsFlow.value.find { it.pkgName == pkg }
        }

        val name = animeExtension?.name ?: mangaExtension?.name
        val icon = animeExtension?.icon ?: mangaExtension?.icon
        if (name == null) {
            finish()
            return
        }
        if (icon != null) binding.extensionBrowseIcon.setImageDrawable(icon)
        // show extension name in header; keep generic search hint
        binding.extensionBrowseTitle.text = name
        binding.extensionBrowseSearch.queryHint = getString(R.string.search)
        // initial header state: show title + icon, show search icon, hide search bar
        binding.extensionBrowseSearch.isVisible = false
        binding.extensionBrowseSearchIcon.isVisible = true
        binding.extensionBrowseTitle.isVisible = true
        binding.extensionBrowseIcon.isVisible = true

        binding.extensionBrowseSettings.setOnClickListener {
            val configurableSources = when {
                animeExtension != null -> animeExtension!!.sources.filterIsInstance<eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource>()
                mangaExtension != null -> mangaExtension!!.sources.filterIsInstance<eu.kanade.tachiyomi.source.ConfigurableSource>()
                else -> emptyList()
            }
            ExtensionSettingsOpener.openConfigurableSourcePreferences(this, configurableSources, null)
        }

        binding.extensionBrowseSearchIcon.setOnClickListener {
            binding.extensionBrowseSearch.isVisible = true
            binding.extensionBrowseSearchIcon.isVisible = false
            binding.extensionBrowseTitle.isVisible = false
            binding.extensionBrowseIcon.isVisible = false
            binding.extensionBrowseSearch.isIconified = false
            binding.extensionBrowseSearch.requestFocus()
        }

        binding.extensionBrowseBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val spanCount = resources.configuration.screenWidthDp / 120
        binding.extensionBrowseRecycler.layoutManager =
            GridLayoutManager(this, spanCount.coerceAtLeast(2))
        binding.extensionBrowseRecycler.adapter = adapter

        binding.extensionBrowseActiveFilterChips.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.extensionBrowseActiveFilterChips.adapter = activeFilterAdapter
        adapter.setImageHeaders(currentSourceHeaders())
        binding.extensionBrowseRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as GridLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                if (!endReached && loadJob?.isActive != true && lastVisible >= adapter.itemCount - 6) {
                    loadPage(currentPage + 1)
                }
            }
        })

        configureLanguageSwitch()
        configureChips()
        configureSearch()

        load(Mode.POPULAR, null)
    }

    private fun supportsLatest(): Boolean {
        return when {
            animeExtension != null ->
                (animeExtension!!.sources.getOrNull(sourceIndex) as? AnimeCatalogueSource)?.supportsLatest == true
            mangaExtension != null ->
                (mangaExtension!!.sources.getOrNull(sourceIndex) as? CatalogueSource)?.supportsLatest == true
            else -> false
        }
    }

    private fun hasFilters(): Boolean {
        return when {
            animeExtension != null -> {
                val fl = (animeExtension!!.sources.getOrNull(sourceIndex) as? AnimeCatalogueSource)
                    ?.getFilterList()
                hasRenderableFilters(fl)
            }
            mangaExtension != null -> {
                val fl = (mangaExtension!!.sources.getOrNull(sourceIndex) as? CatalogueSource)
                    ?.getFilterList()
                hasRenderableFilters(fl)
            }
            else -> false
        }
    }

    // Return true only if the provided filter list actually contains at least one
    // renderable filter (select/text/checkbox/tristate/sort or group with children).
    private fun hasRenderableFilters(filters: Any?): Boolean {
        if (filters == null) return false
        when (filters) {
            is FilterList -> {
                fun check(list: List<eu.kanade.tachiyomi.source.model.Filter<*>>): Boolean {
                    list.forEach { f ->
                        when (f) {
                            is eu.kanade.tachiyomi.source.model.Filter.Select<*>,
                            is eu.kanade.tachiyomi.source.model.Filter.Text,
                            is eu.kanade.tachiyomi.source.model.Filter.CheckBox,
                            is eu.kanade.tachiyomi.source.model.Filter.TriState,
                            is eu.kanade.tachiyomi.source.model.Filter.Sort -> return true
                            is eu.kanade.tachiyomi.source.model.Filter.Group<*> -> {
                                val children = (f.state as? List<*>)?.filterIsInstance<eu.kanade.tachiyomi.source.model.Filter<*>>()
                                if (!children.isNullOrEmpty() && check(children)) return true
                            }
                            else -> Unit
                        }
                    }
                    return false
                }
                return check(filters.list)
            }
            is AnimeFilterList -> {
                fun check(list: List<eu.kanade.tachiyomi.animesource.model.AnimeFilter<*>>): Boolean {
                    list.forEach { f ->
                        when (f) {
                            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Select<*>,
                            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Text,
                            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.CheckBox,
                            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState,
                            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Sort -> return true
                            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Group<*> -> {
                                val children = (f.state as? List<*>)?.filterIsInstance<eu.kanade.tachiyomi.animesource.model.AnimeFilter<*>>()
                                if (!children.isNullOrEmpty() && check(children)) return true
                            }
                            else -> Unit
                        }
                    }
                    return false
                }
                return check(filters.list)
            }
            else -> return false
        }
    }

    private fun configureChips() {
        binding.chipLatest.isVisible = supportsLatest()
        binding.chipFilter.isVisible = hasFilters()

        binding.chipPopular.setOnClickListener {
            if (currentMode == Mode.POPULAR) binding.chipPopular.isChecked = true
            else load(Mode.POPULAR, null)
        }
        binding.chipLatest.setOnClickListener {
            if (currentMode == Mode.LATEST) binding.chipLatest.isChecked = true
            else load(Mode.LATEST, null)
        }
        binding.chipFilter.setOnClickListener {
            // Keep the currently-loaded mode's chip checked while the sheet is open.
            when (currentMode) {
                Mode.POPULAR -> binding.chipPopular.isChecked = true
                Mode.LATEST -> binding.chipLatest.isChecked = true
                Mode.FILTER -> binding.chipFilter.isChecked = true
                Mode.SEARCH -> {
                    // When in search mode, leave chips as-is (no chip corresponds to search).
                }
            }
            openFilterSheet()
        }
    }

    private fun configureSearch() {
        binding.extensionBrowseSearch.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val q = query?.trim() ?: ""
                if (q.isEmpty()) {
                    // empty -> go back to popular
                    binding.chipPopular.isChecked = true
                    binding.chipLatest.isChecked = false
                    binding.chipFilter.isChecked = false
                    load(Mode.POPULAR, null)
                } else {
                    currentQuery = q
                    binding.chipPopular.isChecked = false
                    binding.chipLatest.isChecked = false
                    binding.chipFilter.isChecked = false
                    load(Mode.SEARCH, currentFilters)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Do nothing for incremental changes to avoid excess requests.
                return false
            }
        })

        // When search is cleared via the close button, restore popular and header UI.
        binding.extensionBrowseSearch.setOnCloseListener {
            // restore header
            binding.extensionBrowseSearch.isVisible = false
            binding.extensionBrowseSearchIcon.isVisible = true
            binding.extensionBrowseTitle.isVisible = true
            binding.extensionBrowseIcon.isVisible = true

            binding.chipPopular.isChecked = true
            binding.chipLatest.isChecked = false
            binding.chipFilter.isChecked = false
            currentQuery = ""
            load(Mode.POPULAR, null)
            false
        }
    }

    private fun configureLanguageSwitch() {
        val names: Array<String> = when {
            animeExtension != null -> animeExtension!!.sources.map { getLanguageName(it.lang) }.toTypedArray()
            mangaExtension != null -> mangaExtension!!.sources.map { getLanguageName(it.lang) }.toTypedArray()
            else -> emptyArray()
        }
        if (names.size <= 1) {
            binding.extensionBrowseLanguage.isVisible = false
            return
        }
        binding.extensionBrowseLanguage.isVisible = true
        binding.extensionBrowseLanguage.setOnClickListener {
            customAlertDialog().apply {
                setTitle(getString(R.string.language))
                singleChoiceItems(names, sourceIndex) { which ->
                    if (which != sourceIndex) {
                        sourceIndex = which
                        defaultFilters = null
                        adapter.setImageHeaders(currentSourceHeaders())
                        configureChips()
                        binding.chipPopular.isChecked = true
                        load(Mode.POPULAR, null)
                    }
                }
                show()
            }
        }
    }

    private fun currentSourceHeaders(): Map<String, String> {
        val headers = when {
            animeExtension != null -> (animeExtension!!.sources.getOrNull(sourceIndex)
                as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource)?.headers
            mangaExtension != null -> (mangaExtension!!.sources.getOrNull(sourceIndex)
                as? eu.kanade.tachiyomi.source.online.HttpSource)?.headers
            else -> null
        } ?: return emptyMap()
        val map = LinkedHashMap<String, String>(headers.size)
        headers.names().forEach { name -> headers[name]?.let { map[name] = it } }
        return map
    }

    private fun openFilterSheet() {
        val filters: Any = when {
            animeExtension != null -> (currentFilters as? AnimeFilterList)
                ?: (animeExtension!!.sources.getOrNull(sourceIndex) as? AnimeCatalogueSource)
                    ?.getFilterList() ?: return
            mangaExtension != null -> (currentFilters as? FilterList)
                ?: (mangaExtension!!.sources.getOrNull(sourceIndex) as? CatalogueSource)
                    ?.getFilterList() ?: return
            else -> return
        }
        val sheet = ExtensionFilterBottomSheet.newInstance(filters) { applied ->
            currentFilters = applied
            binding.chipFilter.isChecked = true
            binding.chipPopular.isChecked = false
            binding.chipLatest.isChecked = false
            load(Mode.FILTER, applied)
            refreshActiveFilterChips()
        }
        sheet.show(supportFragmentManager, "extension_filter")
    }

    private fun load(mode: Mode, filters: Any?) {
        currentMode = mode
        currentFilters = filters
        currentPage = 1
        endReached = false
        adapter.submit(emptyList())
        binding.extensionBrowseEmptyContainer.isVisible = false
        binding.chipPopular.isChecked = mode == Mode.POPULAR
        binding.chipLatest.isChecked = mode == Mode.LATEST
        binding.chipFilter.isChecked = mode == Mode.FILTER
        refreshActiveFilterChips()
        loadPage(1)
    }

    private fun refreshActiveFilterChips() {
        val chips = buildActiveFilterChips()
        activeFilterAdapter.submit(chips)
        binding.extensionBrowseActiveFilterChips.isVisible = chips.isNotEmpty()
    }

    private fun ensureDefaultFilters() {
        if (defaultFilters != null) return
        defaultFilters = when {
            animeExtension != null ->
                (animeExtension!!.sources.getOrNull(sourceIndex) as? AnimeCatalogueSource)
                    ?.getFilterList()
            mangaExtension != null ->
                (mangaExtension!!.sources.getOrNull(sourceIndex) as? CatalogueSource)
                    ?.getFilterList()
            else -> null
        }
    }

    private fun buildActiveFilterChips(): List<ActiveFilterChip> {
        val out = mutableListOf<ActiveFilterChip>()
        when (val current = currentFilters) {
            is FilterList -> {
                ensureDefaultFilters()
                val defaults = (defaultFilters as? FilterList)?.list
                current.list.forEachIndexed { i, f ->
                    collectMangaChips(f, defaults?.getOrNull(i), out)
                }
            }
            is AnimeFilterList -> {
                ensureDefaultFilters()
                val defaults = (defaultFilters as? AnimeFilterList)?.list
                current.list.forEachIndexed { i, f ->
                    collectAnimeChips(f, defaults?.getOrNull(i), out)
                }
            }
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectMangaChips(
        f: Filter<*>,
        default: Filter<*>?,
        out: MutableList<ActiveFilterChip>,
    ) {
        when (f) {
            is Filter.Select<*> -> {
                val state = (f as Filter<Int>).state
                val defaultState = (default as? Filter<Int>)?.state ?: 0
                if (state != defaultState) {
                    val value = f.values.getOrNull(state)?.toString() ?: return
                    out.add(ActiveFilterChip("${f.name}: $value") {
                        (f as Filter<Int>).state = defaultState
                    })
                }
            }
            is Filter.Text -> {
                val state = (f as Filter<String>).state
                val defaultState = (default as? Filter<String>)?.state ?: ""
                if (state != defaultState) {
                    out.add(ActiveFilterChip("${f.name}: $state") {
                        (f as Filter<String>).state = defaultState
                    })
                }
            }
            is Filter.CheckBox -> {
                val state = (f as Filter<Boolean>).state
                val defaultState = (default as? Filter<Boolean>)?.state ?: false
                if (state != defaultState) {
                    out.add(ActiveFilterChip(f.name) {
                        (f as Filter<Boolean>).state = defaultState
                    })
                }
            }
            is Filter.TriState -> {
                val state = (f as Filter<Int>).state
                val defaultState = (default as? Filter<Int>)?.state ?: Filter.TriState.STATE_IGNORE
                if (state == defaultState) return
                val label = when (state) {
                    Filter.TriState.STATE_INCLUDE -> f.name
                    Filter.TriState.STATE_EXCLUDE -> getString(R.string.filter_exclude, f.name)
                    else -> return
                }
                out.add(ActiveFilterChip(label) {
                    (f as Filter<Int>).state = defaultState
                })
            }
            is Filter.Sort -> {
                val state = (f as Filter<Filter.Sort.Selection?>).state
                val defaultState = (default as? Filter<Filter.Sort.Selection?>)?.state
                if (sortSelectionEquals(state, defaultState)) return
                if (state == null) return
                val name = f.values.getOrNull(state.index) ?: return
                val arrow = if (state.ascending) "↑" else "↓"
                out.add(ActiveFilterChip("${f.name}: $name $arrow") {
                    (f as Filter<Filter.Sort.Selection?>).state = defaultState
                })
            }
            is Filter.Group<*> -> {
                val children = (f.state as? List<*>) ?: return
                val defaultChildren = (default as? Filter.Group<*>)?.state as? List<*>
                children.forEachIndexed { i, child ->
                    if (child is Filter<*>) {
                        collectMangaChips(child, defaultChildren?.getOrNull(i) as? Filter<*>, out)
                    }
                }
            }
            else -> Unit
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectAnimeChips(
        f: AnimeFilter<*>,
        default: AnimeFilter<*>?,
        out: MutableList<ActiveFilterChip>,
    ) {
        when (f) {
            is AnimeFilter.Select<*> -> {
                val state = (f as AnimeFilter<Int>).state
                val defaultState = (default as? AnimeFilter<Int>)?.state ?: 0
                if (state != defaultState) {
                    val value = f.values.getOrNull(state)?.toString() ?: return
                    out.add(ActiveFilterChip("${f.name}: $value") {
                        (f as AnimeFilter<Int>).state = defaultState
                    })
                }
            }
            is AnimeFilter.Text -> {
                val state = (f as AnimeFilter<String>).state
                val defaultState = (default as? AnimeFilter<String>)?.state ?: ""
                if (state != defaultState) {
                    out.add(ActiveFilterChip("${f.name}: $state") {
                        (f as AnimeFilter<String>).state = defaultState
                    })
                }
            }
            is AnimeFilter.CheckBox -> {
                val state = (f as AnimeFilter<Boolean>).state
                val defaultState = (default as? AnimeFilter<Boolean>)?.state ?: false
                if (state != defaultState) {
                    out.add(ActiveFilterChip(f.name) {
                        (f as AnimeFilter<Boolean>).state = defaultState
                    })
                }
            }
            is AnimeFilter.TriState -> {
                val state = (f as AnimeFilter<Int>).state
                val defaultState = (default as? AnimeFilter<Int>)?.state
                    ?: AnimeFilter.TriState.STATE_IGNORE
                if (state == defaultState) return
                val label = when (state) {
                    AnimeFilter.TriState.STATE_INCLUDE -> f.name
                    AnimeFilter.TriState.STATE_EXCLUDE -> getString(R.string.filter_exclude, f.name)
                    else -> return
                }
                out.add(ActiveFilterChip(label) {
                    (f as AnimeFilter<Int>).state = defaultState
                })
            }
            is AnimeFilter.Sort -> {
                val state = (f as AnimeFilter<AnimeFilter.Sort.Selection?>).state
                val defaultState = (default as? AnimeFilter<AnimeFilter.Sort.Selection?>)?.state
                if (animeSortSelectionEquals(state, defaultState)) return
                if (state == null) return
                val name = f.values.getOrNull(state.index) ?: return
                val arrow = if (state.ascending) "↑" else "↓"
                out.add(ActiveFilterChip("${f.name}: $name $arrow") {
                    (f as AnimeFilter<AnimeFilter.Sort.Selection?>).state = defaultState
                })
            }
            is AnimeFilter.Group<*> -> {
                val children = (f.state as? List<*>) ?: return
                val defaultChildren = (default as? AnimeFilter.Group<*>)?.state as? List<*>
                children.forEachIndexed { i, child ->
                    if (child is AnimeFilter<*>) {
                        collectAnimeChips(
                            child,
                            defaultChildren?.getOrNull(i) as? AnimeFilter<*>,
                            out,
                        )
                    }
                }
            }
            else -> Unit
        }
    }

    private fun sortSelectionEquals(
        a: Filter.Sort.Selection?,
        b: Filter.Sort.Selection?,
    ): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.index == b.index && a.ascending == b.ascending
    }

    private fun animeSortSelectionEquals(
        a: AnimeFilter.Sort.Selection?,
        b: AnimeFilter.Sort.Selection?,
    ): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.index == b.index && a.ascending == b.ascending
    }

    private inner class ActiveFilterChipAdapter :
        RecyclerView.Adapter<ActiveFilterChipAdapter.VH>() {

        private var items: List<ActiveFilterChip> = emptyList()

        fun submit(list: List<ActiveFilterChip>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val binding: ItemChipBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val chip = items[position]
            holder.binding.root.apply {
                text = chip.label
                isCloseIconVisible = true
                val remove = {
                    chip.onRemove()
                    val mode = if (currentQuery.isNotEmpty()) Mode.SEARCH else Mode.FILTER
                    load(mode, currentFilters)
                }
                setOnClickListener { remove() }
                setOnCloseIconClickListener { remove() }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun loadPage(page: Int) {
        loadJob?.cancel()
        val showLoading = adapter.itemCount == 0
        if (showLoading) binding.extensionBrowseProgress.isVisible = true

        loadJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { fetch(page) }
            }
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            binding.extensionBrowseProgress.isVisible = false
            val list = result.getOrNull()
            if (result.isFailure) {
                Logger.log(result.exceptionOrNull() ?: Exception("fetch failed"))
                snackString(getString(R.string.search_fetch_error))
                if (adapter.itemCount == 0) {
                    binding.extensionBrowseEmptyContainer.isVisible = true
                    binding.extensionBrowseEmptyText.text = getString(R.string.search_fetch_error)
                }
                return@launch
            }
            val entries = list?.first ?: emptyList()
            endReached = list?.second != true
            if (page == 1) {
                adapter.submit(entries)
            } else {
                adapter.append(entries)
            }
            if (adapter.itemCount == 0) {
                binding.extensionBrowseEmptyContainer.isVisible = true
                binding.extensionBrowseEmptyText.text = getString(R.string.no_results_found)
            } else {
                binding.extensionBrowseEmptyContainer.isVisible = false
                currentPage = page
            }
        }
    }

    private suspend fun fetch(page: Int): Pair<List<BrowseItem>, Boolean> {
        return if (animeExtension != null) {
            val source = animeExtension!!.sources.getOrNull(sourceIndex) as? AnimeCatalogueSource
                ?: return emptyList<BrowseItem>() to false
            val res: AnimesPage = when (currentMode) {
                Mode.POPULAR -> source.getPopularAnime(page)
                Mode.LATEST -> source.getLatestUpdates(page)
                Mode.FILTER -> {
                    val fl = currentFilters as? AnimeFilterList ?: source.getFilterList()
                    source.getSearchAnime(page, "", fl)
                }
                Mode.SEARCH -> {
                    val fl = currentFilters as? AnimeFilterList ?: source.getFilterList()
                    source.getSearchAnime(page, currentQuery, fl)
                }
            }
            res.animes.map { BrowseItem.fromAnime(it) } to res.hasNextPage
        } else if (mangaExtension != null) {
            val source = mangaExtension!!.sources.getOrNull(sourceIndex) as? CatalogueSource
                ?: return emptyList<BrowseItem>() to false
            val res: MangasPage = when (currentMode) {
                Mode.POPULAR -> source.fetchPopularManga(page).awaitSingle()
                Mode.LATEST -> source.fetchLatestUpdates(page).awaitSingle()
                Mode.FILTER -> {
                    val fl = currentFilters as? FilterList ?: source.getFilterList()
                    source.fetchSearchManga(page, "", fl).awaitSingle()
                }
                Mode.SEARCH -> {
                    val fl = currentFilters as? FilterList ?: source.getFilterList()
                    source.fetchSearchManga(page, currentQuery, fl).awaitSingle()
                }
            }
            res.mangas.map { BrowseItem.fromManga(it) } to res.hasNextPage
        } else {
            emptyList<BrowseItem>() to false
        }
    }

    private fun openInfo(item: BrowseItem) {
        val intent = Intent(this, ExtensionMediaInfoActivity::class.java).apply {
            putExtra(ExtensionMediaInfoActivity.EXTRA_PKG,
                animeExtension?.pkgName ?: mangaExtension?.pkgName)
            putExtra(ExtensionMediaInfoActivity.EXTRA_TYPE, type)
            putExtra(ExtensionMediaInfoActivity.EXTRA_LANG_INDEX, sourceIndex)
            if (item.anime != null) putExtra(ExtensionMediaInfoActivity.EXTRA_ANIME, item.anime)
            if (item.manga != null) putExtra(ExtensionMediaInfoActivity.EXTRA_MANGA, item.manga)
        }
        startActivity(intent)
    }

    private fun openInBrowser(item: BrowseItem) {
        try {
            val url = when {
                item.anime != null -> (animeExtension?.sources?.getOrNull(sourceIndex)
                    as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource)
                    ?.getAnimeUrl(item.anime)
                item.manga != null -> (mangaExtension?.sources?.getOrNull(sourceIndex)
                    as? eu.kanade.tachiyomi.source.online.HttpSource)
                    ?.getMangaUrl(item.manga)
                else -> null
            }
            if (url.isNullOrBlank()) {
                snackString(getString(R.string.no_results_found))
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            snackString("Failed to open link: ${e.message}")
        }
    }
}

data class BrowseItem(
    val title: String,
    val thumbnail: String?,
    val anime: SAnime? = null,
    val manga: SManga? = null,
) {
    companion object {
        fun fromAnime(a: SAnime) = BrowseItem(a.title, a.thumbnail_url, anime = a)
        fun fromManga(m: SManga) = BrowseItem(m.title, m.thumbnail_url, manga = m)
    }
}
