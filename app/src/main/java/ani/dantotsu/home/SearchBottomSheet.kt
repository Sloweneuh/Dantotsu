package ani.dantotsu.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.databinding.BottomSheetSearchBinding
import ani.dantotsu.media.SearchActivity

class SearchBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSearchBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_QUERY = "query"

        fun newInstance(query: String?): SearchBottomSheet {
            val f = SearchBottomSheet()
            val args = Bundle()
            args.putString(ARG_QUERY, query)
            f.arguments = args
            return f
        }
        fun newInstance(): SearchBottomSheet = newInstance(null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentQuery = arguments?.getString(ARG_QUERY)

        binding.animeSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.ANIME, currentQuery)
            dismiss()
        }
        binding.mangaSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.MANGA, currentQuery)
            dismiss()
        }
        binding.characterSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.CHARACTER, currentQuery)
            dismiss()
        }
        binding.staffSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.STAFF, currentQuery)
            dismiss()
        }
        binding.studioSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.STUDIO, currentQuery)
            dismiss()
        }
        binding.userSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.USER, currentQuery)
            dismiss()
        }
        binding.muSearch.visibility = if (MangaUpdates.token != null) View.VISIBLE else View.GONE
        binding.muSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.MANGAUPDATES, currentQuery)
            dismiss()
        }
    }

    private fun startActivity(context: Context, type: SearchType, query: String?) {
        val intent = Intent(context, SearchActivity::class.java).putExtra("type", type.toAnilistString())
        if (!query.isNullOrBlank()) intent.putExtra("query", query)
        // If opened from an existing SearchActivity, copy compatible filters
        val src = activity as? SearchActivity
        if (src != null) {
            when (type) {
                SearchType.ANIME, SearchType.MANGA -> {
                    when (src.searchType) {
                        SearchType.ANIME, SearchType.MANGA -> {
                            src.aniMangaResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                            src.aniMangaResult.genres?.takeIf { it.isNotEmpty() }?.let {
                                intent.putStringArrayListExtra("genres", ArrayList(it))
                                intent.putExtra("genre", it.first())
                            }
                            src.aniMangaResult.tags?.takeIf { it.isNotEmpty() }?.let {
                                intent.putStringArrayListExtra("tags", ArrayList(it))
                                intent.putExtra("tag", it.first())
                            }
                            src.aniMangaResult.sort?.let { intent.putExtra("sortBy", it) }
                            src.aniMangaResult.status?.let { intent.putExtra("status", it) }
                            src.aniMangaResult.source?.let { intent.putExtra("source", it) }
                            src.aniMangaResult.format?.let { intent.putExtra("format", it) }
                            src.aniMangaResult.countryOfOrigin?.let { intent.putExtra("country", it) }
                            src.aniMangaResult.season?.let { intent.putExtra("season", it) }
                            src.aniMangaResult.seasonYear?.let { intent.putExtra("seasonYear", it.toString()) }
                            src.aniMangaResult.startYear?.let { intent.putExtra("seasonYear", it.toString()) }
                            if (src.aniMangaResult.onList == true) intent.putExtra("listOnly", true)
                            if (src.aniMangaResult.isAdult) intent.putExtra("hentai", true)
                        }
                        SearchType.MANGAUPDATES -> {
                            src.muSearchResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                            src.muSearchResult.genres?.takeIf { it.isNotEmpty() }?.let {
                                intent.putStringArrayListExtra("genres", ArrayList(it))
                                intent.putExtra("genre", it.first())
                            }
                        }
                        else -> {
                            // other source types: only forward the search text if present
                            when (src.searchType) {
                                SearchType.CHARACTER -> src.characterResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                                SearchType.STAFF -> src.staffResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                                SearchType.STUDIO -> src.studioResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                                SearchType.USER -> src.userResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                                else -> {}
                            }
                        }
                    }
                }

                SearchType.MANGAUPDATES -> {
                    when (src.searchType) {
                        SearchType.MANGAUPDATES -> {
                            src.muSearchResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                            src.muSearchResult.genres?.takeIf { it.isNotEmpty() }?.let { intent.putStringArrayListExtra("genres", ArrayList(it)) }
                            src.muSearchResult.categories?.takeIf { it.isNotEmpty() }?.let { intent.putStringArrayListExtra("categories", ArrayList(it)) }
                            src.muSearchResult.format?.let { intent.putExtra("format", it) }
                            src.muSearchResult.year?.let { intent.putExtra("year", it) }
                        }
                        SearchType.ANIME, SearchType.MANGA -> {
                            src.aniMangaResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                            src.aniMangaResult.genres?.takeIf { it.isNotEmpty() }?.let {
                                intent.putStringArrayListExtra("genres", ArrayList(it))
                                intent.putExtra("genre", it.first())
                            }
                            src.aniMangaResult.tags?.takeIf { it.isNotEmpty() }?.let {
                                intent.putStringArrayListExtra("tags", ArrayList(it))
                                intent.putExtra("tag", it.first())
                            }
                        }
                        else -> {
                            // from other types just forward query
                            when (src.searchType) {
                                SearchType.CHARACTER -> src.characterResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                                SearchType.STAFF -> src.staffResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                                SearchType.STUDIO -> src.studioResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                                SearchType.USER -> src.userResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                                else -> {}
                            }
                        }
                    }
                }

                else -> {
                    // target is other search types: forward only query
                    when (src.searchType) {
                        SearchType.ANIME, SearchType.MANGA -> src.aniMangaResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                        SearchType.MANGAUPDATES -> src.muSearchResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                        SearchType.CHARACTER -> src.characterResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                        SearchType.STAFF -> src.staffResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                        SearchType.STUDIO -> src.studioResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                        SearchType.USER -> src.userResult.search?.let { if (intent.getStringExtra("query") == null) intent.putExtra("query", it) }
                        else -> {}
                    }
                }
            }
        }

        ContextCompat.startActivity(context, intent, null)
        // If the bottom sheet was opened from an existing SearchActivity, finish it
        // so the new SearchActivity doesn't stack on top of the old one.
        if (src != null) {
            src.finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // companion object moved above
}