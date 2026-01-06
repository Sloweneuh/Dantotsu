package ani.dantotsu.media.user

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.HORIZONTAL
import androidx.recyclerview.widget.RecyclerView.VERTICAL
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.BottomSheetListFilterBinding
import ani.dantotsu.databinding.ItemChipBinding
import com.google.android.material.chip.Chip
import java.util.Calendar

class ListFilterBottomDialog(
    private val isAnime: Boolean,
    currentFilters: ListFilters,
    private val onApply: (ListFilters) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetListFilterBinding? = null
    private val binding get() = _binding!!

    private var selectedGenres = currentFilters.genres.toMutableList()
    private var selectedTags = currentFilters.tags.toMutableList()
    private var selectedFormats = currentFilters.formats.toMutableList()
    private var selectedStatuses = currentFilters.statuses.toMutableList()
    private var selectedSources = currentFilters.sources.toMutableList()
    private var selectedSeason: String? = currentFilters.season
    private var selectedYear: Int? = currentFilters.year
    private var selectedCountry: String? = currentFilters.countryOfOrigin
    private var scoreRange = currentFilters.scoreRange
    private var yearRange = currentFilters.yearRange

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetListFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDropdowns()
        setupRangeSliders()
        setupChipLists()
        setupButtons()
        setupCountryFilter()
    }

    private fun setupDropdowns() {
        // Source
        binding.listFilterSource.setText(selectedSources.firstOrNull())
        binding.listFilterSource.setAdapter(
            ArrayAdapter(
                binding.root.context,
                R.layout.item_dropdown,
                Anilist.source.toTypedArray()
            )
        )

        // Format
        binding.listFilterFormat.setText(selectedFormats.firstOrNull())
        binding.listFilterFormat.setAdapter(
            ArrayAdapter(
                binding.root.context,
                R.layout.item_dropdown,
                (if (isAnime) Anilist.animeFormats else Anilist.mangaFormats).toTypedArray()
            )
        )

        // Status
        binding.listFilterStatus.setText(selectedStatuses.firstOrNull())
        binding.listFilterStatus.setAdapter(
            ArrayAdapter(
                binding.root.context,
                R.layout.item_dropdown,
                if (isAnime) Anilist.animeStatus else Anilist.mangaStatus
            )
        )

        // Season (anime only)
        if (!isAnime) {
            binding.listFilterSeasonCont.visibility = GONE
        } else {
            binding.listFilterSeason.setText(selectedSeason)
            binding.listFilterSeason.setAdapter(
                ArrayAdapter(
                    binding.root.context,
                    R.layout.item_dropdown,
                    Anilist.seasons.toTypedArray()
                )
            )
        }
    }

    private fun setupRangeSliders() {
        // Score range (0.0-10.0 for display)
        binding.listFilterScoreRange.values = listOf(scoreRange.first, scoreRange.second)

        // Year range
        binding.listFilterYearRange.values = listOf(yearRange.first.toFloat(), yearRange.second.toFloat())
    }

    private fun setupChipLists() {
        // Genres
        binding.listFilterGenres.adapter = FilterChipAdapter(Anilist.genres ?: listOf()) { chip ->
            val genre = chip.text.toString()
            chip.isChecked = selectedGenres.contains(genre)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedGenres.add(genre)
                else selectedGenres.remove(genre)
            }
        }
        binding.listFilterGenresGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.listFilterGenres.layoutManager =
                if (!isChecked) LinearLayoutManager(binding.root.context, HORIZONTAL, false)
                else GridLayoutManager(binding.root.context, 2, VERTICAL, false)
        }
        binding.listFilterGenresGrid.isChecked = false

        // Tags - controlled by adult tags switch
        fun updateTagsList(includeAdult: Boolean) {
            val tagsList = if (includeAdult && Anilist.adult) {
                // Combine both adult and non-adult tags if switch is on and user has adult content enabled
                val adultTags = Anilist.tags?.get(true) ?: listOf()
                val nonAdultTags = Anilist.tags?.get(false) ?: listOf()
                (adultTags + nonAdultTags).distinct().sorted()
            } else {
                // Only non-adult tags
                Anilist.tags?.get(false) ?: listOf()
            }

            binding.listFilterTags.adapter = FilterChipAdapter(tagsList) { chip ->
                val tag = chip.text.toString()
                chip.isChecked = selectedTags.contains(tag)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedTags.add(tag)
                    else selectedTags.remove(tag)
                }
            }
        }

        // Initialize with non-adult tags
        updateTagsList(false)

        // Set up adult tags switch
        binding.listFilterTagsAdult.isChecked = false
        // Hide adult switch if user doesn't have adult content enabled in settings
        if (!Anilist.adult) {
            binding.listFilterTagsAdult.visibility = GONE
        }

        binding.listFilterTagsAdult.setOnCheckedChangeListener { _, isChecked ->
            updateTagsList(isChecked)
        }

        binding.listFilterTagsGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.listFilterTags.layoutManager =
                if (!isChecked) LinearLayoutManager(binding.root.context, HORIZONTAL, false)
                else GridLayoutManager(binding.root.context, 2, VERTICAL, false)
        }
        binding.listFilterTagsGrid.isChecked = false
    }

    private fun setupButtons() {
        binding.resetListFilter.setOnClickListener {
            val rotateAnimation = ObjectAnimator.ofFloat(binding.resetListFilter, "rotation", 180f, 540f)
            rotateAnimation.duration = 500
            rotateAnimation.interpolator = AccelerateDecelerateInterpolator()
            rotateAnimation.start()

            resetFilters()
        }

        binding.listFilterCancel.setOnClickListener {
            dismiss()
        }

        binding.listFilterApply.setOnClickListener {
            val filters = ListFilters(
                genres = selectedGenres,
                tags = selectedTags,
                formats = listOfNotNull(binding.listFilterFormat.text.toString().takeIf { it.isNotBlank() }),
                statuses = listOfNotNull(binding.listFilterStatus.text.toString().takeIf { it.isNotBlank() }),
                sources = listOfNotNull(binding.listFilterSource.text.toString().takeIf { it.isNotBlank() }),
                season = binding.listFilterSeason.text.toString().takeIf { it.isNotBlank() },
                year = null, // Removed - using yearRange instead
                countryOfOrigin = selectedCountry,
                scoreRange = Pair(
                    binding.listFilterScoreRange.values[0],
                    binding.listFilterScoreRange.values[1]
                ),
                yearRange = Pair(
                    binding.listFilterYearRange.values[0].toInt(),
                    binding.listFilterYearRange.values[1].toInt()
                )
            )
            onApply(filters)
            dismiss()
        }
    }

    private fun setupCountryFilter() {
        updateCountryIcon()

        binding.countryFilter.setOnClickListener {
            val popupMenu = PopupMenu(requireContext(), it)
            popupMenu.menuInflater.inflate(R.menu.country_filter_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.country_global -> {
                        selectedCountry = null
                        updateCountryIcon()
                    }
                    R.id.country_china -> {
                        selectedCountry = "CN"
                        updateCountryIcon()
                    }
                    R.id.country_south_korea -> {
                        selectedCountry = "KR"
                        updateCountryIcon()
                    }
                    R.id.country_japan -> {
                        selectedCountry = "JP"
                        updateCountryIcon()
                    }
                    R.id.country_taiwan -> {
                        selectedCountry = "TW"
                        updateCountryIcon()
                    }
                }
                true
            }
            popupMenu.show()
        }
    }

    private fun updateCountryIcon() {
        val icon = when (selectedCountry) {
            "CN" -> R.drawable.ic_round_globe_china_googlefonts
            "KR" -> R.drawable.ic_round_globe_south_korea_googlefonts
            "JP" -> R.drawable.ic_round_globe_japan_googlefonts
            "TW" -> R.drawable.ic_round_globe_taiwan_googlefonts
            else -> R.drawable.ic_round_globe_search_googlefonts
        }
        binding.countryFilter.setImageResource(icon)

        val bounceAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.bounce_zoom)
        binding.countryFilter.startAnimation(bounceAnimation)
    }

    private fun resetFilters() {
        selectedGenres.clear()
        selectedTags.clear()
        selectedFormats.clear()
        selectedStatuses.clear()
        selectedSources.clear()
        selectedSeason = null
        selectedYear = null
        selectedCountry = null
        scoreRange = Pair(0.0f, 10.0f)
        yearRange = Pair(1970, 2028)

        binding.listFilterSource.setText("")
        binding.listFilterFormat.setText("")
        binding.listFilterStatus.setText("")
        binding.listFilterSeason.setText("")
        binding.listFilterScoreRange.values = listOf(0.0f, 10.0f)
        binding.listFilterYearRange.values = listOf(1970f, 2028f)

        binding.listFilterGenres.adapter?.notifyDataSetChanged()
        binding.listFilterTags.adapter?.notifyDataSetChanged()

        updateCountryIcon()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class FilterChipAdapter(
        private val list: List<String>,
        private val perform: ((Chip) -> Unit)
    ) : RecyclerView.Adapter<FilterChipAdapter.SearchChipViewHolder>() {

        inner class SearchChipViewHolder(val binding: ItemChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchChipViewHolder {
            val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
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
}

data class ListFilters(
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val season: String? = null,
    val year: Int? = null,
    val countryOfOrigin: String? = null,
    val scoreRange: Pair<Float, Float> = Pair(0.0f, 10.0f),
    val yearRange: Pair<Int, Int> = Pair(1970, 2028)
) {
    fun isEmpty(): Boolean {
        return genres.isEmpty() && tags.isEmpty() && formats.isEmpty() &&
                statuses.isEmpty() && sources.isEmpty() && season == null &&
                year == null && countryOfOrigin == null &&
                scoreRange == Pair(0.0f, 10.0f) && yearRange == Pair(1970, 2028)
    }

    // Convert display score (0.0-10.0) to internal score (0-100)
    fun getInternalScoreRange(): Pair<Int, Int> {
        return Pair(
            (scoreRange.first * 10).toInt(),
            (scoreRange.second * 10).toInt()
        )
    }
}

