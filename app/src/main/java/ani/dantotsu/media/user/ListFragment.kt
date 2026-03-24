package ani.dantotsu.media.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUMediaAdapter
import ani.dantotsu.home.MergedReadingAdapter
import ani.dantotsu.connections.mangaupdates.toMedia
import ani.dantotsu.databinding.FragmentListBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaAdaptor
import ani.dantotsu.media.MediaRandomDialogFragment
import ani.dantotsu.media.OtherDetailsViewModel

class ListFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private var pos: Int? = null
    private var calendar = false
    private var grid: Boolean? = null
    private var list: MutableList<Media>? = null
    private var muList: List<MUMedia>? = null
    private var mediaAdaptor: MediaAdaptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pos = it.getInt("list")
            calendar = it.getBoolean("calendar")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val screenWidth = resources.displayMetrics.run { widthPixels / density }

        fun update() {
            val g = grid ?: return
            val aniList = list ?: return
            val muItems = muList ?: emptyList()

            val spanCount = if (g) (screenWidth / 120f).toInt() else 1

            val layoutManager = GridLayoutManager(requireContext(), spanCount)
            binding.listRecyclerView.layoutManager = layoutManager

            // If there are MU items, merge them with AniList items into a single sorted list
            if (muItems.isNotEmpty()) {
                // Build a combined list of Any (Media | MUMedia)
                val combined: List<Any> = aniList.map { it as Any } + muItems

                // Determine the current sort preference (use Manga sort when MU present)
                val isManga = aniList.firstOrNull()?.manga != null || muItems.isNotEmpty()
                val prefName = if (isManga) ani.dantotsu.settings.saving.PrefName.MangaListSortOrder else ani.dantotsu.settings.saving.PrefName.AnimeListSortOrder
                val sortPref: String = ani.dantotsu.settings.saving.PrefManager.getVal<String>(prefName)
                val baseKey = sortPref.removeSuffix("_asc").removeSuffix("_desc")
                val ascending = sortPref.endsWith("_asc")

                val mergedItems: List<Any> = if (baseKey.isBlank()) {
                    // No explicit sort: keep AniList order then MU items
                    combined
                } else {
                    // Sort by the requested key, mapping MU fields to comparable values
                    fun extractComparable(item: Any): Comparable<Any?> {
                        return when (baseKey) {
                            "score" -> when (item) {
                                is ani.dantotsu.media.Media -> {
                                    val v = if (item.userScore != 0) item.userScore.toDouble() else (item.meanScore?.toDouble() ?: 0.0)
                                    v as Comparable<Any?>
                                }
                                is MUMedia -> ((item.bayesianRating ?: 0.0) * 10.0) as Comparable<Any?>
                                else -> 0.0 as Comparable<Any?>
                            }
                            "title" -> when (item) {
                                is ani.dantotsu.media.Media -> item.userPreferredName.lowercase() as Comparable<Any?>
                                is MUMedia -> (item.title ?: "").lowercase() as Comparable<Any?>
                                else -> "" as Comparable<Any?>
                            }
                            "release" -> when (item) {
                                is ani.dantotsu.media.Media -> ((item.startDate?.year ?: Int.MIN_VALUE).toLong()) as Comparable<Any?>
                                is MUMedia -> 0L as Comparable<Any?>
                                else -> 0L as Comparable<Any?>
                            }
                            "updatedAt" -> when (item) {
                                is ani.dantotsu.media.Media -> (item.userUpdatedAt ?: 0L) as Comparable<Any?>
                                is MUMedia -> (item.updatedAt ?: 0L) as Comparable<Any?>
                                else -> 0L as Comparable<Any?>
                            }
                            else -> when (item) {
                                is ani.dantotsu.media.Media -> (item.userUpdatedAt ?: 0L) as Comparable<Any?>
                                is MUMedia -> (item.updatedAt ?: 0L) as Comparable<Any?>
                                else -> 0L as Comparable<Any?>
                            }
                        }
                    }

                    val comparator = Comparator<Any> { a: Any, b: Any ->
                        val va = extractComparable(a)
                        val vb = extractComparable(b)
                        return@Comparator try {
                            @Suppress("UNCHECKED_CAST")
                            (va as Comparable<Any?>).compareTo(vb as Any?)
                        } catch (_: Exception) { 0 }
                    }

                    val sorted = combined.sortedWith(comparator)
                    if (ascending) sorted else sorted.reversed()
                }

                val mergedAdapter = MergedReadingAdapter(mergedItems, if (g) 0 else 1)
                mediaAdaptor = null
                binding.listRecyclerView.adapter = mergedAdapter
            } else {
                val anilistAdaptor = MediaAdaptor(if (g) 0 else 1, aniList, requireActivity(), true)
                mediaAdaptor = anilistAdaptor
                binding.listRecyclerView.adapter = anilistAdaptor
            }
        }

        if (calendar) {
            val model: OtherDetailsViewModel by activityViewModels()
            model.getCalendar().observe(viewLifecycleOwner) {
                if (it != null) {
                    list = it.values.toList().getOrNull(pos!!)
                    update()
                }
            }
            grid = true
        } else {
            val model: ListViewModel by activityViewModels()

            fun resolveMuList(
                aniMap: Map<String, *>?,
                muMap: Map<String, List<MUMedia>>?
            ): List<MUMedia>? {
                if (muMap == null) return null
                val key = aniMap?.keys?.toList()?.getOrNull(pos!!) ?: return null
                return if (key == "All") muMap.values.flatten() else muMap[key]
            }

            model.getLists().observe(viewLifecycleOwner) { aniMap ->
                if (aniMap != null) {
                    list = aniMap.values.toList().getOrNull(pos!!)
                    muList = resolveMuList(aniMap, model.getFilteredMuLists().value)
                    update()
                }
            }
            model.getFilteredMuLists().observe(viewLifecycleOwner) { muMap ->
                muList = resolveMuList(model.getLists().value, muMap)
                update()
            }
            model.grid.observe(viewLifecycleOwner) {
                grid = it
                update()
            }
        }
    }

    fun randomOptionClick() {
        val aniItems: List<Media> = list ?: emptyList()
        val muItems: List<Media> = (muList ?: emptyList()).map { it.toMedia() }
        val combined = ArrayList(aniItems + muItems)
        if (combined.isNotEmpty()) {
            MediaRandomDialogFragment.newInstance(combined)
                .show(parentFragmentManager, "random")
        }
    }

    companion object {
        fun newInstance(pos: Int, calendar: Boolean = false): ListFragment =
            ListFragment().apply {
                arguments = Bundle().apply {
                    putInt("list", pos)
                    putBoolean("calendar", calendar)
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
