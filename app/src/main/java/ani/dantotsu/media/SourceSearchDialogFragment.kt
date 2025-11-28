package ani.dantotsu.media

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.appcompat.widget.ListPopupWindow
import ani.dantotsu.R
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils.clamp
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetSourceSearchBinding
import ani.dantotsu.media.anime.AnimeSourceAdapter
import ani.dantotsu.media.manga.MangaSourceAdapter
import ani.dantotsu.navBarHeight
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.HMangaSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.px
import ani.dantotsu.tryWithSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceSearchDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSourceSearchBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private var searched = false
    var anime = true
    var i: Int? = null
    var id: Int? = null
    var media: Media? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSourceSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarHeight }

        val scope = requireActivity().lifecycleScope
        val imm =
            requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        model.getMedia().observe(viewLifecycleOwner) {
            media = it
            if (media != null) {
                binding.mediaListProgressBar.visibility = View.GONE
                binding.mediaListLayout.visibility = View.VISIBLE

                binding.searchRecyclerView.visibility = View.GONE
                binding.searchProgress.visibility = View.VISIBLE

                i = media!!.selected!!.sourceIndex

                val source = if (media!!.anime != null) {
                    (if (media!!.isAdult) HAnimeSources else AnimeSources)[i!!]
                } else {
                    anime = false
                    (if (media!!.isAdult) HMangaSources else MangaSources)[i!!]
                }

                // Build a list of candidate titles/synonyms to show in a dropdown dialog.
                // We try a few common properties on the Media object; if they don't exist,
                // fall back to the existing mangaName() helper value.
                val titleOptions: List<String> = run {
                    val list = mutableListOf<String>()
                    try {
                        // Common Media properties used across the app: title, romaji, english, synonyms
                        // Use reflection defensively because Media's exact shape may vary.
                        media?.let { m ->
                            // Primary titles
                            list.addAll(listOfNotNull(
                                getStringProp(m, "title"),
                                getStringProp(m, "romaji"),
                                getStringProp(m, "english"),
                                getStringProp(m, "native")
                            ).map { it.trim() }.filter { it.isNotEmpty() })

                            // Alternative titles/synonyms arrays/maps
                            // Try common fields that might hold synonyms: alternativeTitles, synonyms, otherTitles
                            val alt = getAnyProp(m, "alternativeTitles")
                            if (alt is Map<*, *>) {
                                // values might include strings or lists
                                alt.values.forEach { v ->
                                    when (v) {
                                        is String -> if (v.isNotBlank()) list.add(v)
                                        is Collection<*> -> v.forEach { if (it is String && it.isNotBlank()) list.add(it) }
                                    }
                                }
                            }
                            val syn = getAnyProp(m, "synonyms")
                            if (syn is Collection<*>) {
                                syn.forEach { if (it is String && it.isNotBlank()) list.add(it) }
                            }
                            // Some Media implementations expose a list of titles under `titles`
                            val titlesAny = getAnyProp(m, "titles")
                            if (titlesAny is Collection<*>) {
                                titlesAny.forEach { if (it is String && it.isNotBlank()) list.add(it) }
                            }

                            // finally, fallback helper
                            val fallback = try { m.mangaName() } catch (_: Throwable) { null }
                            if (!fallback.isNullOrBlank()) list.add(fallback)
                        }
                    } catch (_: Throwable) {
                    }
                    // dedupe while keeping original order
                    val seen = linkedSetOf<String>()
                    list.map { it.trim() }.filterTo(mutableListOf()) { seen.add(it) }
                }

                fun search() {
                    binding.searchBarText.clearFocus()
                    imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
                    scope.launch {
                        model.responses.postValue(
                            withContext(Dispatchers.IO) {
                                tryWithSuspend {
                                    source.search(binding.searchBarText.text.toString())
                                }
                            }
                        )
                    }
                }

                binding.searchSourceTitle.text = source.name
                binding.searchBarText.setText(media!!.mangaName())

                // Use the TextInputLayout end icon as the dropdown trigger (robust, accessible)
                binding.searchBar.setEndIconOnClickListener {
                    if (titleOptions.size <= 1) return@setEndIconOnClickListener

                    val adapter = ArrayAdapter(requireContext(), R.layout.item_titles_dropdown, titleOptions)
                     val popup = ListPopupWindow(requireContext())
                     popup.anchorView = binding.searchBarText
                     popup.setAdapter(adapter)
                     popup.isModal = true

                    binding.searchBar.post {
                        popup.width = binding.searchBar.width
                        popup.verticalOffset = binding.searchBar.height
                        popup.setBackgroundDrawable(
                            ContextCompat.getDrawable(requireContext(), R.drawable.dropdown_background)
                        )
                        try { popup.listView?.elevation = 12f } catch (_: Throwable) {}
                        popup.show()
                    }

                    popup.setOnItemClickListener { _, _, position, _ ->
                        val selected = titleOptions[position]
                        binding.searchBarText.setText(selected)
                        popup.dismiss()
                        // automatically perform the search for convenience
                        search()
                    }
                }

                binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
                    return@setOnEditorActionListener when (actionId) {
                        EditorInfo.IME_ACTION_SEARCH -> {
                            search()
                            true
                        }

                        else -> false
                    }
                }
                // end icon is used as dropdown trigger; searching is done via IME or the search end icon inside TextInputLayout if desired
                if (!searched) search()
                searched = true
                model.responses.observe(viewLifecycleOwner) { j ->
                    if (j != null) {
                        binding.searchRecyclerView.visibility = View.VISIBLE
                        binding.searchProgress.visibility = View.GONE
                        binding.searchRecyclerView.adapter =
                            if (anime) AnimeSourceAdapter(j, model, i!!, media!!.id, this, scope)
                            else MangaSourceAdapter(j, model, i!!, media!!.id, this, scope)
                        binding.searchRecyclerView.layoutManager = GridLayoutManager(
                            requireActivity(),
                            clamp(
                                requireActivity().resources.displayMetrics.widthPixels / 124f.px,
                                1,
                                4
                            )
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun dismiss() {
        model.responses.value = null
        super.dismiss()
    }

    // Helper reflection getters to gather possible title fields without depending on Media implementation details
    private fun getStringProp(obj: Any, name: String): String? {
        return try {
            val f = obj::class.java.getDeclaredField(name)
            f.isAccessible = true
            (f.get(obj) as? String)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getAnyProp(obj: Any, name: String): Any? {
        return try {
            val f = obj::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.get(obj)
        } catch (_: Throwable) {
            null
        }
    }
}