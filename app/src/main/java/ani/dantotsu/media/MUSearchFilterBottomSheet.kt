package ani.dantotsu.media

import android.annotation.SuppressLint
import android.os.Bundle
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MUSearchFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMuSearchFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var activity: SearchActivity

    private var selectedGenres = mutableListOf<String>()
    private var excludedGenres = mutableListOf<String>()

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

        setupFormat()
        setupYear()
        loadGenresAndCategories()

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

    private fun applyFilters() {
        val r = activity.muSearchResult
        r.format = binding.muFilterFormat.text.toString().ifEmpty { null }
        r.year = binding.muFilterYear.text.toString().toIntOrNull()
        r.genres = selectedGenres.toMutableList().ifEmpty { null }
        r.excludedGenres = excludedGenres.toMutableList().ifEmpty { null }
        r.categories = null
        r.excludedCategories = null

        activity.updateMuChips?.invoke()
        activity.emptyMediaAdapter()
        activity.search()
    }

    private fun resetAll() {
        selectedGenres.clear()
        excludedGenres.clear()
        binding.muFilterFormat.setText("", false)
        binding.muFilterYear.setText("", false)
        @Suppress("NotifyDataSetChanged")
        binding.muFilterGenresRecycler.adapter?.notifyDataSetChanged()
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
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = MUSearchFilterBottomSheet()
    }
}
