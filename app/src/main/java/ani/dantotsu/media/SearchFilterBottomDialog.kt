package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.HORIZONTAL
import androidx.recyclerview.widget.RecyclerView.VERTICAL
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.BottomSheetSearchFilterBinding
import ani.dantotsu.databinding.ItemChipBinding
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class SearchFilterBottomDialog : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSearchFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var activity: SearchActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSearchFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var selectedGenres = mutableListOf<String>()
    private var exGenres = mutableListOf<String>()
    private var selectedTags = mutableListOf<String>()
    private var exTags = mutableListOf<String>()
    private var isAdult = false
    private var listOnly: Boolean? = null
    private var tagSearchQuery: String = ""

    private fun updateTagsList(includeAdult: Boolean) {
        val tagsList = if (includeAdult && Anilist.adult) {
            val adultTags = Anilist.tags?.get(true) ?: listOf()
            val nonAdultTags = Anilist.tags?.get(false) ?: listOf()
            (adultTags + nonAdultTags).distinct().sorted()
        } else {
            Anilist.tags?.get(false) ?: listOf()
        }
        val filteredTags = if (tagSearchQuery.isBlank()) {
            tagsList
        } else {
            val query = tagSearchQuery.trim().lowercase()
            tagsList.filter { it.lowercase().contains(query) }
        }
        binding.searchFilterTags.adapter = FilterChipAdapter(filteredTags) { chip ->
            val tag = chip.text.toString()
            chip.isChecked = selectedTags.contains(tag)
            chip.isCloseIconVisible = exTags.contains(tag)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    chip.isCloseIconVisible = false
                    exTags.remove(tag)
                    selectedTags.add(tag)
                } else selectedTags.remove(tag)
            }
            chip.setOnLongClickListener {
                chip.isChecked = false
                chip.isCloseIconVisible = true
                exTags.add(tag)
            }
        }
        updateTagSearchIcon(binding.searchTagsQuickSearchLayout.visibility == View.VISIBLE)
    }

    private fun updateTagSearchIcon(isSearchMode: Boolean) {
        binding.searchTagsAnilist.setImageResource(
            if (isSearchMode) R.drawable.ic_round_search_off_24
            else R.drawable.ic_round_search_24
        )
    }

    private fun setTagSearchMode(enabled: Boolean) {
        binding.searchTagsQuickSearchLayout.visibility = if (enabled) View.VISIBLE else GONE
        if (Anilist.adult) {
            binding.searchTagsAdultContainer.visibility = if (enabled) GONE else View.VISIBLE
        }
        updateTagSearchIcon(enabled)

        if (enabled) {
            binding.searchTagsQuickSearchText.requestFocus()
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(binding.searchTagsQuickSearchText, InputMethodManager.SHOW_IMPLICIT)
        } else {
            tagSearchQuery = ""
            binding.searchTagsQuickSearchText.setText("")
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(binding.searchTagsQuickSearchText.windowToken, 0)
            updateTagsList(binding.searchTagsAdult.isChecked)
        }
    }
    private fun updateChips() {
        binding.searchFilterGenres.adapter?.notifyDataSetChanged()
        binding.searchFilterTags.adapter?.notifyDataSetChanged()
    }

    private fun createWrapChipLayoutManager(): RecyclerView.LayoutManager {
        return FlexboxLayoutManager(requireContext()).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
        }
    }

    private fun startBounceZoomAnimation(view: View? = null) {
        val targetView = view ?: binding.sortByFilter
        val bounceZoomAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.bounce_zoom)
        targetView.startAnimation(bounceZoomAnimation)
    }

    private fun setSortByFilterImage() {
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
        binding.sortByFilter.setImageResource(filterDrawable)
    }

    private fun setCountryButton(country: String?) {
        if (country == null) {
            val drawable = ContextCompat.getDrawable(
                requireContext(), R.drawable.ic_round_globe_search_googlefonts
            )!!.mutate()
            DrawableCompat.setTint(
                DrawableCompat.wrap(drawable),
                requireContext().getResourceColor(com.google.android.material.R.attr.colorPrimary)
            )
            val size = (35 * resources.displayMetrics.density).toInt()
            drawable.setBounds(0, 0, size, size)
            binding.countryFilter.text = ""
            binding.countryFilter.setCompoundDrawablesRelative(drawable, null, null, null)
        } else {
            binding.countryFilter.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, null, null
            )
            binding.countryFilter.text = when (country) {
                "CN" -> "\uD83C\uDDE8\uD83C\uDDF3"
                "KR" -> "\uD83C\uDDF0\uD83C\uDDF7"
                "JP" -> "\uD83C\uDDEF\uD83C\uDDF5"
                "TW" -> "\uD83C\uDDF9\uD83C\uDDFC"
                else -> "\uD83C\uDF10"
            }
        }
    }

    private fun resetSearchFilter() {
        activity.aniMangaResult.sort = null
        binding.sortByFilter.setImageResource(R.drawable.ic_round_filter_alt_24)
        startBounceZoomAnimation(binding.sortByFilter)
        activity.aniMangaResult.countryOfOrigin = null
        setCountryButton(null)
        startBounceZoomAnimation(binding.countryFilter)

        selectedGenres.clear()
        exGenres.clear()
        selectedTags.clear()
        exTags.clear()
        isAdult = false
        tagSearchQuery = ""
        setTagSearchMode(false)
        listOnly = null
        binding.searchTagsAdult.isChecked = false
        updateTagsList(false)
        binding.searchListOnly.checkedState = com.google.android.material.checkbox.MaterialCheckBox.STATE_UNCHECKED
        binding.searchStatus.setText("")
        binding.searchSource.setText("")
        binding.searchFormat.setText("")
        binding.searchSeason.setText("")
        val maxYear = Calendar.getInstance().get(Calendar.YEAR) + 1
        binding.searchYearRange.valueTo = maxYear.toFloat()
        binding.searchYearRange.values = listOf(1970f, maxYear.toFloat())
        binding.searchStatus.clearFocus()
        binding.searchFormat.clearFocus()
        binding.searchSeason.clearFocus()
        updateChips()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        activity = requireActivity() as SearchActivity

        selectedGenres = activity.aniMangaResult.genres ?: mutableListOf()
        exGenres = activity.aniMangaResult.excludedGenres ?: mutableListOf()
        selectedTags = activity.aniMangaResult.tags ?: mutableListOf()
        exTags = activity.aniMangaResult.excludedTags ?: mutableListOf()
        isAdult = activity.aniMangaResult.isAdult
        listOnly = activity.aniMangaResult.onList
        setSortByFilterImage()
        setCountryButton(activity.aniMangaResult.countryOfOrigin)

        binding.resetSearchFilter.setOnClickListener {
            val rotateAnimation =
                ObjectAnimator.ofFloat(binding.resetSearchFilter, "rotation", 180f, 540f)
            rotateAnimation.duration = 500
            rotateAnimation.interpolator = AccelerateDecelerateInterpolator()
            rotateAnimation.start()
            resetSearchFilter()
        }

        binding.resetSearchFilter.setOnLongClickListener {
            val rotateAnimation =
                ObjectAnimator.ofFloat(binding.resetSearchFilter, "rotation", 180f, 540f)
            rotateAnimation.duration = 500
            rotateAnimation.interpolator = AccelerateDecelerateInterpolator()
            rotateAnimation.start()
            val bounceAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.bounce_zoom)

            binding.resetSearchFilter.startAnimation(bounceAnimation)
            binding.resetSearchFilter.postDelayed({
                resetSearchFilter()

                CoroutineScope(Dispatchers.Main).launch {
                    val rangeStart = binding.searchYearRange.values[0].toInt()
                    val rangeEnd = binding.searchYearRange.values[1].toInt()
                    val minRange = minOf(rangeStart, rangeEnd)
                    val maxRange = maxOf(rangeStart, rangeEnd)
                    val defaultRangeStart = 1970
                    val defaultRangeEnd = Calendar.getInstance().get(Calendar.YEAR) + 1
                    val shouldApplyYearRange =
                        !(minRange == defaultRangeStart && maxRange == defaultRangeEnd)
                    activity.aniMangaResult.apply {
                        status =
                            binding.searchStatus.text.toString().replace(" ", "_").ifBlank { null }
                        source =
                            binding.searchSource.text.toString().replace(" ", "_").ifBlank { null }
                        format = binding.searchFormat.text.toString().ifBlank { null }
                        season = binding.searchSeason.text.toString().ifBlank { null }
                        seasonYear = null
                        startYear = null
                        yearRangeStart = if (shouldApplyYearRange) minRange else null
                        yearRangeEnd = if (shouldApplyYearRange) maxRange else null
                        sort = activity.aniMangaResult.sort
                        this.isAdult = this@SearchFilterBottomDialog.isAdult
                        onList = this@SearchFilterBottomDialog.listOnly
                        genres = selectedGenres
                        tags = selectedTags
                        excludedGenres = exGenres
                        excludedTags = exTags
                    }
                    activity.updateChips.invoke()
                    activity.search()
                    dismiss()
                }
            }, 500)
            true
        }

        binding.sortByFilter.setOnClickListener {
            val popupMenu = PopupMenu(requireContext(), it)
            popupMenu.menuInflater.inflate(R.menu.sortby_filter_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.sort_by_score -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[0]
                        binding.sortByFilter.setImageResource(R.drawable.ic_round_area_chart_24)
                        startBounceZoomAnimation()
                    }

                    R.id.sort_by_popular -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[1]
                        binding.sortByFilter.setImageResource(R.drawable.ic_round_filter_peak_24)
                        startBounceZoomAnimation()
                    }

                    R.id.sort_by_trending -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[2]
                        binding.sortByFilter.setImageResource(R.drawable.ic_round_star_graph_24)
                        startBounceZoomAnimation()
                    }

                    R.id.sort_by_recent -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[3]
                        binding.sortByFilter.setImageResource(R.drawable.ic_round_new_releases_24)
                        startBounceZoomAnimation()
                    }

                    R.id.sort_by_a_z -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[4]
                        binding.sortByFilter.setImageResource(R.drawable.ic_round_filter_list_24)
                        startBounceZoomAnimation()
                    }

                    R.id.sort_by_z_a -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[5]
                        binding.sortByFilter.setImageResource(R.drawable.ic_round_filter_list_24_reverse)
                        startBounceZoomAnimation()
                    }

                    R.id.sort_by_pure_pain -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[6]
                        binding.sortByFilter.setImageResource(R.drawable.ic_round_assist_walker_24)
                        startBounceZoomAnimation()
                    }
                }
                true
            }
            popupMenu.show()
        }

        binding.countryFilter.setOnClickListener {
            val popupMenu = PopupMenu(requireContext(), it)
            popupMenu.menuInflater.inflate(R.menu.country_filter_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.country_global -> {
                        activity.aniMangaResult.countryOfOrigin = null
                        setCountryButton(null)
                        startBounceZoomAnimation(binding.countryFilter)
                    }

                    R.id.country_china -> {
                        activity.aniMangaResult.countryOfOrigin = "CN"
                        setCountryButton("CN")
                        startBounceZoomAnimation(binding.countryFilter)
                    }

                    R.id.country_south_korea -> {
                        activity.aniMangaResult.countryOfOrigin = "KR"
                        setCountryButton("KR")
                        startBounceZoomAnimation(binding.countryFilter)
                    }

                    R.id.country_japan -> {
                        activity.aniMangaResult.countryOfOrigin = "JP"
                        setCountryButton("JP")
                        startBounceZoomAnimation(binding.countryFilter)
                    }

                    R.id.country_taiwan -> {
                        activity.aniMangaResult.countryOfOrigin = "TW"
                        setCountryButton("TW")
                        startBounceZoomAnimation(binding.countryFilter)
                    }
                }
                true
            }
            popupMenu.show()
        }

        binding.searchFilterApply.setOnClickListener {
            val rangeStart = binding.searchYearRange.values[0].toInt()
            val rangeEnd = binding.searchYearRange.values[1].toInt()
            val minRange = minOf(rangeStart, rangeEnd)
            val maxRange = maxOf(rangeStart, rangeEnd)
            val defaultRangeStart = 1970
            val defaultRangeEnd = Calendar.getInstance().get(Calendar.YEAR) + 1
            val shouldApplyYearRange =
                !(minRange == defaultRangeStart && maxRange == defaultRangeEnd)
            activity.aniMangaResult.apply {
                status = binding.searchStatus.text.toString().replace(" ", "_").ifBlank { null }
                source = binding.searchSource.text.toString().replace(" ", "_").ifBlank { null }
                format = binding.searchFormat.text.toString().ifBlank { null }
                season = binding.searchSeason.text.toString().ifBlank { null }
                seasonYear = null
                startYear = null
                yearRangeStart = if (shouldApplyYearRange) minRange else null
                yearRangeEnd = if (shouldApplyYearRange) maxRange else null
                sort = activity.aniMangaResult.sort
                countryOfOrigin = activity.aniMangaResult.countryOfOrigin
                this.isAdult = this@SearchFilterBottomDialog.isAdult
                onList = this@SearchFilterBottomDialog.listOnly
                genres = selectedGenres
                tags = selectedTags
                excludedGenres = exGenres
                excludedTags = exTags
            }
            activity.updateChips.invoke()
            activity.search()
            dismiss()
        }
        binding.searchFilterCancel.setOnClickListener {
            dismiss()
        }
        val format =
            if (activity.aniMangaResult.type == "ANIME") Anilist.animeStatus else Anilist.mangaStatus
        binding.searchStatus.setText(activity.aniMangaResult.status?.replace("_", " "))
        binding.searchStatus.setAdapter(
            ArrayAdapter(
                binding.root.context,
                R.layout.item_dropdown,
                format
            )
        )

        binding.searchSource.setText(activity.aniMangaResult.source?.replace("_", " "))
        binding.searchSource.setAdapter(
            ArrayAdapter(
                binding.root.context,
                R.layout.item_dropdown,
                Anilist.source.toTypedArray()
            )
        )

        binding.searchFormat.setText(activity.aniMangaResult.format)
        binding.searchFormat.setAdapter(
            ArrayAdapter(
                binding.root.context,
                R.layout.item_dropdown,
                (if (activity.aniMangaResult.type == "ANIME") Anilist.animeFormats else Anilist.mangaFormats).toTypedArray()
            )
        )

        val maxYear = Calendar.getInstance().get(Calendar.YEAR) + 1
        binding.searchYearRange.valueTo = maxYear.toFloat()
        val initialRangeStart = activity.aniMangaResult.yearRangeStart
        val initialRangeEnd = activity.aniMangaResult.yearRangeEnd
        if (initialRangeStart != null && initialRangeEnd != null) {
            binding.searchYearRange.values = listOf(initialRangeStart.toFloat(), initialRangeEnd.toFloat())
        } else if (activity.aniMangaResult.type == "ANIME") {
            val year = activity.aniMangaResult.seasonYear
            binding.searchYearRange.values = if (year != null) {
                listOf(year.toFloat(), year.toFloat())
            } else {
                listOf(1970f, maxYear.toFloat())
            }
        } else {
            val year = activity.aniMangaResult.startYear
            binding.searchYearRange.values = if (year != null) {
                listOf(year.toFloat(), year.toFloat())
            } else {
                listOf(1970f, maxYear.toFloat())
            }
        }

        if (activity.aniMangaResult.type == "MANGA") binding.searchSeasonCont.visibility = GONE
        else {
            binding.searchSeason.setText(activity.aniMangaResult.season)
            binding.searchSeason.setAdapter(
                ArrayAdapter(
                    binding.root.context,
                    R.layout.item_dropdown,
                    Anilist.seasons.toTypedArray()
                )
            )
        }

        binding.searchFilterGenres.adapter = FilterChipAdapter(Anilist.genres ?: listOf()) { chip ->
            val genre = chip.text.toString()
            chip.isChecked = selectedGenres.contains(genre)
            chip.isCloseIconVisible = exGenres.contains(genre)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    chip.isCloseIconVisible = false
                    exGenres.remove(genre)
                    selectedGenres.add(genre)
                } else
                    selectedGenres.remove(genre)
            }
            chip.setOnLongClickListener {
                chip.isChecked = false
                chip.isCloseIconVisible = true
                exGenres.add(genre)
            }
        }
        binding.searchGenresGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.searchFilterGenres.layoutManager =
                if (!isChecked) LinearLayoutManager(binding.root.context, HORIZONTAL, false)
                else GridLayoutManager(binding.root.context, 2, VERTICAL, false)
        }
        binding.searchGenresGrid.isChecked = false

        // List only checkbox (tri-state: unchecked=all, checked=on list, indeterminate=not on list)
        if (Anilist.userid != null) {
            binding.searchListOnly.checkedState = when (listOnly) {
                null -> com.google.android.material.checkbox.MaterialCheckBox.STATE_UNCHECKED
                true -> com.google.android.material.checkbox.MaterialCheckBox.STATE_CHECKED
                false -> com.google.android.material.checkbox.MaterialCheckBox.STATE_INDETERMINATE
            }
            binding.searchListOnly.addOnCheckedStateChangedListener { _, state ->
                listOnly = when (state) {
                    com.google.android.material.checkbox.MaterialCheckBox.STATE_CHECKED -> true
                    com.google.android.material.checkbox.MaterialCheckBox.STATE_INDETERMINATE -> false
                    else -> null
                }
            }
            binding.searchListOnly.setOnTouchListener { _, event ->
                (event.actionMasked == android.view.MotionEvent.ACTION_DOWN).also {
                    if (it) binding.searchListOnly.checkedState =
                        (binding.searchListOnly.checkedState + 1) % 3
                }
            }
        } else {
            binding.searchListOnly.visibility = android.view.View.GONE
        }

        updateTagsList(isAdult)
        // Adult tags switch
        binding.searchTagsAdult.isChecked = isAdult
        if (!Anilist.adult) {
            binding.searchTagsAdult.visibility = android.view.View.GONE
            binding.searchTagsAdultContainer.visibility = android.view.View.GONE
        }
        binding.searchTagsAdult.setOnCheckedChangeListener { _, isChecked ->
            isAdult = isChecked
            updateTagsList(isChecked)
        }
        binding.searchTagsGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.searchFilterTags.layoutManager =
                if (!isChecked) LinearLayoutManager(binding.root.context, HORIZONTAL, false)
                else createWrapChipLayoutManager()
        }
        binding.searchTagsGrid.isChecked = false
        binding.searchTagsQuickSearchText.addTextChangedListener {
            tagSearchQuery = it?.toString().orEmpty()
            updateTagsList(binding.searchTagsAdult.isChecked)
        }
        setTagSearchMode(false)

        binding.searchTagsAnilist.setOnClickListener {
            val searchActive = binding.searchTagsQuickSearchLayout.visibility == View.VISIBLE
            setTagSearchMode(!searchActive)
        }
    }


    class FilterChipAdapter(val list: List<String>, private val perform: ((Chip) -> Unit)) :
        RecyclerView.Adapter<FilterChipAdapter.SearchChipViewHolder>() {
        inner class SearchChipViewHolder(val binding: ItemChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchChipViewHolder {
            val binding =
                ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return SearchChipViewHolder(binding)
        }


        override fun onBindViewHolder(holder: SearchChipViewHolder, position: Int) {
            val title = list[position]
            holder.setIsRecyclable(false)
            holder.binding.root.apply {
                text = title
                isCheckable = true
                perform.invoke(this)
            }
        }

        override fun getItemCount(): Int = list.size
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        fun newInstance() = SearchFilterBottomDialog()
    }
}