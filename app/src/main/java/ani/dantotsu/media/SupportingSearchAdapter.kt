package ani.dantotsu.media

import android.annotation.SuppressLint
import android.widget.PopupMenu
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.App.Companion.context
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.SearchResults
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SupportingSearchAdapter(private val activity: SearchActivity, private val type: SearchType) :
    HeaderInterface() {

    private var muChipAdapter: MUChipAdapter? = null
    private var comickChipAdapter: ComickChipAdapter? = null
    private var mangaBakaChipAdapter: MangaBakaChipAdapter? = null
    private var kitsuChipAdapter: KitsuChipAdapter? = null

    // The grid/list style toggle applies to every "media card" tracker source.
    private fun isSupportingList(t: SearchType) =
        t == SearchType.MANGAUPDATES || t.isComick || t == SearchType.MANGABAKA ||
            t.isKitsu || t == SearchType.SIMKL || t.isMal

    // Simkl's and MAL's search endpoints take no filter params, so they get the style toggle but
    // no filter sheet (MAL's official-API search only supports q/limit/offset).
    private fun hasFilterSheet(t: SearchType) =
        t == SearchType.MANGAUPDATES || t.isComick || t == SearchType.MANGABAKA || t.isKitsu

    @SuppressLint("ClickableViewAccessibility")
    override fun bind() {
        searchHistoryAdapter = SearchHistoryAdapter(type) {
            binding.searchBarText.setText(it)
            binding.searchBarText.setSelection(it.length)
        }
        binding.searchHistoryList.layoutManager = LinearLayoutManager(binding.root.context)
        binding.searchHistoryList.adapter = searchHistoryAdapter

        val imm: InputMethodManager =
            activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager

        if (activity.searchType == SearchType.MANGA || activity.searchType == SearchType.ANIME) {
            throw IllegalArgumentException("Invalid search type (wrong adapter)")
        }

        binding.searchByImage.visibility = View.GONE

        if (isSupportingList(type)) {
            when (activity.supportStyle) {
                0 -> {
                    binding.searchResultGrid.alpha = 1f
                    binding.searchResultList.alpha = 0.33f
                }
                else -> {
                    binding.searchResultList.alpha = 1f
                    binding.searchResultGrid.alpha = 0.33f
                }
            }
            binding.searchResultGrid.setOnClickListener {
                it.alpha = 1f
                binding.searchResultList.alpha = 0.33f
                activity.supportStyle = 0
                PrefManager.setVal(PrefName.SearchStyleSupporting, 0)
                activity.recyclerSupporting()
            }
            binding.searchResultList.setOnClickListener {
                it.alpha = 1f
                binding.searchResultGrid.alpha = 0.33f
                activity.supportStyle = 1
                PrefManager.setVal(PrefName.SearchStyleSupporting, 1)
                activity.recyclerSupporting()
            }
        } else {
            binding.searchResultGrid.visibility = View.GONE
            binding.searchResultList.visibility = View.GONE
        }

        if (hasFilterSheet(type)) {
            binding.searchFilter.visibility = View.VISIBLE
            binding.searchChipRecycler.visibility = View.VISIBLE
            when {
                type == SearchType.MANGAUPDATES -> {
                    muChipAdapter = MUChipAdapter(activity, this)
                    activity.updateMuChips = { muChipAdapter?.update() }
                    binding.searchChipRecycler.adapter = muChipAdapter
                }
                type.isComick -> {
                    comickChipAdapter = ComickChipAdapter(activity, this)
                    activity.updateComickChips = { comickChipAdapter?.update() }
                    binding.searchChipRecycler.adapter = comickChipAdapter
                }
                type.isKitsu -> {
                    kitsuChipAdapter = KitsuChipAdapter(activity, this)
                    activity.updateKitsuChips = { kitsuChipAdapter?.update() }
                    binding.searchChipRecycler.adapter = kitsuChipAdapter
                }
                else -> {
                    mangaBakaChipAdapter = MangaBakaChipAdapter(activity, this)
                    activity.updateMangaBakaChips = { mangaBakaChipAdapter?.update() }
                    binding.searchChipRecycler.adapter = mangaBakaChipAdapter
                }
            }
            binding.searchChipRecycler.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.searchFilter.setOnClickListener {
                when {
                    type == SearchType.MANGAUPDATES -> MUSearchFilterBottomSheet.newInstance()
                        .show(activity.supportFragmentManager, "mu_filter")
                    type.isComick -> ComickSearchFilterBottomSheet.newInstance()
                        .show(activity.supportFragmentManager, "comick_filter")
                    type.isKitsu -> KitsuSearchFilterBottomSheet.newInstance()
                        .show(activity.supportFragmentManager, "kitsu_filter")
                    else -> MangaBakaSearchFilterBottomSheet.newInstance()
                        .show(activity.supportFragmentManager, "mangabaka_filter")
                }
            }
        } else {
            binding.searchFilter.visibility = View.GONE
            binding.searchChipRecycler.visibility = View.GONE
        }

        binding.searchBar.hint = activity.searchType.hint(binding.root.context)
        if (PrefManager.getVal(PrefName.Incognito)) {
            val startIconDrawableRes = R.drawable.ic_incognito_24
            val startIconDrawable: Drawable? =
                context?.let { AppCompatResources.getDrawable(it, startIconDrawableRes) }
            binding.searchBar.startIconDrawable = startIconDrawable
        }

        // Back button
        binding.searchBack.setOnClickListener { activity.finish() }

        // Quick type switch popup (icon only)
        // Type icon on the left of the search bar for supporting types
        binding.searchTypeIcon.setImageResource(getIconForType(activity.searchType))
        binding.searchTypeIcon.setOnClickListener {
            val query = binding.searchBarText.text.toString().takeIf { it.isNotBlank() }
            ani.dantotsu.home.SearchBottomSheet.newInstance(query)
                .show(activity.supportFragmentManager, "search_type")
        }


    

        binding.searchBarText.removeTextChangedListener(textWatcher)

        // The state this header edits, resolved on each use rather than captured: the text watcher
        // needs the query as it stands when it fires, not as it stood when the header was bound.
        fun currentResult(): SearchResults<*> = when (type) {
            SearchType.CHARACTER -> activity.characterResult
            SearchType.STUDIO -> activity.studioResult
            SearchType.STAFF -> activity.staffResult
            SearchType.USER -> activity.userResult
            SearchType.MANGAUPDATES -> activity.muSearchResult
            SearchType.COMICK, SearchType.COMICK_ANIME -> activity.comickSearchResult
            SearchType.MANGABAKA -> activity.mangaBakaSearchResult
            SearchType.KITSU, SearchType.KITSU_ANIME -> activity.kitsuSearchResult
            SearchType.SIMKL -> activity.simklSearchResult
            SearchType.MAL, SearchType.MAL_ANIME -> activity.malSearchResult
            else -> throw IllegalArgumentException("Invalid search type")
        }

        val modelText = currentResult().search ?: ""

        val currentText = binding.searchBarText.text.toString()
        if (!binding.searchBarText.hasFocus() && currentText != modelText) {
            binding.searchBarText.setText(modelText)
            binding.searchBarText.setSelection(binding.searchBarText.text.length)
            binding.searchBarText.clearFocus()
            binding.root.requestFocus()
            imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
        }

        binding.clearHistory.setOnClickListener {
            it.startAnimation(fadeOutAnimation())
            it.visibility = View.GONE
            searchHistoryAdapter.clearHistory()
        }
        updateClearHistoryVisibilityWithFilters()
        fun searchTitle() {
            currentResult().search = binding.searchBarText.text.toString().takeIf { it.isNotEmpty() }
            activity.search()
        }

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                val text = s.toString().takeIf { it.isNotBlank() }
                // The field restores the text it saved for itself, after bind() has already seeded
                // it from the model — so every configuration change arrives here with nothing
                // actually changed. Read as the user emptying the box, that wiped the results and
                // sent the screen back to the history list.
                if (text == currentResult().search) return
                if (text == null) {
                    currentResult().search = null
                    activity.emptyMediaAdapter()
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(200)
                        activity.runOnUiThread {
                            setHistoryVisibility(true)
                        }
                    }
                } else {
                    setHistoryVisibility(false)
                    searchTitle()
                }
            }
        }
        binding.searchBarText.addTextChangedListener(textWatcher)
        initContentVisibility(activity.model.resultsIsNotEmpty(activity.searchType))

        binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
            return@setOnEditorActionListener when (actionId) {
                EditorInfo.IME_ACTION_SEARCH -> {
                    searchTitle()
                    binding.searchBarText.clearFocus()
                    imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
                    true
                }

                else -> false
            }
        }
        binding.searchBar.setEndIconOnClickListener { searchTitle() }

        search = Runnable { searchTitle() }
        requestFocus = Runnable { binding.searchBarText.requestFocus() }
        markReady()
    }

    private fun getIconForType(type: SearchType): Int {
        return when (type) {
            SearchType.ANIME -> R.drawable.ic_round_movie_filter_24
            SearchType.MANGA -> R.drawable.ic_round_menu_book_24
            SearchType.USER -> R.drawable.ic_round_person_24
            SearchType.CHARACTER -> R.drawable.ic_round_face_24
            SearchType.STAFF -> R.drawable.ic_round_group_24
            SearchType.STUDIO -> R.drawable.ic_round_movie_edit_24
            SearchType.MANGAUPDATES -> R.drawable.ic_round_mangaupdates_24
            SearchType.COMICK -> R.drawable.ic_round_comick_manga_24
            SearchType.COMICK_ANIME -> R.drawable.ic_round_comick_anime_24
            SearchType.MANGABAKA -> R.drawable.ic_round_mangabaka_24
            SearchType.KITSU -> R.drawable.ic_kitsu_manga
            SearchType.KITSU_ANIME -> R.drawable.ic_kitsu_anime
            SearchType.SIMKL -> R.drawable.ic_simkl
            SearchType.MAL -> R.drawable.ic_myanimelist_manga
            SearchType.MAL_ANIME -> R.drawable.ic_myanimelist_anime
            else -> R.drawable.ic_round_search_24
        }
    }

    private fun updateClearHistoryVisibilityWithFilters() {
        // Hide clear history button if there are active filters but no search text
        val hasFilters = when (type) {
            SearchType.MANGAUPDATES -> {
                activity.muSearchResult.run {
                    !format.isNullOrBlank() ||
                        year != null ||
                        !genres.isNullOrEmpty() ||
                        !excludedGenres.isNullOrEmpty() ||
                        !categories.isNullOrEmpty() ||
                        !licensed.isNullOrBlank() ||
                        !orderBy.isNullOrBlank() ||
                        !statusFilters.isNullOrEmpty()
                }
            }

            SearchType.COMICK, SearchType.COMICK_ANIME -> {
                activity.comickSearchResult.run {
                    !genres.isNullOrEmpty() ||
                        !excludedGenres.isNullOrEmpty() ||
                        !tags.isNullOrEmpty() ||
                        !excludedTags.isNullOrEmpty() ||
                        !categories.isNullOrEmpty() ||
                        !excludedCategories.isNullOrEmpty() ||
                        !demographic.isNullOrEmpty() ||
                        !country.isNullOrEmpty() ||
                        !contentRating.isNullOrEmpty() ||
                        status != null ||
                        !sort.isNullOrBlank() ||
                        time != null ||
                        minimum != null ||
                        minimumRating != null ||
                        fromYear != null ||
                        toYear != null ||
                        completed != null ||
                        excludeMyList != null ||
                        showAll != null
                }
            }

            SearchType.MANGABAKA -> {
                activity.mangaBakaSearchResult.run {
                    !genres.isNullOrEmpty() ||
                        !excludedGenres.isNullOrEmpty() ||
                        !tags.isNullOrEmpty() ||
                        !excludedTags.isNullOrEmpty() ||
                        !types.isNullOrEmpty() ||
                        !statuses.isNullOrEmpty() ||
                        !contentRatings.isNullOrEmpty() ||
                        fromYear != null ||
                        toYear != null ||
                        !sort.isNullOrBlank()
                }
            }

            SearchType.KITSU, SearchType.KITSU_ANIME -> {
                activity.kitsuSearchResult.run {
                    !categories.isNullOrEmpty() ||
                        !subtypes.isNullOrEmpty() ||
                        !statuses.isNullOrEmpty() ||
                        !ageRatings.isNullOrEmpty() ||
                        !season.isNullOrBlank() ||
                        fromYear != null ||
                        toYear != null ||
                        !sort.isNullOrBlank()
                }
            }

            else -> false
        }

        val hasSearchText = binding.searchBarText.text.toString().isNotBlank()

        // Show clear history only if there's history AND (no filters OR there's search text)
        binding.clearHistory.visibility = if (
            searchHistoryAdapter.itemCount > 0 && (!hasFilters || hasSearchText)
        ) View.VISIBLE else View.GONE
    }

    /**
     * Chip adapter for active MangaUpdates search filters.
     */
    class MUChipAdapter(
        private val activity: SearchActivity,
        private val adapter: SupportingSearchAdapter
    ) : RecyclerView.Adapter<MUChipAdapter.MUChipViewHolder>() {

        private var chips = activity.muSearchResult.toChipList()

        inner class MUChipViewHolder(val binding: ItemChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): MUChipViewHolder {
            val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MUChipViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MUChipViewHolder, position: Int) {
            val chip = chips[position]
            holder.binding.root.apply {
                text = chip.text.replace("_", " ")
                isCloseIconVisible = true
                setOnClickListener { removeAndSearch(chip) }
                setOnCloseIconClickListener { removeAndSearch(chip) }
            }
        }

        private fun removeAndSearch(chip: AniMangaSearchResults.SearchChip) {
            activity.muSearchResult.removeChip(chip)
            update()
            activity.search()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun update() {
            chips = activity.muSearchResult.toChipList()
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = chips.size
    }

    class ComickChipAdapter(
        private val activity: SearchActivity,
        private val adapter: SupportingSearchAdapter,
    ) : RecyclerView.Adapter<ComickChipAdapter.ComickChipViewHolder>() {

        private var chips = activity.comickSearchResult.toChipList()

        inner class ComickChipViewHolder(val binding: ItemChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int,
        ): ComickChipViewHolder {
            val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ComickChipViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ComickChipViewHolder, position: Int) {
            val chip = chips[position]
            holder.binding.root.apply {
                text = chip.text.replace("_", " ")
                isCloseIconVisible = true
                setOnClickListener { removeAndSearch(chip) }
                setOnCloseIconClickListener { removeAndSearch(chip) }
            }
        }

        private fun removeAndSearch(chip: ani.dantotsu.connections.anilist.AniMangaSearchResults.SearchChip) {
            activity.comickSearchResult.removeChip(chip)
            update()
            activity.search()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun update() {
            chips = activity.comickSearchResult.toChipList()
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = chips.size
    }

    class MangaBakaChipAdapter(
        private val activity: SearchActivity,
        private val adapter: SupportingSearchAdapter,
    ) : RecyclerView.Adapter<MangaBakaChipAdapter.MangaBakaChipViewHolder>() {

        private var chips = activity.mangaBakaSearchResult.toChipList()

        inner class MangaBakaChipViewHolder(val binding: ItemChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int,
        ): MangaBakaChipViewHolder {
            val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MangaBakaChipViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MangaBakaChipViewHolder, position: Int) {
            val chip = chips[position]
            holder.binding.root.apply {
                text = chip.text.replace("_", " ")
                isCloseIconVisible = true
                setOnClickListener { removeAndSearch(chip) }
                setOnCloseIconClickListener { removeAndSearch(chip) }
            }
        }

        private fun removeAndSearch(chip: ani.dantotsu.connections.anilist.AniMangaSearchResults.SearchChip) {
            activity.mangaBakaSearchResult.removeChip(chip)
            update()
            activity.search()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun update() {
            chips = activity.mangaBakaSearchResult.toChipList()
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = chips.size
    }

    class KitsuChipAdapter(
        private val activity: SearchActivity,
        private val adapter: SupportingSearchAdapter,
    ) : RecyclerView.Adapter<KitsuChipAdapter.KitsuChipViewHolder>() {

        private var chips = activity.kitsuSearchResult.toChipList()

        inner class KitsuChipViewHolder(val binding: ItemChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int,
        ): KitsuChipViewHolder {
            val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return KitsuChipViewHolder(binding)
        }

        override fun onBindViewHolder(holder: KitsuChipViewHolder, position: Int) {
            val chip = chips[position]
            holder.binding.root.apply {
                text = chip.text.replace("_", " ")
                isCloseIconVisible = true
                setOnClickListener { removeAndSearch(chip) }
                setOnCloseIconClickListener { removeAndSearch(chip) }
            }
        }

        private fun removeAndSearch(chip: ani.dantotsu.connections.anilist.AniMangaSearchResults.SearchChip) {
            activity.kitsuSearchResult.removeChip(chip)
            update()
            activity.search()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun update() {
            chips = activity.kitsuSearchResult.toChipList()
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = chips.size
    }
}
