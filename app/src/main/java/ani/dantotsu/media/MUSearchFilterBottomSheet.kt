package ani.dantotsu.media

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.databinding.BottomSheetMuSearchFilterBinding
import ani.dantotsu.databinding.ItemChipBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MUSearchFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMuSearchFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var activity: SearchActivity

    private var selectedGenres = mutableListOf<String>()
    private var excludedGenres = mutableListOf<String>()
    private var selectedCategories = mutableListOf<String>()
    private var selectedStatusFilters = mutableListOf<String>()
    private var categorySearchJob: Job? = null

    // API value → display label for status filters
    private val statusFilterOptions = listOf(
        "scanlated" to "Scanlated",
        "completed" to "Completed",
        "oneshots" to "Oneshots",
        "no_oneshots" to "No Oneshots",
        "some_releases" to "Has Releases",
        "no_releases" to "No Releases",
    )

    // API value → display label for sort options (blank = default)
    private val sortOptions = listOf(
        "" to "",
        "score" to "Score",
        "title" to "Title",
        "year" to "Year",
        "rating" to "Rating",
        "date_added" to "Date Added",
        "week_pos" to "Weekly Rank",
        "month1_pos" to "Monthly Rank",
        "month3_pos" to "3-Month Rank",
        "month6_pos" to "6-Month Rank",
        "year_pos" to "Yearly Rank",
        "list_reading" to "Reading Count",
        "list_wish" to "Wish Count",
        "list_complete" to "Complete Count",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMuSearchFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as SearchActivity

        val r = activity.muSearchResult
        selectedGenres = r.genres?.toMutableList() ?: mutableListOf()
        excludedGenres = r.excludedGenres?.toMutableList() ?: mutableListOf()
        selectedCategories = r.categories?.toMutableList() ?: mutableListOf()
        selectedStatusFilters = r.statusFilters?.toMutableList() ?: mutableListOf()

        setupFormat()
        setupYear()
        setupLicensed()
        setupSort()
        setupStatusFilters()
        loadGenresAndCategories()
        setupCategories()

        binding.muFilterReset.setOnClickListener { resetAll() }
        binding.muFilterCancel.setOnClickListener { dismiss() }
        binding.muFilterApply.setOnClickListener {
            applyFilters()
            dismiss()
        }
    }

    private fun setupFormat() {
        val formats = listOf(
            "", "Manga", "Manhwa", "Manhua", "OEL", "Artbook", "Doujinshi",
            "Drama CD", "Filipino", "French", "German", "Indonesian", "Malaysian",
            "Nordic", "Novel", "Spanish", "Thai", "Vietnamese"
        )
        binding.muFilterFormat.setText(activity.muSearchResult.format ?: "", false)
        binding.muFilterFormat.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown, formats)
        )
    }

    private fun setupYear() {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR) + 1
        val years = listOf("") + (currentYear downTo 1950).map { it.toString() }
        binding.muFilterYear.setText(activity.muSearchResult.year?.toString() ?: "", false)
        binding.muFilterYear.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown, years)
        )
    }

    private fun setupLicensed() {
        val labels = listOf("", "Licensed", "Not Licensed")
        val current = when (activity.muSearchResult.licensed) {
            "yes" -> "Licensed"
            "no" -> "Not Licensed"
            else -> ""
        }
        binding.muFilterLicensed.setText(current, false)
        binding.muFilterLicensed.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown, labels)
        )
    }

    private fun setupSort() {
        val labels = sortOptions.map { it.second }
        val currentApiValue = activity.muSearchResult.orderBy ?: ""
        val currentLabel = sortOptions.firstOrNull { it.first == currentApiValue }?.second ?: ""
        binding.muFilterSort.setText(currentLabel, false)
        binding.muFilterSort.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown, labels)
        )
    }

    private fun setupStatusFilters() {
        binding.muFilterStatusRecycler.adapter =
            FilterChipAdapter(statusFilterOptions.map { it.second }) { chip ->
                val label = chip.text.toString()
                val apiValue = statusFilterOptions.first { it.second == label }.first
                chip.isChecked = selectedStatusFilters.contains(apiValue)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (!selectedStatusFilters.contains(apiValue)) selectedStatusFilters.add(apiValue)
                    } else {
                        selectedStatusFilters.remove(apiValue)
                    }
                }
            }
    }

    private fun loadGenresAndCategories() {
        CoroutineScope(Dispatchers.IO).launch {
            val genres = MangaUpdates.getGenres()

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                binding.muFilterGenresRecycler.adapter =
                    FilterChipAdapter(genres) { chip ->
                        val genre = chip.text.toString()
                        chip.isChecked = selectedGenres.contains(genre)
                        chip.isCloseIconVisible = excludedGenres.contains(genre)
                        chip.setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) {
                                chip.isCloseIconVisible = false
                                excludedGenres.remove(genre)
                                if (!selectedGenres.contains(genre)) selectedGenres.add(genre)
                            } else {
                                selectedGenres.remove(genre)
                            }
                        }
                        chip.setOnLongClickListener {
                            chip.isChecked = false
                            selectedGenres.remove(genre)
                            chip.isCloseIconVisible = true
                            if (!excludedGenres.contains(genre)) excludedGenres.add(genre)
                            true
                        }
                        chip.setOnCloseIconClickListener {
                            chip.isCloseIconVisible = false
                            excludedGenres.remove(genre)
                        }
                    }
                binding.muFilterGenresGrid.setOnCheckedChangeListener { _, isChecked ->
                    binding.muFilterGenresRecycler.layoutManager =
                        if (!isChecked) LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
                        else GridLayoutManager(requireContext(), 2, RecyclerView.VERTICAL, false)
                }
                binding.muFilterGenresGrid.isChecked = false
            }
        }
    }

    private fun setupCategories() {
        updateCategoryResults(selectedCategories.toList())

        binding.muFilterCategorySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                categorySearchJob?.cancel()
                if (query.isBlank()) {
                    binding.muFilterCategoryProgress.visibility = View.GONE
                    binding.muFilterCategoryCount.visibility = View.GONE
                    updateCategoryResults(selectedCategories.toList())
                    return
                }
                categorySearchJob = CoroutineScope(Dispatchers.IO).launch {
                    delay(400)
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.muFilterCategoryProgress.visibility = View.VISIBLE
                        binding.muFilterCategoryCount.visibility = View.GONE
                    }
                    val results = MangaUpdates.getCategories(query)
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.muFilterCategoryProgress.visibility = View.GONE
                        binding.muFilterCategoryCount.text = "(${results.size})"
                        binding.muFilterCategoryCount.visibility = View.VISIBLE
                        updateCategoryResults(results)
                    }
                }
            }
        })
    }

    private fun updateCategoryResults(categories: List<String>) {
        if (_binding == null) return
        binding.muFilterCategoryResultsRecycler.adapter =
            FilterChipAdapter(categories) { chip ->
                val category = chip.text.toString()
                chip.isChecked = selectedCategories.contains(category)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (!selectedCategories.contains(category)) selectedCategories.add(category)
                    } else {
                        selectedCategories.remove(category)
                    }
                }
            }
    }

    private fun applyFilters() {
        val r = activity.muSearchResult
        r.format = binding.muFilterFormat.text.toString().ifEmpty { null }
        r.year = binding.muFilterYear.text.toString().toIntOrNull()
        r.licensed = when (binding.muFilterLicensed.text.toString()) {
            "Licensed" -> "yes"
            "Not Licensed" -> "no"
            else -> null
        }
        r.orderBy = sortOptions.firstOrNull { it.second == binding.muFilterSort.text.toString() }
            ?.first?.ifEmpty { null }
        r.genres = selectedGenres.toMutableList().ifEmpty { null }
        r.excludedGenres = excludedGenres.toMutableList().ifEmpty { null }
        r.categories = selectedCategories.toMutableList().ifEmpty { null }
        r.excludedCategories = null
        r.statusFilters = selectedStatusFilters.toMutableList().ifEmpty { null }

        activity.updateMuChips?.invoke()
        activity.emptyMediaAdapter()
        activity.search()
    }

    private fun resetAll() {
        selectedGenres.clear()
        excludedGenres.clear()
        selectedCategories.clear()
        selectedStatusFilters.clear()
        binding.muFilterFormat.setText("", false)
        binding.muFilterYear.setText("", false)
        binding.muFilterLicensed.setText("", false)
        binding.muFilterSort.setText("", false)
        categorySearchJob?.cancel()
        binding.muFilterCategorySearch.setText("")
        binding.muFilterCategoryCount.visibility = View.GONE
        binding.muFilterCategoryProgress.visibility = View.GONE
        updateCategoryResults(emptyList())
        @Suppress("NotifyDataSetChanged")
        binding.muFilterGenresRecycler.adapter?.notifyDataSetChanged()
        @Suppress("NotifyDataSetChanged")
        binding.muFilterStatusRecycler.adapter?.notifyDataSetChanged()
    }

    class FilterChipAdapter(
        private val list: List<String>,
        private val perform: (Chip) -> Unit
    ) : RecyclerView.Adapter<FilterChipAdapter.VH>() {

        inner class VH(val binding: ItemChipBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.setIsRecyclable(false)
            holder.binding.root.apply {
                text = list[position]
                isCheckable = true
                perform.invoke(this)
            }
        }

        override fun getItemCount(): Int = list.size
    }

    override fun onDestroyView() {
        categorySearchJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = MUSearchFilterBottomSheet()
    }
}
