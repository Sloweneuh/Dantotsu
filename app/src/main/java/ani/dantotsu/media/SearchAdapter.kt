package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.HORIZONTAL
import ani.dantotsu.App.Companion.context
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.imagesearch.ImageSearchActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.checkbox.MaterialCheckBox.STATE_CHECKED
import com.google.android.material.checkbox.MaterialCheckBox.STATE_INDETERMINATE
import com.google.android.material.checkbox.MaterialCheckBox.STATE_UNCHECKED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchAdapter(private val activity: SearchActivity, private val type: SearchType) :
    HeaderInterface() {

    private fun updateFilterTextViewDrawable() {
        val filterDrawable = when (activity.aniMangaResult.sort) {
            Anilist.sortBy[0] -> R.drawable.ic_round_area_chart_24
            Anilist.sortBy[1] -> R.drawable.ic_round_filter_peak_24
            Anilist.sortBy[2] -> R.drawable.ic_round_star_graph_24
            Anilist.sortBy[3] -> R.drawable.ic_round_new_releases_24
            Anilist.sortBy[4] -> R.drawable.ic_round_filter_list_24
            Anilist.sortBy[5] -> R.drawable.ic_round_filter_list_24_reverse
            Anilist.sortBy[6] -> R.drawable.ic_round_assist_walker_24
            else -> R.drawable.ic_round_filter_alt_24
        }
        binding.filterTextView.setCompoundDrawablesWithIntrinsicBounds(filterDrawable, 0, 0, 0)
    }

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

        if (activity.searchType != SearchType.MANGA && activity.searchType != SearchType.ANIME) {
            throw IllegalArgumentException("Invalid search type (wrong adapter)")
        }

        when (activity.style) {
            0 -> {
                binding.searchResultGrid.alpha = 1f
                binding.searchResultList.alpha = 0.33f
            }

            1 -> {
                binding.searchResultList.alpha = 1f
                binding.searchResultGrid.alpha = 0.33f
            }
        }

        binding.searchBar.hint = activity.aniMangaResult.type
        if (PrefManager.getVal(PrefName.Incognito)) {
            val startIconDrawableRes = R.drawable.ic_incognito_24
            val startIconDrawable: Drawable? =
                context?.let { AppCompatResources.getDrawable(it, startIconDrawableRes) }
            binding.searchBar.startIconDrawable = startIconDrawable
        }

        // Initialize search bar text from existing model value and move caret to end
        binding.searchBarText.setText(activity.aniMangaResult.search)
        binding.searchBarText.setSelection(binding.searchBarText.text.length)
        binding.searchBarText.clearFocus()
        binding.root.requestFocus()
        imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)

        // Back button
        binding.searchBack.setOnClickListener { activity.finish() }

        // Quick type switch popup
        // Type icon on the left of the search bar
        binding.searchTypeIcon.setImageResource(getIconForType(activity.searchType))
        binding.searchTypeIcon.setOnClickListener {
            val query = binding.searchBarText.text.toString().takeIf { it.isNotBlank() }
            ani.dantotsu.home.SearchBottomSheet.newInstance(query)
                .show(activity.supportFragmentManager, "search_type")
        }


    

        binding.searchChipRecycler.adapter = SearchChipAdapter(activity, this).also {
            activity.updateChips = { it.update() }
        }

        binding.searchChipRecycler.layoutManager =
            LinearLayoutManager(binding.root.context, HORIZONTAL, false)

        binding.searchFilter.setOnClickListener {
            SearchFilterBottomDialog.newInstance().show(activity.supportFragmentManager, "dialog")
        }
        binding.searchFilter.setOnLongClickListener {
            val popupMenu = PopupMenu(activity, binding.searchFilter)
            popupMenu.menuInflater.inflate(R.menu.sortby_filter_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.sort_by_score -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[0]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_popular -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[1]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_trending -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[2]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_recent -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[3]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_a_z -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[4]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_z_a -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[5]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_pure_pain -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[6]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }
                }
                true
            }
            popupMenu.show()
            true
        }
        if (activity.aniMangaResult.type != "ANIME") {
            binding.searchByImage.visibility = View.GONE
        }
        binding.searchByImage.setOnClickListener {
            activity.startActivity(Intent(activity, ImageSearchActivity::class.java))
        }
        binding.clearHistory.setOnClickListener {
            it.startAnimation(fadeOutAnimation())
            it.visibility = View.GONE
            searchHistoryAdapter.clearHistory()
        }
        updateClearHistoryVisibilityWithFilters()
        fun searchTitle() {
            activity.aniMangaResult.apply {
                search =
                    if (binding.searchBarText.text.toString() != "") binding.searchBarText.text.toString() else null
            }
            if (binding.searchBarText.text.toString().equals("hentai", true)) {
                openLinkInBrowser("https://www.youtube.com/watch?v=GgJrEOo0QoA")
            }
            activity.search()
        }

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (s.toString().isBlank()) {
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

        binding.searchResultGrid.setOnClickListener {
            it.alpha = 1f
            binding.searchResultList.alpha = 0.33f
            activity.style = 0
            PrefManager.setVal(PrefName.SearchStyle, 0)
            activity.recycler()
        }
        binding.searchResultList.setOnClickListener {
            it.alpha = 1f
            binding.searchResultGrid.alpha = 0.33f
            activity.style = 1
            PrefManager.setVal(PrefName.SearchStyle, 1)
            activity.recycler()
        }

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
            else -> R.drawable.ic_round_search_24
        }
    }

    private fun updateClearHistoryVisibilityWithFilters() {
        // Hide clear history button if there are active filters but no search text
        val hasFilters = activity.aniMangaResult.run {
            !sort.isNullOrBlank() ||
                !genres.isNullOrEmpty() ||
                !excludedGenres.isNullOrEmpty() ||
                !tags.isNullOrEmpty() ||
                !excludedTags.isNullOrEmpty() ||
                !status.isNullOrBlank() ||
                !format.isNullOrBlank() ||
                seasonYear != null ||
                startYear != null ||
                (yearRangeStart != null && yearRangeEnd != null) ||
                !season.isNullOrBlank() ||
                !countryOfOrigin.isNullOrBlank()
        }

        val hasSearchText = binding.searchBarText.text.toString().isNotBlank()

        // Show clear history only if there's history AND (no filters OR there's search text)
        binding.clearHistory.visibility = if (
            searchHistoryAdapter.itemCount > 0 && (!hasFilters || hasSearchText)
        ) View.VISIBLE else View.GONE
    }

    class SearchChipAdapter(
        val activity: SearchActivity,
        private val searchAdapter: SearchAdapter
    ) :
        RecyclerView.Adapter<SearchChipAdapter.SearchChipViewHolder>() {
        private var chips = activity.aniMangaResult.toChipList()

        inner class SearchChipViewHolder(val binding: ItemChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchChipViewHolder {
            val binding =
                ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return SearchChipViewHolder(binding)
        }


        override fun onBindViewHolder(holder: SearchChipViewHolder, position: Int) {
            val chip = chips[position]
            holder.binding.root.apply {
                text = chip.text.replace("_", " ")
                isCloseIconVisible = true

                // Make entire chip clickable, not just the X icon
                setOnClickListener {
                    activity.aniMangaResult.removeChip(chip)
                    update()
                    activity.search()
                    searchAdapter.updateFilterTextViewDrawable()
                }
                setOnCloseIconClickListener {
                    activity.aniMangaResult.removeChip(chip)
                    update()
                    activity.search()
                    searchAdapter.updateFilterTextViewDrawable()
                }
            }
        }

        @SuppressLint("NotifyDataSetChanged")
        fun update() {
            chips = activity.aniMangaResult.toChipList()
            notifyDataSetChanged()
            searchAdapter.updateFilterTextViewDrawable()
        }

        override fun getItemCount(): Int = chips.size
    }
}
