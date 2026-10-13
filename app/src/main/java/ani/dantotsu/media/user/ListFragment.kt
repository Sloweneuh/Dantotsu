package ani.dantotsu.media.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
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
    /**
     * Which of the view model's lists this tab shows, by name.
     *
     * [pos] — the index into that map — is what this used to go by, and it is only stable while the
     * map's shape is. It no longer is: the screen now paints a stored response before the fresh one
     * arrives, and if the user has added, removed or renamed a list in between, the two have
     * different keys in different places. Since fragments survive that swap now, an index would
     * quietly start pointing at somebody else's list. The name does not move.
     *
     * Null for the calendar, which has no named lists and indexes its own data.
     */
    private var listKey: String? = null
    private var calendar = false
    private var grid: Boolean? = null
    private var list: MutableList<Media>? = null
    private var muList: List<MUMedia>? = null
    private var mediaAdaptor: MediaAdaptor? = null
    private var mergedAdaptor: MergedReadingAdapter? = null
    private var currentAdapterType: Int? = null
    private var currentSpanCount: Int? = null
    /** The list [mediaAdaptor] shows — the tab's own copy, so updates can be diffed into it. */
    private val shownList = ArrayList<Media>()

    /** What each shown row displays, as of the last update; see [RowKey]. */
    private var shownRows: List<RowKey> = emptyList()

    /**
     * Everything a list row shows. Each pass the screen gets (cache, network, favourites,
     * MangaUpdates) brings fresh Media objects, so rows are compared by what they display.
     */
    private data class RowKey(
        val id: Int,
        val title: String,
        val cover: String?,
        val progress: Int?,
        val score: Int,
        val status: String?,
        val favourite: Boolean,
        val meanScore: Int?,
    )

    private fun rowKey(media: Media) = RowKey(
        media.id, media.userPreferredName, media.cover, media.userProgress, media.userScore,
        media.userStatus, media.isFav, media.meanScore,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pos = it.getInt("list")
            listKey = it.getString("listKey")
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

            // Recreating the layout manager (like swapping the adapter below) resets scroll
            // position and detaches every row. A background list refresh can call update() again
            // right as the user taps an item, and doing either unconditionally would strand the
            // tapped view's shared-element transition on stale, no-longer-on-screen bounds — so
            // only touch these when the display mode actually changed.
            if (currentSpanCount != spanCount || binding.listRecyclerView.layoutManager == null) {
                binding.listRecyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount)
                currentSpanCount = spanCount
            }

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

                val type = if (g) 0 else 1
                val existing = mergedAdaptor
                if (existing != null && mediaAdaptor == null && currentAdapterType == type) {
                    // Same display mode, same adapter kind already showing: update its rows in
                    // place rather than tearing down the RecyclerView's views and scroll position.
                    existing.submitList(mergedItems)
                } else {
                    val mergedAdapter = MergedReadingAdapter(mergedItems, type, true)
                    mergedAdaptor = mergedAdapter
                    currentAdapterType = type
                    mediaAdaptor = null
                    binding.listRecyclerView.adapter = mergedAdapter
                }
            } else {
                val type = if (g) 0 else 1
                // Each pass the screen gets (cached lists, the network's, favourites, MangaUpdates)
                // used to rebuild or re-bind every row, replaying every cover load and entrance
                // animation: the list flashed once per pass. Rows are diffed instead, so only those
                // that actually changed rebind, and a pass that changed nothing does nothing. The
                // adapter and its scroll position are kept unless the display mode changed.
                val newRows = aniList.map(::rowKey)
                val adaptor = mediaAdaptor
                if (adaptor != null && mergedAdaptor == null && currentAdapterType == type) {
                    val oldRows = shownRows
                    val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                        override fun getOldListSize() = oldRows.size
                        override fun getNewListSize() = newRows.size
                        override fun areItemsTheSame(o: Int, n: Int) = oldRows[o].id == newRows[n].id
                        override fun areContentsTheSame(o: Int, n: Int) = oldRows[o] == newRows[n]
                    })
                    shownList.clear()
                    shownList.addAll(aniList)
                    shownRows = newRows
                    diff.dispatchUpdatesTo(adaptor)
                } else {
                    mergedAdaptor = null
                    shownList.clear()
                    shownList.addAll(aniList)
                    shownRows = newRows
                    val anilistAdaptor = MediaAdaptor(type, shownList, requireActivity(), true)
                    mediaAdaptor = anilistAdaptor
                    currentAdapterType = type
                    binding.listRecyclerView.adapter = anilistAdaptor
                }
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
                val key = keyIn(aniMap) ?: return null
                return if (key == "All") muMap.values.flatten() else muMap[key]
            }

            model.getLists().observe(viewLifecycleOwner) { aniMap ->
                if (aniMap != null) {
                    list = keyIn(aniMap)?.let { aniMap[it] as? MutableList<Media> }
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

    /**
     * This tab's key in [map], preferring the name it was created with and falling back to the
     * positional argument for a fragment restored from before the name was recorded.
     */
    private fun keyIn(map: Map<String, *>?): String? {
        if (map == null) return null
        listKey?.let { key -> if (map.containsKey(key)) return key }
        return map.keys.toList().getOrNull(pos ?: return null)
    }

    fun scrollToTop() {
        if (_binding == null) return
        binding.listRecyclerView.scrollToPosition(10)
        binding.listRecyclerView.smoothScrollToPosition(0)
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
        fun newInstance(pos: Int, calendar: Boolean = false, listKey: String? = null): ListFragment =
            ListFragment().apply {
                arguments = Bundle().apply {
                    putInt("list", pos)
                    putString("listKey", listKey)
                    putBoolean("calendar", calendar)
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
