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
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: SearchHeaderViewHolder, position: Int) {
        binding = holder.binding

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
        binding.searchResultGrid.visibility = View.GONE
        binding.searchResultList.visibility = View.GONE

        if (type == SearchType.MANGAUPDATES || type == SearchType.COMICK) {
            binding.searchFilter.visibility = View.VISIBLE
            binding.searchChipRecycler.visibility = View.VISIBLE
            if (type == SearchType.MANGAUPDATES) {
                muChipAdapter = MUChipAdapter(activity, this)
                activity.updateMuChips = { muChipAdapter?.update() }
                binding.searchChipRecycler.adapter = muChipAdapter
            } else {
                comickChipAdapter = ComickChipAdapter(activity)
                activity.updateComickChips = { comickChipAdapter?.update() }
                binding.searchChipRecycler.adapter = comickChipAdapter
            }
            binding.searchChipRecycler.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.searchFilter.setOnClickListener {
                if (type == SearchType.MANGAUPDATES) {
                    MUSearchFilterBottomSheet.newInstance()
                        .show(activity.supportFragmentManager, "mu_filter")
                } else {
                    ComickSearchFilterBottomSheet.newInstance()
                        .show(activity.supportFragmentManager, "comick_filter")
                }
            }
        } else {
            binding.searchFilter.visibility = View.GONE
            binding.searchChipRecycler.visibility = View.GONE
        }

        binding.searchBar.hint = activity.searchType.toAnilistString()
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
        when (type) {
            SearchType.CHARACTER -> {
                binding.searchBarText.setText(activity.characterResult.search)
                binding.searchBarText.setSelection(binding.searchBarText.text.length)
                binding.searchBarText.clearFocus()
                binding.root.requestFocus()
                imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
            }

            SearchType.STUDIO -> {
                binding.searchBarText.setText(activity.studioResult.search)
                binding.searchBarText.setSelection(binding.searchBarText.text.length)
                binding.searchBarText.clearFocus()
                binding.root.requestFocus()
                imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
            }

            SearchType.STAFF -> {
                binding.searchBarText.setText(activity.staffResult.search)
                binding.searchBarText.setSelection(binding.searchBarText.text.length)
                binding.searchBarText.clearFocus()
                binding.root.requestFocus()
                imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
            }

            SearchType.USER -> {
                binding.searchBarText.setText(activity.userResult.search)
                binding.searchBarText.setSelection(binding.searchBarText.text.length)
                binding.searchBarText.clearFocus()
                binding.root.requestFocus()
                imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
            }

            SearchType.MANGAUPDATES -> {
                binding.searchBarText.setText(activity.muSearchResult.search)
                binding.searchBarText.setSelection(binding.searchBarText.text.length)
                binding.searchBarText.clearFocus()
                binding.root.requestFocus()
                imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
            }

            SearchType.COMICK -> {
                binding.searchBarText.setText(activity.comickSearchResult.search)
                binding.searchBarText.setSelection(binding.searchBarText.text.length)
                binding.searchBarText.clearFocus()
                binding.root.requestFocus()
                imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
            }

            else -> throw IllegalArgumentException("Invalid search type")
        }

        binding.clearHistory.setOnClickListener {
            it.startAnimation(fadeOutAnimation())
            it.visibility = View.GONE
            searchHistoryAdapter.clearHistory()
        }
        updateClearHistoryVisibilityWithFilters()
        fun searchTitle() {
            val searchText = binding.searchBarText.text.toString().takeIf { it.isNotEmpty() }

            val result: SearchResults<*> = when (type) {
                SearchType.CHARACTER -> activity.characterResult
                SearchType.STUDIO -> activity.studioResult
                SearchType.STAFF -> activity.staffResult
                SearchType.USER -> activity.userResult
                SearchType.MANGAUPDATES -> activity.muSearchResult
                SearchType.COMICK -> activity.comickSearchResult
                else -> throw IllegalArgumentException("Invalid search type")
            }

            result.search = searchText
            activity.search()
        }

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (s.toString().isBlank()) {
                    when (type) {
                        SearchType.CHARACTER -> activity.characterResult.search = null
                        SearchType.STUDIO -> activity.studioResult.search = null
                        SearchType.STAFF -> activity.staffResult.search = null
                        SearchType.USER -> activity.userResult.search = null
                        SearchType.MANGAUPDATES -> activity.muSearchResult.search = null
                        SearchType.COMICK -> activity.comickSearchResult.search = null
                        else -> Unit
                    }
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
            SearchType.COMICK -> R.drawable.ic_round_comick_24
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

            SearchType.COMICK -> {
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
}
